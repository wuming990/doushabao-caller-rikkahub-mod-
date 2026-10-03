package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.buildGroupModeratorInstruction
import me.rerere.rikkahub.data.ai.prompts.buildGroupTranscript
import me.rerere.rikkahub.data.ai.prompts.buildGroupTurnInstruction
import me.rerere.rikkahub.data.ai.prompts.moveGroupMessageToEnd
import me.rerere.rikkahub.data.ai.prompts.resolveModeratedSpeaker
import me.rerere.rikkahub.data.datastore.Settings
// findModelById 是 Settings 上的**顶层扩展函数**（定义在 PreferencesStore.kt），
// 跨文件使用必须显式 import —— 漏掉时编译器报 Unresolved reference，
// 还会连带把返回值推断成 Nothing，让后面每一处 .id 都跟着报错。
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupSpeakerMode
import me.rerere.rikkahub.data.model.nextRoundRobinSpeaker
import me.rerere.rikkahub.data.model.sanitizeGroupMembers
import kotlin.uuid.Uuid

/**
 * v313：AI 讨论群组的驱动器。
 *
 * ## 为什么做成「注入窄接口」而不是 ChatService 的内部类
 *
 * 圆桌的执行器是 ChatService 的 inner class，好处是能直接拿所有私有成员，代价是
 * 那 2700 行二改与圆桌逻辑彻底焊死 —— 出问题时只剩「读源码定位」这一条路。
 * 这里把引擎独立出来，只通过 [host] 那几把函数与主程序打交道：引擎本身可以用假实现
 * 真单测，ChatService 里只留一小段接线。
 *
 * ## 一次「继续讨论」的流程
 *
 * 挑人 → 让 TA 说话 → 说完了看这批额度够不够 → 够就换下一个人，不够就停下等用户。
 *
 * 成员若调用提问工具，整场停在「等你回答」；你答完后**由同一个成员**接着说。
 * 这一条是探测位抓出来的必修项：主对话那条续跑路径固定用「助手当前模型」
 * （`findModelById(assistant.chatModelId ?: settings.chatModelId)`），
 * 群里照抄会变成 A 提问、B 回答，讨论直接串味。
 *
 * ## 隔离规矩
 *
 * 引擎只在「这个对话被显式建过群」时才被调用；普通对话一个字都不走这里。
 * 反过来，群对话里用户自己发消息仍然走主对话正常路径 —— 用户可以随时插话。
 */
internal class GroupChatEngine(
    private val run: GroupChatRun,
    private val host: Host,
) {

    /** 引擎向主程序要的能力，全部以函数注入，方便单测替换 */
    internal interface Host {
        suspend fun settings(): Settings

        suspend fun conversation(): Conversation

        /** 该对话用哪个助手的设置（工具、系统提示词等） */
        suspend fun assistantFor(conversation: Conversation): Assistant

        /** 给成员装配工具，与主对话同一口径（搜索 / 工作区 / MCP / 提问） */
        suspend fun toolsFor(model: Model, assistant: Assistant, conversation: Conversation): List<Tool>

        /**
         * 让 [model] 说一次话，并由**实现方负责写进对话**。
         *
         * @param overwriteMessageId 非空表示「在同一条消息上继续写」（回答提问后的续写），
         *   刻意不新增一条 —— 否则用户会看到同一次发言被拆成两条。
         *   为空表示新发言，实现方追加一条助手消息并逐字流式刷新。
         * @param persist **false = 这次产出绝不写进对话**。主持人挑人只要一个名字，
         *   把「乙模型」当成正式发言存进用户的讨论记录是数据污染（审查位抓的 P0），
         *   而且它还会顺着上文被后面每个成员读到。
         * @return 这条发言的最终内容；什么都没产出时返回 null
         */
        suspend fun speak(
            model: Model,
            assistant: Assistant,
            messages: List<UIMessage>,
            tools: List<Tool>,
            overwriteMessageId: Uuid?,
            persist: Boolean = true,
            onActivity: (Int) -> Unit,
        ): UIMessage?

        /** 模型显示名。署名与上文用同一个来源，别两处各算一遍 */
        fun displayName(modelId: Uuid?): String?

        /** 失败必须显示给用户，不能只写日志 */
        fun reportError(error: Throwable, speakerName: String)

        /** 界面状态文字；传 null 清空 */
        fun setStatus(text: String?)
    }

    /**
     * 推进一批讨论。
     *
     * 用循环而不是递归：一批最多 turnsPerRun 条，额度用完就返回，由用户点「继续讨论」再开一批。
     * 每次「停下来等用户」都以 return 正常退出协程，不占着 job 干等 ——
     * 圆桌 v239 那次「点停止没反应」的根因就是协程卡在不可取消的上游上。
     */
    suspend fun advanceTurns() {
        while (true) {
            val state = run.snapshot()
            if (state.status == GroupRunStatus.STOPPED || state.status == GroupRunStatus.FINISHED) return
            if (state.budgetExhausted) {
                // 额度用完 = **暂停**，不是结束。状态留在 PAUSED，用户才能再点「继续讨论」
                // 开下一批。这里若标成 FINISHED，界面上的继续入口会消失，整场讨论作废。
                host.setStatus(null)
                return
            }

            val settings = host.settings()
            val conversation = host.conversation()
            val config = settings.groupChatConfigs[conversation.id]?.takeIf { it.enabled } ?: run {
                // 群设置被删掉了、或用户暂停了这个群：不再推进，但也不报错 ——
                // 用户可能就是想把这场退回普通对话
                run.markFinished()
                host.setStatus(null)
                return
            }
            val existing = settings.providers
                .flatMap { provider -> provider.models.map { it.id } }
                .toSet()
            val members = sanitizeGroupMembers(config.memberModelIds, existing)
            if (members.size < GroupChatConfig.MIN_MEMBERS) {
                run.markFinished()
                host.setStatus(null)
                return
            }

            val assistant = host.assistantFor(conversation)
            val speakerId = pickSpeaker(settings, config, members, conversation)
            if (speakerId == null) {
                run.markFinished()
                host.setStatus(null)
                return
            }
            // ⚠️ 这里刻意不写 `?: continue`：members 已经按「模型还存在」过滤过，
            // 正常不可达；但一旦不可达的事情发生，continue 是**没有状态变化的无限循环**
            // （游标没推进、额度没消耗，会一直空转烧 CPU）。宁可停下报错。
            val speaker = settings.findModelById(speakerId) ?: run {
                run.markFinished()
                host.setStatus(null)
                return
            }
            val speakerName = speaker.displayName

            run.markSpeaking(speakerId, speakerName)
            host.setStatus("🗣 $speakerName 正在发言")

            val outcome = try {
                speakOnce(speaker, speakerName, config, members, assistant, conversation)
            } catch (e: CancellationException) {
                // 用户主动停止：原样上抛，绝不当成「这个成员失败了，换下一个」
                run.markStopped()
                host.setStatus(null)
                throw e
            } catch (e: Exception) {
                val canContinue = run.markTurnFailed(
                    modelId = speakerId,
                    speakerName = speakerName,
                    reason = e.message ?: e.toString(),
                )
                host.reportError(e, speakerName)
                if (!canContinue) {
                    host.setStatus(null)
                    return
                }
                continue
            }

            when (outcome) {
                // 提问**不占额度**：TA 还没真正把话说完
                TurnOutcome.ASKED_USER -> {
                    run.markWaitingForUser(speakerId)
                    host.setStatus(null)
                    return
                }

                TurnOutcome.SPOKEN -> {
                    val canContinue = run.markTurnDone(speakerId)
                    if (!canContinue) {
                        host.setStatus(null)
                        return
                    }
                }

                // 空产出：算一次失败但不对用户报错（中转站偶发空回，重试无意义）
                TurnOutcome.NOTHING -> {
                    val canContinue = run.markTurnFailed(speakerId, speakerName, "没有产出内容")
                    if (!canContinue) {
                        host.setStatus(null)
                        return
                    }
                }
            }
        }
    }

    private enum class TurnOutcome { SPOKEN, ASKED_USER, NOTHING }

    private suspend fun speakOnce(
        speaker: Model,
        speakerName: String,
        config: GroupChatConfig,
        members: List<Uuid>,
        assistant: Assistant,
        conversation: Conversation,
    ): TurnOutcome {
        val messages = transcriptFor(speaker.id, conversation) + turnInstruction(
            speakerId = speaker.id,
            speakerName = speakerName,
            config = config,
            members = members,
            conversation = conversation,
        )
        val produced = host.speak(
            model = speaker,
            assistant = assistant,
            messages = messages,
            tools = if (config.allowTools) host.toolsFor(speaker, assistant, conversation) else emptyList(),
            overwriteMessageId = null,
            onActivity = { chars -> run.touch(chars) },
        ) ?: return TurnOutcome.NOTHING

        return if (groupTurnAskedUser(produced)) TurnOutcome.ASKED_USER else TurnOutcome.SPOKEN
    }

    /**
     * 追加给成员的那条临时指令。
     *
     * 刻意**不入库**：它是"怎么开会"的流程说明，不是讨论内容。存进对话会让后面的成员
     * 收到一堆重复的会议规则，也会污染用户导出的记录（圆桌同一套做法）。
     */
    private fun turnInstruction(
        speakerId: Uuid,
        speakerName: String,
        config: GroupChatConfig,
        members: List<Uuid>,
        conversation: Conversation,
    ): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text(
                buildGroupTurnInstruction(
                    speakerName = speakerName,
                    memberNames = members.map { host.displayName(it) ?: "成员" },
                    speakerMode = config.speakerMode,
                    allowAskUser = config.allowAskUser,
                    isFirstUtterance = conversation.currentMessages.none { it.modelId == speakerId },
                )
            )
        ),
    )

    /** 某个成员眼中的讨论记录（别人的话带署名） */
    private fun transcriptFor(
        speakerId: Uuid,
        conversation: Conversation,
    ): List<UIMessage> = buildGroupTranscript(
        messages = conversation.currentMessages,
        speakingModelId = speakerId,
        nameOf = { id -> host.displayName(id) },
    )

    /**
     * 决定下一个发言的人。
     *
     * MODERATED 模式多花一次调用问主持人；**主持人报错、答非所问、或没点名，
     * 全部退回轮流**。这条不能省：主持人本身也是模型，它坏一次不该让整场讨论停。
     */
    private suspend fun pickSpeaker(
        settings: Settings,
        config: GroupChatConfig,
        members: List<Uuid>,
        conversation: Conversation,
    ): Uuid? {
        val existing = members.toSet()
        val last = run.snapshot().lastSpeakerModelId
        val roundRobin = nextRoundRobinSpeaker(members, existing, last)
        if (config.speakerMode != GroupSpeakerMode.MODERATED) return roundRobin
        val fallback = roundRobin ?: return null

        // 主持人自己从成员里挑，或者用配置指定的那个
        val moderator = config.moderatorModelId?.let { settings.findModelById(it) } ?: return fallback
        val candidates = members.mapNotNull { host.displayName(it) }
        if (candidates.isEmpty()) return fallback

        val reply = try {
            host.speak(
                model = moderator,
                assistant = host.assistantFor(conversation),
                messages = transcriptFor(moderator.id, conversation) + UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text(
                            buildGroupModeratorInstruction(
                                speakerNames = candidates,
                                lastSpeakerName = host.displayName(last),
                            )
                        )
                    ),
                ),
                // 主持人只挑人：不给工具，否则它会去查资料，把「挑人」变成「干活」
                tools = emptyList(),
                overwriteMessageId = null,
                // ⚠️ 必须 persist = false：主持人只输出一个名字，把它当成正式发言
                // 存进用户的讨论记录是数据污染，而且后面每个成员都会在上文里读到它。
                persist = false,
                onActivity = { },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fallback
        }

        val picked = resolveModeratedSpeaker(
            moderatorReply = reply?.toText().orEmpty(),
            speakerNames = candidates,
            fallback = fallback,
            idOf = { name -> members.firstOrNull { host.displayName(it) == name } },
        )
        // 主持人可能点了一个已经被删掉的模型
        return picked?.takeIf { it in existing } ?: fallback
    }

    /**
     * 用户答完提问后，让刚才提问的那个成员接着说。
     *
     * 与主对话唯一必须分岔的地方：见类注释。
     */
    suspend fun resumeAfterUserAnswer() {
        val asked = run.resumeFromQuestion() ?: return
        val settings = host.settings()
        val conversation = host.conversation()
        val config = settings.groupChatConfigs[conversation.id]?.takeIf { it.enabled } ?: run {
            run.markFinished()
            host.setStatus(null)
            return
        }
        val speaker = settings.findModelById(asked) ?: run {
            run.markFinished()
            host.setStatus(null)
            return
        }
        val speakerName = speaker.displayName
        host.setStatus("🗣 $speakerName 正在接着说")

        // 那条带未执行 ask_user 的助手消息属于 TA 自己 → 在上文里保持 ASSISTANT 角色，
        // GenerationLoop 会认出未执行的工具，把用户的答案当工具输出喂回去继续写。
        // v314：从后往前找**那位成员带未执行提问**的那条助手消息 —— 用户中途插话之后
        // 「最后一条」会变成用户消息，只认最后一条会让这次续写另起一条
        // （用户看到同一次发言被拆成两条）。与 findPendingGroupAsk 是同一类修正。
        val continuingId = conversation.currentMessages.asReversed()
            .firstOrNull { message ->
                message.role == MessageRole.ASSISTANT &&
                    message.modelId == asked &&
                    message.getTools().any { tool ->
                        tool.toolName == GROUP_ASK_USER_TOOL_NAME && !tool.isExecuted
                    }
            }
            ?.id

        // v318：把这条提问消息挪到上文末位。
        // 用户「先插话、再点卡片回答」时末条会是那条插话（USER），而生成层只认
        // 「末条消息里有没有待续跑的工具」——认不出就会走全新生成，再按
        // overwriteMessageId 原地覆盖这条提问消息，提问卡片与用户的答案一起被抹掉。
        val transcript = moveGroupMessageToEnd(
            messages = transcriptFor(asked, conversation),
            messageId = continuingId,
        )

        try {
            val produced = host.speak(
                model = speaker,
                assistant = host.assistantFor(conversation),
                messages = transcript,
                tools = if (config.allowTools) {
                    host.toolsFor(speaker, host.assistantFor(conversation), conversation)
                } else {
                    emptyList()
                },
                overwriteMessageId = continuingId,
                onActivity = { chars -> run.touch(chars) },
            )
            host.setStatus(null)
            if (produced == null) {
                run.markTurnFailed(speaker.id, speakerName, "回答之后没有产出内容")
                return
            }
            if (groupTurnAskedUser(produced)) {
                // 答完又问：继续等，仍然不占额度
                run.markWaitingForUser(speaker.id)
                return
            }
            val canContinue = run.markTurnDone(speaker.id)
            if (canContinue) advanceTurns()
        } catch (e: CancellationException) {
            run.markStopped()
            host.setStatus(null)
            throw e
        } catch (e: Exception) {
            run.markTurnFailed(speaker.id, speakerName, e.message ?: e.toString())
            host.reportError(e, speakerName)
            host.setStatus(null)
        }
    }
}
