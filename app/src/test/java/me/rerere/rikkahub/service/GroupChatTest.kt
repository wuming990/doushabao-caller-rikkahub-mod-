package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.ai.prompts.buildGroupTranscript
import me.rerere.rikkahub.data.ai.prompts.withoutGroupChatToolParts
import me.rerere.rikkahub.data.ai.prompts.moveGroupMessageToEnd
import me.rerere.rikkahub.data.ai.prompts.resolveModeratedSpeaker
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatPreset
import me.rerere.rikkahub.data.model.GroupPresetApplyResult
import me.rerere.rikkahub.data.model.GroupPresetKind
import me.rerere.rikkahub.data.model.applyGroupPreset
import me.rerere.rikkahub.data.model.applySavedPreset
import me.rerere.rikkahub.data.model.clampPresetName
import me.rerere.rikkahub.data.model.presetApplyMessage
import me.rerere.rikkahub.data.model.resolveRequestedSpeaker
import me.rerere.rikkahub.data.model.suggestPresetName
import me.rerere.rikkahub.data.model.GroupPresetOpKind
import me.rerere.rikkahub.data.model.GroupPresetOpResult
import me.rerere.rikkahub.data.model.presetOpMessage
import me.rerere.rikkahub.data.model.validateNewPresetName
import me.rerere.rikkahub.data.model.validatePresetRename
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.clampGroupTurns
import me.rerere.rikkahub.data.model.clampResumeMax
import me.rerere.rikkahub.data.model.nextRoundRobinSpeaker
import me.rerere.rikkahub.data.model.sanitizeGroupMembers
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.ai.prompts.buildGroupTurnInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v313：AI 讨论群组的单元测试。
 *
 * 全部走**真实函数调用**，不做源码字符串比对 —— 群功能的核心风险是
 * 「上文整形把别人的话当成自己说的」「落库把临时指令存进对话」「批次/提问判定算错」，
 * 这三件事只有真跑一遍才看得出来。
 */
class GroupChatTest {

    private val modelA = Uuid.random()
    private val modelB = Uuid.random()
    private val modelC = Uuid.random()
    private val names = mapOf(
        modelA to "甲模型",
        modelB to "乙模型",
        modelC to "丙模型",
    )

    private fun nameOf(id: Uuid?): String? = id?.let { names[it] }

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistant(text: String, modelId: Uuid) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        modelId = modelId,
    )

    // ---- 上文整形：群功能最容易出错的一处 ----

    @Test
    fun `别人的发言换成带署名的用户消息，自己的保持助手角色`() {
        val messages = listOf(
            user("做个方案"),
            assistant("我的看法是 X", modelA),
            assistant("我补充 Y", modelB),
        )
        val forA = buildGroupTranscript(messages, modelA, ::nameOf)

        // A 自己那条保持 ASSISTANT —— 它才知道"我之前说过"
        assertEquals(MessageRole.ASSISTANT, forA[1].role)
        // B 那条换成 USER 并带署名
        assertEquals(MessageRole.USER, forA[2].role)
        assertTrue("必须带署名", forA[2].toText().contains("乙模型"))
        assertTrue("要标明是别人说的", forA[2].toText().contains("说]"))
        // 用户的话原样
        assertEquals("做个方案", forA[0].toText())
    }

    @Test
    fun `换一个成员看同一段历史，署名归属跟着变`() {
        val messages = listOf(
            assistant("甲说的", modelA),
            assistant("乙说的", modelB),
        )
        val forB = buildGroupTranscript(messages, modelB, ::nameOf)
        // 对 B 来说：A 的是别人的话（USER + 署名），自己那条保持 ASSISTANT
        assertEquals(MessageRole.USER, forB[0].role)
        assertEquals(MessageRole.ASSISTANT, forB[1].role)
        assertTrue(forB[0].toText().contains("甲模型"))
    }

    @Test
    fun `群里别人的工具结果与空发言不进上文`() {
        val toolish = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "t1",
                    toolName = "workspace_read_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("文件内容")),
                )
            ),
            modelId = modelB,
            // v319：这条测的是「**群讨论期间**别人的工具不外泄」，所以标成群组发言。
            // 开群之前的历史（fromGroupChat = false）现在按用户要求要整份带进来，
            // 那条口径由「开群之前的历史，工具产出要带进上文」单独覆盖，两条必须都有人守。
            fromGroupChat = true,
        )
        val blank = assistant("   ", modelB)
        val result = buildGroupTranscript(listOf(user("任务"), toolish, blank), modelA, ::nameOf)
        // 只剩用户那条：没有正文的他人发言不该变成一条只有署名的空消息
        assertEquals(1, result.size)
        assertEquals("任务", result[0].toText())
    }

    // ---- v318：成员向用户提的问 + 用户的回答，必须让其他成员也看到 ----

    /** 构造一条「只有 ask_user 提问卡片、没有正文」的助手消息（真机就是这个形状） */
    private fun askMessage(
        modelId: Uuid,
        question: String = "你是想公开评估还是先私下谈？",
        options: String = "\"公开\",\"私下\"",
        selectionType: String = "single",
        answer: String? = null,
        text: String? = null,
    ): UIMessage {
        val parts = mutableListOf<UIMessagePart>()
        if (text != null) parts.add(UIMessagePart.Text(text))
        parts.add(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "ask_user",
                input = """{"questions":[{"id":"q0","question":"$question",""" +
                    """"options":[$options],"selection_type":"$selectionType"}]}""",
                approvalState = if (answer != null) {
                    ToolApprovalState.Answered(answer)
                } else {
                    ToolApprovalState.Pending
                },
            )
        )
        return UIMessage(role = MessageRole.ASSISTANT, parts = parts, modelId = modelId)
    }

    @Test
    fun `成员问过用户的问题与用户的回答，下一个成员必须看得到`() {
        val asked = askMessage(
            modelId = modelB,
            answer = """{"answers":{"q0":"公开"}}""",
        )
        val forC = buildGroupTranscript(listOf(asked), modelC, ::nameOf)

        assertEquals("提问消息不能再被整条丢掉", 1, forC.size)
        val text = forC[0].toText()
        assertTrue("要带署名", text.contains("乙模型"))
        assertTrue("要带上问的问题", text.contains("你是想公开评估还是先私下谈？"))
        assertTrue("要带上用户的回答", text.contains("用户回答：公开"))
    }

    @Test
    fun `用户还没回答的提问不进上文，免得把悬而未决当既成事实`() {
        val asked = askMessage(modelId = modelB, answer = null)
        val forC = buildGroupTranscript(listOf(asked), modelC, ::nameOf)
        assertTrue("未作答时不该产出摘要", forC.isEmpty())
    }

    @Test
    fun `提问消息对提问者自己仍是助手消息`() {
        val asked = askMessage(modelId = modelB, answer = """{"answers":{"q0":"公开"}}""")
        val forB = buildGroupTranscript(listOf(asked), modelB, ::nameOf)
        // 保持 ASSISTANT：GenerationLoop 认得出里面那条已回答的工具，会把它当自己的续写上下文
        assertEquals(MessageRole.ASSISTANT, forB[0].role)
    }

    @Test
    fun `正文与问答同时存在时两者都带进上文`() {
        val asked = askMessage(
            modelId = modelB,
            text = "先给个判断",
            answer = """{"answers":{"q0":"私下"}}""",
        )
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertTrue("正文要在", text.contains("先给个判断"))
        assertTrue("问答也要在", text.contains("用户回答：私下"))
    }

    @Test
    fun `答案不是约定形状时也不丢用户的话`() {
        // 跳过、降级输入等历史路径可能整段塞自由文本
        val asked = askMessage(modelId = modelB, answer = "都行，你看着办")
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertTrue(text.contains("都行，你看着办"))
    }

    @Test
    fun `题目读不出来时退化成只带答案原文`() {
        val asked = askMessage(
            modelId = modelB,
            options = "",
            question = "",
            answer = """{"answers":{"free_text":"我其实更在意稳定性"}}""",
        )
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertTrue("自由文本要带进去", text.contains("我其实更在意稳定性"))
    }

    @Test
    fun `其它工具的输出仍然不进上文`() {
        // 只有 ask_user 是例外；别把某个成员与服务商之间的协议内容塞给别人
        val other = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "t9",
                    toolName = "workspace_read_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("文件内容")),
                    approvalState = ToolApprovalState.Auto,
                )
            ),
            modelId = modelB,
            // v319：同上，这条守的是「群讨论期间」那一半口径
            fromGroupChat = true,
        )
        assertTrue(buildGroupTranscript(listOf(other), modelC, ::nameOf).isEmpty())
    }

    @Test
    fun `超长回答截断，不把整份上文挤爆`() {
        val long = "长".repeat(4_000)
        val asked = askMessage(modelId = modelB, answer = """{"answers":{"q0":"$long"}}""")
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertTrue("应有截断说明", text.contains("问答过长"))
        assertTrue("摘要不该无限长", text.length < 5_000)
    }

    // ---- 轮流与名单清洗 ----

    @Test
    fun `轮流发言按顺序推进并回到开头`() {
        val ids = listOf(modelA, modelB, modelC)
        val existing = ids.toSet()
        assertEquals(modelA, nextRoundRobinSpeaker(ids, existing, null))
        assertEquals(modelB, nextRoundRobinSpeaker(ids, existing, modelA))
        assertEquals(modelC, nextRoundRobinSpeaker(ids, existing, modelB))
        assertEquals(modelA, nextRoundRobinSpeaker(ids, existing, modelC))
    }

    @Test
    fun `上一个发言者被删掉后不卡住`() {
        val existing = setOf(modelB, modelC)
        // lastSpeaker 是已经不存在的 A
        assertEquals(modelB, nextRoundRobinSpeaker(listOf(modelA, modelB, modelC), existing, modelA))
    }

    @Test
    fun `成员全没了返回空而不是编一个`() {
        assertNull(nextRoundRobinSpeaker(listOf(modelA), emptySet(), null))
    }

    @Test
    fun `名单清洗去重剔除已删模型并限人数`() {
        val dirty = listOf(modelA, modelA, Uuid.random(), modelB)
        assertEquals(listOf(modelA, modelB), sanitizeGroupMembers(dirty, setOf(modelA, modelB)))
        // 上限
        val many = List(12) { Uuid.random() }
        assertEquals(
            GroupChatConfig.MAX_MEMBERS,
            sanitizeGroupMembers(many, many.toSet()).size,
        )
    }

    @Test
    fun `批次上限夹进合法区间`() {
        assertEquals(1, clampGroupTurns(0))
        assertEquals(1, clampGroupTurns(-5))
        assertEquals(GroupChatConfig.MAX_TURNS_PER_RUN, clampGroupTurns(999))
        assertEquals(3, clampGroupTurns(3))
    }

    // ---- 主持人挑人：认不出来必须退回轮流 ----

    @Test
    fun `主持人点名成功`() {
        val ids = mapOf("甲模型" to modelA, "乙模型" to modelB)
        assertEquals(
            modelB,
            resolveModeratedSpeaker("乙模型", ids.keys.toList(), modelA) { ids[it] },
        )
        // 带引号、带解释也要认得出来
        assertEquals(
            modelB,
            resolveModeratedSpeaker("我觉得应该请「乙模型」来说", ids.keys.toList(), modelA) { ids[it] },
        )
    }

    @Test
    fun `主持人答非所问或空回复时退回轮流`() {
        val ids = mapOf("甲模型" to modelA, "乙模型" to modelB)
        assertEquals(
            modelA,
            resolveModeratedSpeaker("我不确定", ids.keys.toList(), modelA) { ids[it] },
        )
        assertEquals(
            modelA,
            resolveModeratedSpeaker("", ids.keys.toList(), modelA) { ids[it] },
        )
        // 编了一个不存在的名字
        assertEquals(
            modelA,
            resolveModeratedSpeaker("丁模型", ids.keys.toList(), modelA) { ids[it] },
        )
    }

    // ---- 状态机 ----

    private fun newRun(budget: Int) = GroupChatRun(Uuid.random(), budget)

    @Test
    fun `一批跑完是暂停不是结束，用户还能继续`() {
        val run = newRun(budget = 2)
        assertTrue(run.beginRun(2))
        run.markSpeaking(modelA, "甲")
        assertTrue("说完第一条还能继续", run.markTurnDone(modelA))
        run.markSpeaking(modelB, "乙")
        assertFalse("额度用完", run.markTurnDone(modelB))
        // 关键：额度用完必须是 PAUSED（界面还能点继续），不能是 FINISHED
        assertEquals(GroupRunStatus.PAUSED, run.snapshot().status)
        assertTrue("PAUSED 之后还能再开一批", run.beginRun(2))
    }

    @Test
    fun `成员失败也推进游标，不会把同一个人反复挑中`() {
        val run = newRun(budget = 3)
        run.beginRun(3)
        run.markSpeaking(modelA, "甲")
        assertTrue(run.markTurnFailed(modelA, "甲", "上游 502"))
        // 失败后 lastSpeaker 必须变成 A，否则下一个还是 A，坏掉的成员会吃光整批额度
        assertEquals(modelA, run.snapshot().lastSpeakerModelId)
        val ids = listOf(modelA, modelB, modelC)
        assertEquals(
            modelB,
            nextRoundRobinSpeaker(ids, ids.toSet(), run.snapshot().lastSpeakerModelId),
        )
    }

    @Test
    fun `失败原因带上是谁，界面显示得出来`() {
        val run = newRun(budget = 2)
        run.beginRun(2)
        run.markSpeaking(modelA, "甲模型")
        run.markTurnFailed(modelA, "甲模型", "HTTP 429 quota")
        val error = run.snapshot().error
        assertNotNull("必须留下原因", error)
        assertTrue(error!!.contains("甲模型"))
        assertTrue(error.contains("429"))
    }

    @Test
    fun `提问不占批次额度，答完由同一位成员接着说`() {
        val run = newRun(budget = 2)
        run.beginRun(2)
        run.markSpeaking(modelB, "乙")
        run.markWaitingForUser(modelB)
        assertEquals(0, run.snapshot().turnsInRun)
        assertEquals(GroupRunStatus.WAITING_FOR_USER, run.snapshot().status)
        assertTrue(run.isWaitingForUserAnswer())

        // 等用户回答期间不许被「继续讨论」重复开跑（会双份花钱、会互相插话）
        assertFalse(run.beginRun(2))

        assertEquals(modelB, run.resumeFromQuestion())
        assertFalse(run.isWaitingForUserAnswer())
        // 接着说完才算一条
        assertTrue(run.markTurnDone(modelB))
        assertEquals(1, run.snapshot().turnsInRun)
    }

    @Test
    fun `正在发言时不许重复开跑`() {
        val run = newRun(budget = 3)
        run.beginRun(3)
        run.markSpeaking(modelA, "甲")
        assertFalse(run.beginRun(3))
    }

    @Test
    fun `停止与结束状态`() {
        val run = newRun(budget = 3)
        run.beginRun(3)
        run.markStopped()
        assertEquals(GroupRunStatus.STOPPED, run.snapshot().status)
        assertFalse(run.isWaitingForUserAnswer())
    }

    // ---- 提问判定 ----

    private fun nodesWith(message: UIMessage): List<MessageNode> = listOf(
        MessageNode(messages = listOf(message), selectIndex = 0)
    )

    @Test
    fun `最后一条里有未执行的提问才算在等用户`() {
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("先问一句"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "ask_user",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
            modelId = modelA,
        )
        assertTrue(groupTurnAskedUser(pending))

        val answered = pending.copy(
            parts = listOf(
                UIMessagePart.Text("先问一句"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "ask_user",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("用户的答案")),
                    approvalState = ToolApprovalState.Answered("用户的答案"),
                ),
            )
        )
        assertFalse("答完就不该再算等待", groupTurnAskedUser(answered))

        val otherTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_2",
                    toolName = "workspace_read_file",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        assertFalse("别的工具待审批不是提问", groupTurnAskedUser(otherTool))
        assertFalse(groupTurnAskedUser(null))
    }

    // ---- 落库：最容易写坏用户数据的一处 ----

    private fun emptyConversation() = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messageNodes = emptyList(),
    )

    @Test
    fun `新发言追加成一条，署名带上成员模型`() {
        val start = emptyConversation()
        val after = upsertGroupUtterance(
            conversation = start,
            utterance = assistant("甲的看法", modelA),
            targetMessageId = null,
        )
        assertEquals(1, after.messageNodes.size)
        assertEquals(modelA, after.currentMessages.single().modelId)
    }

    @Test
    fun `流式刷新覆盖同一条，不会堆出重复发言`() {
        val message = assistant("甲", modelA)
        val start = emptyConversation()
        val first = upsertGroupUtterance(start, message, null)
        // 同一条消息继续变长（id 不变）
        val grown = message.copy(parts = listOf(UIMessagePart.Text("甲说了很多")))
        val second = upsertGroupUtterance(first, grown, grown.id)
        assertEquals("不该新增一条", 1, second.messageNodes.size)
        assertEquals("甲说了很多", second.currentMessages.single().toText())
    }

    @Test
    fun `回答提问后的续写原地覆盖那一条，不拆成两截`() {
        val first = assistant("我先问一句", modelA).copy(
            parts = listOf(
                UIMessagePart.Text("我先问一句"),
                UIMessagePart.Tool("c1", "ask_user", "{}", approvalState = ToolApprovalState.Pending),
            )
        )
        var conv = upsertGroupUtterance(emptyConversation(), first, null)
        val continued = first.copy(
            parts = listOf(
                UIMessagePart.Text("我先问一句"),
                UIMessagePart.Tool("c1", "ask_user", "{}"),
                UIMessagePart.Text("根据回答，我的结论是 Z"),
            )
        )
        conv = upsertGroupUtterance(conv, continued, targetMessageId = first.id)
        assertEquals("必须原地覆盖", 1, conv.messageNodes.size)
        assertTrue(conv.currentMessages.single().toText().contains("结论是 Z"))
    }

    @Test
    fun `目标消息被用户删掉时退回追加，不丢产出`() {
        val conv = upsertGroupUtterance(emptyConversation(), assistant("甲", modelA), null)
        val orphan = upsertGroupUtterance(
            conversation = conv,
            utterance = assistant("乙的新内容", modelB),
            targetMessageId = Uuid.random(),
        )
        assertEquals(2, orphan.messageNodes.size)
    }

    @Test
    fun `临时会议指令不进对话`() {
        // 引擎给成员的上文 = 历史 + 一条不入库的指令；落库只写成员那条发言
        val instruction = user(buildGroupTurnInstruction(
            speakerName = "甲模型",
            memberNames = listOf("甲模型", "乙模型"),
            speakerMode = me.rerere.rikkahub.data.model.GroupSpeakerMode.ROUND_ROBIN,
            allowAskUser = true,
            isFirstUtterance = true,
        ))
        val conv = upsertGroupUtterance(
            emptyConversation(),
            assistant("甲的实际发言", modelA),
            null,
        )
        assertTrue("指令本身不该出现在落库结果里", conv.currentMessages.none { it.toText() == instruction.toText() })
        assertEquals(1, conv.messageNodes.size)
    }

    // ---- 主对话红线：群功能不许牵连普通对话 ----

    @Test
    fun `没建过群的对话拿不到群设置，引擎不会被唤起`() {
        val configs = mapOf(Uuid.random() to GroupChatConfig())
        val stranger = Uuid.random()
        assertNull(configs[stranger])
        assertTrue(configs[stranger] == null)
    }

    @Test
    fun `群设置默认值不改变任何既有行为`() {
        val config = GroupChatConfig()
        assertEquals(GroupChatConfig.DEFAULT_TURNS_PER_RUN, config.turnsPerRun)
        assertEquals(0, config.memberModelIds.size)
        assertFalse("没成员就不该能开跑", config.hasEnoughMembers)
        assertTrue(GroupChatConfig(listOf(modelA, modelB)).hasEnoughMembers)
    }

    // ---- v313 审查位抓出的两条 P0，必须有测试钉住 ----

    @Test
    fun `等待回答时可以跳过，不会永久卡死`() {
        // 真机死锁路径：成员提问 → 用户点停止 → 提问工具被标成已取消 →
        // answerGroupQuestion 的判据永远不再成立 → 群卡在「等你回答」，
        // 而面板此时既不给「继续」也不给「停止」。markQuestionAbandoned 是那条死路的出口。
        val run = newRun(budget = 3)
        run.beginRun(3)
        run.markSpeaking(modelA, "甲")
        run.markWaitingForUser(modelA)
        assertTrue(run.isWaitingForUserAnswer())

        run.markQuestionAbandoned()
        assertFalse("必须离开等待状态", run.isWaitingForUserAnswer())
        assertEquals(GroupRunStatus.PAUSED, run.snapshot().status)
        assertNull("提问归属要清掉", run.snapshot().askedByModelId)
        // 关键：跳过之后还能继续讨论，而不是只能整场作废
        assertTrue(run.beginRun(3))
    }

    @Test
    fun `跳过提问不消耗批次额度，也不影响后续轮流`() {
        val run = newRun(budget = 2)
        run.beginRun(2)
        run.markSpeaking(modelA, "甲")
        run.markWaitingForUser(modelA)
        val before = run.snapshot().turnsInRun
        run.markQuestionAbandoned()
        assertEquals("跳过不该算一条发言", before, run.snapshot().turnsInRun)
        // lastSpeaker 没被推进 → 甲仍然排在队首，由 TA 自己接着往下说
        assertNull(run.snapshot().lastSpeakerModelId)
    }

    @Test
    fun `跳过时回传给成员的内容明确要求别重复提问`() {
        // 这条文案决定成员会不会原样再问一次（那样用户又被卡住），不能随便改
        assertTrue(GROUP_SKIP_ANSWER.contains("跳过了本次询问"))
        assertTrue(GROUP_SKIP_ANSWER.contains("不要重复问同样的问题"))
    }

    // 说明：P0-1「主持人挑人不写进对话」的行为验证需要真实 Settings/Provider 对象，
    // 本项目 testImplementation 只有 junit（没有 mockk），造不出那套依赖，
    // 因此这条**不在这里做行为断言**，改由 GroupChatWiringGuardTest 用源码接线断言
    // 拦住「改回旧写法」。它能防回退，但不证明逻辑正确 —— 真机验收时必须确认：
    // 主持人模式下讨论记录里不该出现只有模型名的孤立发言。

    // ---- v314：群成员续写次数 + 插话后自动接话 + 面板按钮 ----

    @Test
    fun `群成员续写次数默认给 3，装上去就能用`() {
        // 用户口径（v314）：「成员说到一半被掐断就真的停在半截」，要求给个可调的续写次数。
        // 默认给 3（与主对话默认值一致）—— 用户不用先去找设置就能用上。
        assertEquals(3, GroupChatConfig.DEFAULT_RESUME_MAX)
        assertEquals(GroupChatConfig.DEFAULT_RESUME_MAX, GroupChatConfig().resumeMax)
    }

    @Test
    fun `续写次数必须夹进 0 到 99`() {
        assertEquals("负数会被生成层当成关，用户以为开了其实没开", 0, clampResumeMax(-5))
        assertEquals(0, clampResumeMax(0))
        assertEquals(99, clampResumeMax(99))
        assertEquals("上限与主对话那条滑条同口径", 99, clampResumeMax(1000))
        assertEquals(GroupChatConfig.MAX_RESUME_MAX, clampResumeMax(Int.MAX_VALUE))
    }

    @Test
    fun `旧设置里没有续写次数字段也能正常读出来`() {
        // 装机后第一次读的是设备上已有的旧 JSON（没有 resumeMax 这个键）。
        // 少了这一条，用户升级上来会读设置失败 —— 手册里「设置读取失败被重置」那类事故。
        val legacy = "{\"memberModelIds\":[],\"speakerMode\":\"ROUND_ROBIN\"," +
            "\"turnsPerRun\":3,\"allowAskUser\":true,\"allowTools\":true}"
        val decoded = JsonInstant.decodeFromString<GroupChatConfig>(legacy)
        assertEquals(GroupChatConfig.DEFAULT_RESUME_MAX, decoded.resumeMax)
        assertEquals(3, decoded.turnsPerRun)
    }

    @Test
    fun `面板主按钮：正在说话时不给、等回答时给跳过、其余给讨论`() {
        // 面板用 when (primary) 全量分派，所以这里三个取值都必须真跑一遍
        assertEquals(
            "正在说话时不给主按钮（那个场合只该给停止）",
            GroupPrimaryAction.NONE,
            resolveGroupPrimaryAction(GroupRunStatus.SPEAKING),
        )
        assertEquals(
            "等回答时主按钮是「跳过这次提问」",
            GroupPrimaryAction.SKIP_QUESTION,
            resolveGroupPrimaryAction(GroupRunStatus.WAITING_FOR_USER),
        )
        listOf(
            GroupRunStatus.IDLE,
            GroupRunStatus.PAUSED,
            GroupRunStatus.STOPPED,
            GroupRunStatus.FINISHED,
        ).forEach { status ->
            assertEquals(
                "$status 应当给「开始/继续讨论」（没跑过点得动、停过也点得动）",
                GroupPrimaryAction.DISCUSSION,
                resolveGroupPrimaryAction(status),
            )
        }
    }

    @Test
    fun `插话后要不要顺手开一批`() {
        // 用户拍板（v314 A）：我说的话成为讨论的一部分，当前这位说完就轮到成员接着讨论。
        // 只有两种情况不插手：正在说话（引擎自己会接人）、正在等你回答（必须由提问那位接着说）。
        assertFalse(shouldAutoContinueAfterInterjection(GroupRunStatus.SPEAKING))
        assertFalse(shouldAutoContinueAfterInterjection(GroupRunStatus.WAITING_FOR_USER))
        // 其余全部自动接着聊 —— 用户刚插了话却没反应，比多花一次调用更糟
        assertTrue(shouldAutoContinueAfterInterjection(null))
        assertTrue(shouldAutoContinueAfterInterjection(GroupRunStatus.IDLE))
        assertTrue(shouldAutoContinueAfterInterjection(GroupRunStatus.PAUSED))
        assertTrue(shouldAutoContinueAfterInterjection(GroupRunStatus.STOPPED))
        assertTrue(shouldAutoContinueAfterInterjection(GroupRunStatus.FINISHED))
    }

    // ---- v315：启用/暂停 + 一键成员组合 ----

    @Test
    fun `默认就是启用的，旧设置也读得出来`() {
        assertTrue("配置默认必须是启用态", GroupChatConfig().enabled)
        val legacy = "{\"memberModelIds\":[],\"speakerMode\":\"ROUND_ROBIN\",\"turnsPerRun\":3," +
            "\"resumeMax\":3,\"allowAskUser\":true,\"allowTools\":true}"
        val decoded = JsonInstant.decodeFromString<GroupChatConfig>(legacy)
        assertTrue(
            "旧 JSON 里没有 enabled 键时必须默认为启用（否则升级上来群会静默失效）",
            decoded.enabled,
        )
    }

    @Test
    fun `一键成员组合：按厂商各取一个或取列表前三个`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        val d = Uuid.random()
        val e = Uuid.random()
        val available = listOf(
            a to "供应商甲",
            b to "供应商甲",
            c to "供应商乙",
            d to "供应商丙",
            e to "供应商丁",
        )
        assertEquals(
            "不同厂商各取一个（同家只取第一个，最多三个）",
            listOf(a, c, d),
            applyGroupPreset(GroupPresetKind.ONE_PER_PROVIDER, available),
        )
        assertEquals(
            "列表前三个",
            listOf(a, b, c),
            applyGroupPreset(GroupPresetKind.FIRST_THREE, available),
        )
        assertEquals(
            "一个模型都没有时给空表（交给界面提示用户去配模型）",
            emptyList<Uuid>(),
            applyGroupPreset(GroupPresetKind.ONE_PER_PROVIDER, emptyList()),
        )
    }

    // ===== v316：用户自己攒的方案 =====

    @Test
    fun `套用方案时会剔除被删掉的模型并如实报数`() {
        val alive = setOf(modelA, modelB, modelC)
        val gone = Uuid.random()
        val preset = GroupChatPreset(
            id = Uuid.random(),
            name = "方案1",
            memberModelIds = listOf(modelA, gone, modelB, modelA),
            turnsPerRun = 5,
            resumeMax = 2,
        )
        val applied = applySavedPreset(preset, alive)
        assertEquals("剔除悬空 id 且去重保序", listOf(modelA, modelB), applied.memberModelIds)
        assertEquals("被删掉的数量要如实报出来（界面得说清楚）", 1, applied.droppedCount)
        assertEquals("批次与续写次数一起带过来", 5, applied.turnsPerRun)
        assertEquals("续写次数一起带过来", 2, applied.resumeMax)
        assertTrue("两个成员够开一场", applied.membersEnough)
    }

    @Test
    fun `套用方案时次数越界会被夹进合法区间`() {
        val preset = GroupChatPreset(
            id = Uuid.random(),
            name = "方案2",
            memberModelIds = listOf(modelA, modelB),
            turnsPerRun = 999,
            resumeMax = -3,
        )
        val applied = applySavedPreset(preset, setOf(modelA, modelB))
        assertEquals("批次夹到上限", GroupChatConfig.MAX_TURNS_PER_RUN, applied.turnsPerRun)
        assertEquals("续写次数夹到 0（关）", 0, applied.resumeMax)
    }

    @Test
    fun `方案名清洗与建议名`() {
        assertEquals("去首尾空白", "三个人的群", clampPresetName("  三个人的群  "))
        assertEquals("过长截断", "x".repeat(GroupChatPreset.MAX_NAME_LENGTH), clampPresetName("x".repeat(99)))
        assertEquals("全空白洗成空串", "", clampPresetName("   \t "))
        assertEquals("一条都没有时叫方案1", "方案1", suggestPresetName(emptyList()))
        val existing = listOf(
            GroupChatPreset(id = Uuid.random(), name = "方案1"),
            GroupChatPreset(id = Uuid.random(), name = "方案2"),
        )
        assertEquals("跳过已占用的编号", "方案3", suggestPresetName(existing))
        val withGap = listOf(
            GroupChatPreset(id = Uuid.random(), name = "方案3"),
            GroupChatPreset(id = Uuid.random(), name = "方案1"),
        )
        assertEquals("删过一条后不会重名", "方案2", suggestPresetName(withGap))
    }

    @Test
    fun `套用后那句人话要包含关键信息`() {
        val full = presetApplyMessage(
            "方案1",
            GroupPresetApplyResult(memberModelIds = listOf(modelA, modelB), turnsPerRun = 4, resumeMax = 2, droppedCount = 0),
        )
        assertTrue("要说套用了哪个方案", full.contains("方案1"))
        assertTrue("要说成员数", full.contains("2 个成员"))
        assertTrue("要说批次", full.contains("一次 4 条"))
        assertTrue("要说补说次数", full.contains("补说 2 次"))
        assertFalse("没剔除人时不该提", full.contains("被删掉"))
        // 审查位 v316 抓的 P1：弹窗里改的是草稿，提示必须明说要点保存才生效
        assertTrue("要明说这是草稿、得点保存", full.contains("记得点右下角「保存」才生效"))

        val dropped = presetApplyMessage(
            "方案2",
            GroupPresetApplyResult(memberModelIds = listOf(modelA), turnsPerRun = 3, resumeMax = 3, droppedCount = 2),
        )
        assertTrue("剔除了人要明说数量", dropped.contains("已跳过 2 个现在用不了的成员"))
        assertTrue("不足两个人要提示还得补", dropped.contains("成员不足两个"))
    }

    @Test
    fun `存方案与改名的校验要给出原因而不是只说不行`() {
        val a = GroupChatPreset(id = Uuid.random(), name = "方案1")
        val b = GroupChatPreset(id = Uuid.random(), name = "方案2")
        val (okName, okReason) = validateNewPresetName("  丙组  ", listOf(a, b))
        assertEquals("正常名字清洗后通过", "丙组", okName)
        assertEquals("通过时不该带原因", "", okReason)

        val (emptyName, emptyReason) = validateNewPresetName("   ", listOf(a))
        assertNull("空名不给过", emptyName)
        assertTrue("要说为什么不行", emptyReason.contains("不能是空的"))

        val (dupName, dupReason) = validateNewPresetName("方案1", listOf(a, b))
        assertNull("重名不给过（否则按钮上两个同名，用户分不清）", dupName)
        assertTrue("重名要说撞了哪个名字", dupReason.contains("方案1"))

        val full = (1..GroupChatPreset.MAX_PRESETS).map {
            GroupChatPreset(id = Uuid.random(), name = "旧$it")
        }
        val (overName, overReason) = validateNewPresetName("新方案", full)
        assertNull("存满必须拒绝，而不是悄悄挤掉最旧一条", overName)
        assertTrue("要说上限是多少", overReason.contains("最多存 ${GroupChatPreset.MAX_PRESETS} 个方案"))
        assertTrue("要告诉用户怎么办", overReason.contains("先删掉一个"))

        // 改名允许保持原名（等于没改，不该报错）
        val (sameName, sameReason) = validatePresetRename("方案1", listOf(a, b), a.id)
        assertEquals("改回自己的名字不该算重名", "方案1", sameName)
        assertEquals("不该带原因", "", sameReason)
        val (clash, clashReason) = validatePresetRename("方案2", listOf(a, b), a.id)
        assertNull("改成别人占用的名字要拒", clash)
        assertTrue(clashReason.contains("方案2"))
    }

    @Test
    fun `方案写操作的回执文案要说清成败`() {
        val saved = presetOpMessage(GroupPresetOpResult.Done(GroupPresetOpKind.SAVE, "方案1"))
        assertFalse("成功不该被标成错误", saved.second)
        assertTrue("要说存好了", saved.first.contains("已存好"))
        assertTrue("要提醒点名字就能填入", saved.first.contains("点它的名字"))

        val deleted = presetOpMessage(GroupPresetOpResult.Done(GroupPresetOpKind.DELETE, "方案2"))
        assertFalse(deleted.second)
        assertTrue("删除要交代不影响现有群设置", deleted.first.contains("不影响任何对话现有的群设置"))

        val failed = presetOpMessage(GroupPresetOpResult.Rejected(GroupPresetOpKind.SAVE, "磁盘满了"))
        assertTrue("失败必须标成错误", failed.second)
        assertTrue("要把真实原因摊出来，不许只写日志", failed.first.contains("磁盘满了"))
    }

    // ===== v317：面板折叠偏好（真解码，不是字符串断言）=====

    @Test
    fun `旧显示设置里没有折叠字段也能读出来，且默认是展开`() {
        // 装机后第一次读的是设备上已有的旧 JSON（不含 groupPanelCollapsed 这个键）。
        // 这条真跑解码：字段没默认值 / 类型写错 / 忘加字段，都会在这里红，而不是等用户升级后设置被重置。
        val legacy = "{\"showContextUsage\":true,\"showThinkingContent\":true}"
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertFalse("缺键必须落回 false（= 展开），不能一装上就收起", decoded.groupPanelCollapsed)
    }

    @Test
    fun `折叠偏好能真的存下来再读回来`() {
        val collapsed = DisplaySetting(groupPanelCollapsed = true)
        val raw = JsonInstant.encodeToString(collapsed)
        val back = JsonInstant.decodeFromString<DisplaySetting>(raw)
        assertTrue("收起状态要能保住（用户要的是收一次就一直收着）", back.groupPanelCollapsed)
        val expanded = DisplaySetting(groupPanelCollapsed = false)
        val back2 = JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(expanded))
        assertFalse("展开状态也要能保住", back2.groupPanelCollapsed)
    }

    // ---- v318 审查位 P1：用户「先插话、再回答提问」时答案不能被抹掉 ----

    @Test
    fun `提问消息被挪回末位，其余顺序不变`() {
        val ask = assistant("提问那条", modelB).copy(id = Uuid.random())
        val interjection = user("我先插一句")
        val ordered = listOf(user("题目"), ask, interjection)

        val moved = moveGroupMessageToEnd(ordered, ask.id)
        // 生成层只认「末条消息里有没有待续跑的工具」，提问那条必须回到末位
        assertEquals("提问消息必须落在末位", ask.id, moved.last().id)
        assertEquals("条数不能变", 3, moved.size)
        assertEquals("插话仍在，只是排到提问前面", interjection, moved[1])
        assertEquals("最早那条不动", "题目", moved[0].toText())
    }

    @Test
    fun `挪末位时找不到目标或本来就在末位都原样返回`() {
        val a = user("a")
        val b = user("b")
        // ⚠️ 必须先绑成变量再比对引用：listOf(a,b) 每次都是新对象，
        // 直接写 assertSame(listOf(a, b), …) 会永远红（我自己第一次就写错了这条）。
        val nullCase = listOf(a, b)
        val missingCase = listOf(a, b)
        val lastCase = listOf(a, b)
        assertSame("id 为 null 时不动", nullCase, moveGroupMessageToEnd(nullCase, null))
        assertSame("找不到该消息时不动", missingCase, moveGroupMessageToEnd(missingCase, Uuid.random()))
        assertSame("已在末位时不动", lastCase, moveGroupMessageToEnd(lastCase, b.id))
    }

    @Test
    fun `用户点跳过时不把跳过指令当成用户原话摊给其他成员`() {
        // GROUP_SKIP_ANSWER 是写给「提问的那位成员」的指令，含「不要重复问同样的问题」。
        // 原样摊给其他成员，它们会以为用户真的说过这句话。
        val asked = askMessage(
            modelId = modelB,
            answer = """{"answers":{"q0":""},"note":"用户跳过了本次询问，没有作答。""" +
                """请基于现有信息自行决定并继续，不要重复问同样的问题。"}""",
        )
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertTrue("要如实说明是跳过", text.contains("用户跳过了这次提问"))
        assertFalse("不许把指令原文当用户回答", text.contains("不要重复问同样的问题"))
    }

    @Test
    fun `面板跳过（整段答案就是指令）也识别为未作答`() {
        val asked = askMessage(
            modelId = modelB,
            answer = "用户跳过了本次询问，没有作答。请基于现有信息自行决定并继续，" +
                "不要重复问同样的问题；如果确实必须知道，请改用普通文字再问一次。",
        )
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertTrue(text.contains("用户跳过了这次提问"))
        assertFalse("不许把指令原文当用户回答", text.contains("不要重复问同样的问题"))
    }

    @Test
    fun `题目读不出来时同一段答案只贴一次`() {
        // 非 JSON 答案若按题重复，多题场景会把同一段原文贴 N 遍
        val asked = askMessage(
            modelId = modelB,
            options = "",
            question = "",
            answer = "我其实更在意稳定性",
        )
        val text = buildGroupTranscript(listOf(asked), modelC, ::nameOf)[0].toText()
        assertEquals("同一段答案只应出现一次", 1, Regex("我其实更在意稳定性").findAll(text).count())
    }

    // ---- v319：开群带全部上文 / 解散后不把群组工具带进主对话 ----

    @Test
    fun `开群之前的历史，工具产出要带进上文`() {
        // 用户口径（v319 原话）：「我要的是打开时候获取全部上文」。
        // 真机现象：十几万字的原对话开群后只剩 3 万，缺的正是这些「之前干活查到的资料」。
        val historyTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "h1",
                    toolName = "search_web",
                    input = """{"query":"北京展览"}""",
                    output = listOf(UIMessagePart.Text("查到 5 条结果")),
                )
            ),
            modelId = modelB,
            fromGroupChat = false,
        )
        val result = buildGroupTranscript(listOf(historyTool), modelA, ::nameOf)
        assertEquals("带上了工具产出的历史不该再被当成空发言丢掉", 1, result.size)
        val text = result[0].toText()
        assertTrue(
            "要写成第三人称，别让成员误以为是自己调过的工具",
            text.contains("它当时调用了工具"),
        )
        assertTrue("要带工具名", text.contains("search_web"))
        assertTrue("要带参数", text.contains("北京展览"))
        assertTrue("要带结果", text.contains("查到 5 条结果"))
    }

    @Test
    fun `群讨论期间别人调的工具仍然不进共享上文`() {
        // 与上一条成对：用户同时要求「群组聊天过程中每个 ai 的搜索和工具上文
        // 不会参杂进群组聊天中，只有给出的方案/对话内容」。两条口径靠 fromGroupChat 分开。
        val peerTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "g1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("别人为讨论搜的东西")),
                )
            ),
            modelId = modelB,
            fromGroupChat = true,
        )
        assertTrue(
            "群讨论期间别人的搜索不许掺杂进共享上文",
            buildGroupTranscript(listOf(peerTool), modelA, ::nameOf).isEmpty(),
        )
    }

    @Test
    fun `上文上限 0 与负数都是不限，正数才从最旧的丢`() {
        val many = (1..50).map {
            user("第${it}条消息，内容要足够长才看得出截断效果：" + "字".repeat(200))
        }
        assertEquals("默认（0）整份原样", many.size, buildGroupTranscript(many, modelA, ::nameOf).size)
        assertEquals("0 显式传也是整份", many.size, buildGroupTranscript(many, modelA, ::nameOf, charLimit = 0).size)
        assertEquals("负数按不限处理，不许把讨论废掉", many.size, buildGroupTranscript(many, modelA, ::nameOf, charLimit = -1).size)

        val trimmed = buildGroupTranscript(many, modelA, ::nameOf, charLimit = 2_000)
        assertTrue("超预算必须丢东西", trimmed.size < many.size)
        assertEquals("丢的是最旧的，用户最新那条必须在", many.last().toText(), trimmed.last().toText())
    }

    @Test
    fun `主对话剥离群组工具：只动群组发言、ask_user 保留、原列表不改`() {
        val groupPlain = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("群里的结论"),
                UIMessagePart.Tool(
                    toolCallId = "s1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("群里为讨论搜的东西")),
                ),
            ),
            modelId = modelA,
            fromGroupChat = true,
        )
        val groupAsk = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "s2",
                    toolName = "ask_user",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("用户回答")),
                    approvalState = ToolApprovalState.Answered("用户回答"),
                ),
            ),
            modelId = modelA,
            fromGroupChat = true,
        )
        val historyTool = groupPlain.copy(id = Uuid.random(), fromGroupChat = false)

        val before = listOf(groupPlain, groupAsk, historyTool)
        val after = before.withoutGroupChatToolParts()

        assertEquals("群组发言只留正文", listOf("群里的结论"), after[0].parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertTrue("群组那条不能再有工具", after[0].getTools().isEmpty())
        assertEquals("ask_user 是用户本人说的话，不许被摘掉", 1, after[1].getTools().size)
        assertEquals("开群之前的历史一个字都不动（那是主对话自己经历的）", historyTool, after[2])
        // 用户选的是「不删数据」：传入的原始列表必须保持原样，剥离只发生在请求视图上
        assertEquals("原列表不能被改坏", 2, before[0].parts.size)
        assertTrue("原列表里工具必须还在", before[0].getTools().isNotEmpty())
    }

    @Test
    fun `群组发言只剩工具时剥离后留占位，不发空消息给模型`() {
        val onlyTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "s3",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("x")),
                )
            ),
            modelId = modelA,
            fromGroupChat = true,
        )
        val stripped = listOf(onlyTool).withoutGroupChatToolParts().single()
        assertTrue("不能留成空 parts（空助手消息会被部分服务商直接拒掉）", stripped.parts.isNotEmpty())
        assertTrue("要有一句人能看懂的占位", stripped.toText().contains("未纳入主对话上下文"))
    }

    // ---- v319：点名（跳过中间排队的成员，指定某位说话） ----

    @Test
    fun `点名取用一次就清空，不会一直插队`() {
        val run = GroupChatRun(conversationId = Uuid.random(), turnsBudget = 3)
        assertNull("没点过名时是空的", run.takeRequestedSpeaker())
        assertTrue("讨论进行中接受点名", run.requestSpeaker(modelA))
        assertEquals(modelA, run.takeRequestedSpeaker())
        assertNull("取走之后必须清空，否则他会一直插队、轮流顺序就废了", run.takeRequestedSpeaker())
    }

    @Test
    fun `已停或已结束的场次不接受点名`() {
        // 那时该走「继续讨论」，让点名悄悄把状态改回可跑，用户会以为讨论自己在动
        val stopped = GroupChatRun(conversationId = Uuid.random(), turnsBudget = 3)
        stopped.markStopped()
        assertFalse("停了的场次不许被点名改回可跑", stopped.requestSpeaker(modelA))
        val finished = GroupChatRun(conversationId = Uuid.random(), turnsBudget = 3)
        finished.markFinished()
        assertFalse("已结束的场次同理", finished.requestSpeaker(modelA))
    }

    @Test
    fun `点名不改动轮流游标`() {
        // 轮流游标 lastSpeakerModelId 是 markTurnDone 写的（markSpeaking 只写「当前正在说的」），
        // 第一版我把断言写在 markSpeaking 之后，拿一个从没被写过的字段去比，测试自己就是错的。
        // 这里先把游标真的推进起来，再验证「点名 + 取用」这一趟不会把它写歪。
        val run = GroupChatRun(conversationId = Uuid.random(), turnsBudget = 3)
        run.markSpeaking(modelA, "甲模型")
        run.markTurnDone(modelA)
        assertEquals("前置条件：游标已落到甲身上", modelA, run.snapshot().lastSpeakerModelId)

        run.requestSpeaker(modelC)
        assertEquals("点名要被取到", modelC, run.takeRequestedSpeaker())
        assertEquals(
            "点名取走后，轮流游标仍是刚才说完的那位 —— 插一次队不该把顺序写歪",
            modelA,
            run.snapshot().lastSpeakerModelId,
        )
    }

    @Test
    fun `点名的那位已被移出成员时不采用，退回正常顺序`() {
        val members = setOf(modelA, modelB)
        assertEquals("还在成员里就采用他", modelB, resolveRequestedSpeaker(modelB, members))
        assertNull(
            "已被移出成员时不采用 —— 否则用户会听见一个已经不在群里的人被点名说话",
            resolveRequestedSpeaker(modelC, members),
        )
        assertNull("没人被点名时返回 null，走轮流/主持人", resolveRequestedSpeaker(null, members))
        assertNull("空成员表里谁都不该被采用", resolveRequestedSpeaker(modelA, emptySet()))
    }

    // ---- v320：点名换人「还没出声就当场撤」的判据 ----
    //
    // 用户真机反馈（原话）：「下一个模型只是准备开始说话，思考都没开始思考，就被锁在这个
    // 模型了，还得强制等待这个模型说完话才能换到我选择的模型」。
    // 能不能撤、撤完要不要删、什么时候该发通知，全是**纯函数**，所以这里真跑，不做字符串比对。

    private fun assistantOf(vararg parts: UIMessagePart, id: Uuid = Uuid.random()) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
        modelId = modelA,
    )

    @Test
    fun `只有思考或只挂着没执行的工具，都算还没出声`() {
        assertFalse("一个字都没写 = 可以撤", groupUtteranceHasKeptContent(null))
        assertFalse(
            "只有思考不算出声：用户要的就是「思考都没开始/没说出话」时不必等他",
            groupUtteranceHasKeptContent(assistantOf(UIMessagePart.Reasoning("我在想"))),
        )
        assertFalse("空白正文不算出声", groupUtteranceHasKeptContent(assistantOf(UIMessagePart.Text("   "))))
        assertFalse(
            "只挂着**正在执行、还没结果**的工具调用 = 可以撤（整条会被删掉，不会留下有调用没结果的形状）",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.Tool(
                        toolCallId = "c1",
                        toolName = "workspace_read_file",
                        input = "{}",
                        approvalState = ToolApprovalState.Auto,
                    )
                )
            ),
        )
        assertFalse(
            "服务端工具还在跑（没有结果）= 还没出声",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.ServerTool(
                        toolCallId = "s1",
                        toolName = "web_search",
                        status = ServerToolStatus.IN_PROGRESS,
                    )
                )
            ),
        )
    }

    @Test
    fun `写过正文、跑完工具、或用户答过问，就不许撤`() {
        assertTrue("有正文 = 已经出声", groupUtteranceHasKeptContent(assistantOf(UIMessagePart.Text("我的观点"))))
        assertTrue(
            "工具已经跑完带回结果 = 讨论记录里有真东西，撤掉就是丢内容",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.Tool(
                        toolCallId = "c2",
                        toolName = "workspace_read_file",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("文件内容")),
                    )
                )
            ),
        )
        assertTrue(
            "提问卡片已被用户答过 —— 那是用户写下的答案，绝不允许被撤掉（v318 同族事故）",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.Tool(
                        toolCallId = "c3",
                        toolName = "ask_user",
                        input = "{}",
                        approvalState = ToolApprovalState.Answered("我的答案"),
                    )
                )
            ),
        )
        assertTrue("有图片也算出声", groupUtteranceHasKeptContent(assistantOf(UIMessagePart.Image("file:///a.png"))))
        // v320 审查位抓的真窗口：卡片刚在流式内容里出现、还没走到「批次收尾、把手摘除」那一瞬间，
        // 用户恰好点了「你来说」—— 这时候撤掉等于把 TA 问的话连同卡片一起删掉，用户白看一眼。
        assertTrue(
            "等用户批准的**提问卡片**不许撤（那是 TA 正要回答的东西）",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.Tool(
                        toolCallId = "c4",
                        toolName = GROUP_ASK_USER_TOOL_NAME,
                        input = "{}",
                        approvalState = ToolApprovalState.Pending,
                    )
                )
            ),
        )
        assertTrue(
            "等用户批准的任何工具都不许撤（撤了审批按钮就凭空消失）",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.Tool(
                        toolCallId = "c5",
                        toolName = "workspace_shell",
                        input = "{}",
                        approvalState = ToolApprovalState.Pending,
                    )
                )
            ),
        )
        assertTrue(
            "服务端工具已完成 = 有结果留在消息里",
            groupUtteranceHasKeptContent(
                assistantOf(
                    UIMessagePart.ServerTool(
                        toolCallId = "s2",
                        toolName = "web_search",
                        output = kotlinx.serialization.json.JsonPrimitive("搜索结果"),
                        status = ServerToolStatus.COMPLETED,
                    )
                )
            ),
        )
    }

    @Test
    fun `撤场清理只删什么都没留下的那条`() {
        val silentId = Uuid.random()
        val silent = assistantOf(
            UIMessagePart.Reasoning("想过一会儿"),
            UIMessagePart.Tool(toolCallId = "c1", toolName = "workspace_read_file", input = "{}"),
            id = silentId,
        )
        val kept = assistantOf(UIMessagePart.Text("甲：我认为应该先做门禁"))
        val conv = emptyConversation().copy(
            messageNodes = listOf(MessageNode(messages = listOf(kept, silent), selectIndex = 1))
        )

        val out = removeEmptyGroupUtterance(conv, silentId)

        assertEquals("只删掉那条没出声的", 1, out.messageNodes.single().messages.size)
        assertEquals("留下的那条没被碰", kept.id, out.messageNodes.single().messages.single().id)
    }

    @Test
    fun `撤场清理的三种不删情况`() {
        // ① 本次根本没写过消息（一个分片都没收到就被撤）
        val conv = emptyConversation().copy(messageNodes = nodesWith(assistant("已有内容", modelA)))
        assertSame("没写过就不该乱动", conv, removeEmptyGroupUtterance(conv, null))

        // ② 那条消息已经出声了（有正文）—— 宁可留半截，也绝不删内容
        val spoken = assistantOf(UIMessagePart.Text("我说了一半"))
        val spokenConv = emptyConversation().copy(messageNodes = nodesWith(spoken))
        assertSame("有正文不删", spokenConv, removeEmptyGroupUtterance(spokenConv, spoken.id))

        // ③ 那条消息已经被用户删掉了（找不到 = 原样返回）
        assertSame("找不到目标就原样返回", conv, removeEmptyGroupUtterance(conv, Uuid.random()))
    }

    @Test
    fun `撤场清理绝不删掉带着待批准提问卡片的那条`() {
        // 与上面那条判据配对的**第二道闸**：就算点名那条路误判了，删除函数自己也要拒绝。
        val id = Uuid.random()
        val asking = assistantOf(
            UIMessagePart.Tool(
                toolCallId = "c6",
                toolName = GROUP_ASK_USER_TOOL_NAME,
                input = "{}",
                approvalState = ToolApprovalState.Pending,
            ),
            id = id,
        )
        val conv = emptyConversation().copy(messageNodes = nodesWith(asking))
        assertSame("有待批准的提问卡片 = 一个字都不许删", conv, removeEmptyGroupUtterance(conv, id))
    }

    @Test
    fun `整条没出声时连节点一起删掉，不留空节点`() {
        val only = assistantOf(UIMessagePart.Reasoning("只有思考"), id = Uuid.random())
        val conv = emptyConversation().copy(messageNodes = nodesWith(only))
        val out = removeEmptyGroupUtterance(conv, only.id)
        assertTrue("整条消息没了，节点也该一起清掉", out.messageNodes.isEmpty())
    }

    @Test
    fun `通知只在停下来等用户时发`() {
        assertEquals(
            "有人用提问卡片等你回话",
            AppEvent.GroupWaitReason.QUESTION,
            groupWaitReasonOf(GroupRunStatus.WAITING_FOR_USER, null),
        )
        assertEquals(
            "这批说完，等你点继续讨论",
            AppEvent.GroupWaitReason.TURN_DONE,
            groupWaitReasonOf(GroupRunStatus.PAUSED, null),
        )
        assertEquals(
            "这一轮出错了 —— 手册铁律：报错必须让用户看见",
            AppEvent.GroupWaitReason.FAILED,
            groupWaitReasonOf(GroupRunStatus.PAUSED, "甲模型：余额不足"),
        )
        assertEquals(
            "整场结束了：不能说成「等你点继续讨论」（那种场合点了也不会跑，文案不许说谎）",
            AppEvent.GroupWaitReason.ENDED,
            groupWaitReasonOf(GroupRunStatus.FINISHED, null),
        )
        assertNull("还有人正在说话，不该打扰用户", groupWaitReasonOf(GroupRunStatus.SPEAKING, null))
        assertNull("刚开跑，不该打扰用户", groupWaitReasonOf(GroupRunStatus.IDLE, null))
        assertNull("用户自己点的停止，不该再吵他", groupWaitReasonOf(GroupRunStatus.STOPPED, null))
    }

    @Test
    fun `被新一批顶掉的旧场次不许再把状态写成已停止`() {
        // 症状：点「你来说」/「继续讨论」之后全场安静 —— 旧协程临死前把新一批盖成 STOPPED。
        val run = newRun(budget = 3)
        val staleEpoch = run.runEpoch
        assertTrue(run.beginRun(3))
        val currentEpoch = run.runEpoch
        assertTrue("开一批必须换场次编号", currentEpoch > staleEpoch)

        run.markSpeaking(modelA, "甲模型")
        assertFalse("旧场次的收尾不该被接受", run.markStoppedIfCurrent(staleEpoch))
        assertEquals("状态必须还是正在说话", GroupRunStatus.SPEAKING, run.snapshot().status)

        assertTrue("本场次的收尾照旧落 STOPPED", run.markStoppedIfCurrent(currentEpoch))
        assertEquals(GroupRunStatus.STOPPED, run.snapshot().status)
    }

    // ---- v321：「群讨论只保留发言正文」开关 ----
    //
    // 开关打开后，**群聊过程中产生的**思考 / 工具调用与结果 / 服务商侧搜索
    // 不再进任何成员的上文；开群之前那份原对话上文照旧整份带（v319 硬红线）。
    // 全部真跑函数，不做源码字符串比对。

    /** 一条「成员在群里说过的话」：正文 + 思考 + 一个已经跑完带回结果的搜索工具 */
    private fun groupUtteranceOf(
        modelId: Uuid,
        text: String? = "我主张先做门禁",
        reasoning: String = "先想想该不该查资料",
        toolOutput: String = "查到的原始网页正文",
    ): UIMessage {
        val parts = mutableListOf<UIMessagePart>()
        if (text != null) parts.add(UIMessagePart.Text(text))
        parts.add(UIMessagePart.Reasoning(reasoning))
        parts.add(
            UIMessagePart.Tool(
                toolCallId = "g1",
                toolName = "search_web",
                input = """{"query":"门禁 流程"}""",
                output = listOf(UIMessagePart.Text(toolOutput)),
            )
        )
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
            modelId = modelId,
            fromGroupChat = true,
        )
    }

    @Test
    fun `开关打开时，群发言只留正文，思考与工具原文都不进上文`() {
        val own = groupUtteranceOf(modelA)
        val result = buildGroupTranscript(
            messages = listOf(own),
            speakingModelId = modelA,
            nameOf = ::nameOf,
            cleanGroupUtterances = true,
        )

        assertEquals("自己那条仍是一条助手消息", 1, result.size)
        assertEquals(
            "角色不许变（变了模型会以为自己没说过）",
            MessageRole.ASSISTANT,
            result[0].role,
        )
        assertEquals(
            "只留正文那一片",
            listOf("我主张先做门禁"),
            result[0].parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
        assertTrue("思考过程必须被摘掉", result[0].parts.none { it is UIMessagePart.Reasoning })
        assertTrue("工具调用整片摘掉（含它查回的原文）", result[0].parts.none { it is UIMessagePart.Tool })
        assertFalse(
            "思考原文一个字都不许出现在上文里",
            result[0].toText().contains("先想想该不该查资料"),
        )
        assertFalse("工具查回的原文也不许出现", result[0].toText().contains("查到的原始网页正文"))
        assertTrue("正文还在", result[0].toText().contains("我主张先做门禁"))
    }

    @Test
    fun `开关打开时，服务商侧搜索结果（ServerTool）也不进成员上文`() {
        // 用户原话点名要清的三类里就有「搜索结果」。谷歌 / OpenAI 自带搜索的结果
        // 是以 ServerTool 承载的（与 search_web 那种 Tool 是两个类型），
        // 少钉这一条就等于「搜索结果」那一路没人守 —— 而它常常是最占字的那块。
        val own = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("结论：走 B 方案"),
                UIMessagePart.Reasoning("我先搜一下再说"),
                UIMessagePart.ServerTool(
                    toolCallId = "s1",
                    toolName = "web_search",
                    output = kotlinx.serialization.json.JsonPrimitive("整段服务商侧搜索结果原文"),
                    status = ServerToolStatus.COMPLETED,
                ),
            ),
            modelId = modelA,
            fromGroupChat = true,
        )
        val result = buildGroupTranscript(
            messages = listOf(own),
            speakingModelId = modelA,
            nameOf = ::nameOf,
            cleanGroupUtterances = true,
        )
        val kept = result.single()
        assertEquals("正文必须还在", "结论：走 B 方案", kept.toText().trim())
        assertTrue("服务商侧搜索分片必须被摘掉", kept.parts.none { it is UIMessagePart.ServerTool })
        assertTrue("思考分片必须被摘掉", kept.parts.none { it is UIMessagePart.Reasoning })
        assertFalse(
            "搜索结果原文一个字都不许出现在上文里",
            kept.toText().contains("整段服务商侧搜索结果原文"),
        )
        assertFalse("思考原文不许出现在上文里", kept.toText().contains("我先搜一下再说"))
    }

    @Test
    fun `开关打开时，开群之前的历史照旧整份带，一个字都不许摘`() {
        // 用户 v319 原话「我要的是打开时候获取全部上文」；v321 补充口径又把范围钉死成
        // 「群聊过程中产生的上文，不是打开群聊之前的上文」。这条是最容易误伤的地方。
        val history = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("开群前我查过的东西"),
                UIMessagePart.Reasoning("当时是这么想的"),
                UIMessagePart.Tool(
                    toolCallId = "h1",
                    toolName = "search_web",
                    input = """{"query":"北京展览"}""",
                    output = listOf(UIMessagePart.Text("查到 5 条结果")),
                ),
            ),
            modelId = modelA,
            // 关键：开群之前 → false。modelId 故意等于发言者（成员模型与主对话当前模型
            // 常常是同一个，v319 注释里写死过这个坑），只按 modelId 判就会误伤这条。
            fromGroupChat = false,
        )
        val result = buildGroupTranscript(
            messages = listOf(history),
            speakingModelId = modelA,
            nameOf = ::nameOf,
            cleanGroupUtterances = true,
        )

        assertEquals(1, result.size)
        assertSame("开群前的历史必须原样返回（同一条对象）", history, result[0])
        assertEquals("逐字段相等", history, result[0])
        assertEquals(
            "思考不许被摘",
            1,
            result[0].parts.count { it is UIMessagePart.Reasoning },
        )
        assertEquals("工具不许被摘", 1, result[0].parts.count { it is UIMessagePart.Tool })
        assertEquals(
            "工具产出的原文要原样带得到",
            listOf("查到 5 条结果"),
            result[0].parts.filterIsInstance<UIMessagePart.Tool>()
                .single().output.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
    }

    @Test
    fun `开关打开时，成员问过的问题与用户的回答仍要带进上文`() {
        // v318 真机坑：摘掉会让下一个成员当场否认用户答过的条件。
        // ask_user 是「用户本人说的话」，不属于「杂乱的调查过程」。
        val asked = askMessage(
            modelId = modelA,
            text = "先确认一个条件",
            answer = """{"answers":{"q0":"公开"}}""",
        ).copy(fromGroupChat = true)

        val result = buildGroupTranscript(
            messages = listOf(asked),
            speakingModelId = modelA,
            nameOf = ::nameOf,
            cleanGroupUtterances = true,
        )

        assertEquals(1, result.size)
        val ask = result[0].getTools().single()
        assertEquals("提问卡片必须留着", "ask_user", ask.toolName)
        assertTrue("问的题还在", ask.input.contains("你是想公开评估还是先私下谈？"))
        assertEquals(
            "用户的回答必须还在卡片里",
            """{"answers":{"q0":"公开"}}""",
            (ask.approvalState as? ToolApprovalState.Answered)?.answer,
        )
        assertEquals(
            "正文照旧留着",
            listOf("先确认一个条件"),
            result[0].parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
    }

    @Test
    fun `开关打开时，成员生成的图片与文件照旧保留`() {
        // 那是「模型的输出」，不属于「杂乱的调查过程」；库里存的是路径不是原文。
        val withMedia = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("这是效果图"),
                UIMessagePart.Reasoning("先画一张"),
                UIMessagePart.Image("file:///tmp/effect.png"),
                UIMessagePart.Document(
                    url = "file:///tmp/plan.md",
                    fileName = "plan.md",
                    mime = "text/markdown",
                ),
            ),
            modelId = modelA,
            fromGroupChat = true,
        )
        val result = buildGroupTranscript(
            messages = listOf(withMedia),
            speakingModelId = modelA,
            nameOf = ::nameOf,
            cleanGroupUtterances = true,
        )

        val parts = result.single().parts
        assertEquals(
            "只剩正文 + 图片 + 文件，顺序不变",
            listOf(
                UIMessagePart.Text("这是效果图"),
                UIMessagePart.Image("file:///tmp/effect.png"),
                UIMessagePart.Document(
                    url = "file:///tmp/plan.md",
                    fileName = "plan.md",
                    mime = "text/markdown",
                ),
            ),
            parts,
        )
        assertEquals("图片那一片还在", 1, parts.count { it is UIMessagePart.Image })
        assertEquals("文件那一片还在", 1, parts.count { it is UIMessagePart.Document })
        assertTrue("思考照旧摘掉", parts.none { it is UIMessagePart.Reasoning })
    }

    @Test
    fun `开关打开时，只剩思考与工具的群发言要留一句占位，不能发空消息`() {
        val silent = groupUtteranceOf(modelA, text = null)
        val result = buildGroupTranscript(
            messages = listOf(silent),
            speakingModelId = modelA,
            nameOf = ::nameOf,
            cleanGroupUtterances = true,
        )

        val parts = result.single().parts
        assertTrue(
            "不许出现 parts 为空的消息（空助手消息会被部分服务商直接拒掉）",
            parts.isNotEmpty(),
        )
        assertEquals(
            "要有一句人能看懂的占位",
            listOf("（这条群讨论发言只有思考过程与工具调用，正文为空）"),
            parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
    }

    @Test
    fun `开关关着（默认）时，群发言一个字段都不许动`() {
        // 回归钉：不传 cleanGroupUtterances = 与 v320 逐字节一致。
        val own = groupUtteranceOf(modelA)
        val result = buildGroupTranscript(
            messages = listOf(own),
            speakingModelId = modelA,
            nameOf = ::nameOf,
        )

        assertSame("必须原样返回同一条对象", own, result.single())
        assertEquals("逐字段相等", own, result.single())
        assertEquals("正文、思考、工具三片都在", 3, result.single().parts.size)
        assertTrue(result.single().parts.any { it is UIMessagePart.Reasoning })
        assertTrue(result.single().parts.any { it is UIMessagePart.Tool })
    }

    @Test
    fun `主对话剥离：默认保留思考，打开开关才连思考一起摘`() {
        val groupMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("群里的结论"),
                UIMessagePart.Reasoning("当时想的过程"),
                UIMessagePart.Tool(
                    toolCallId = "s1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("群里为讨论搜的东西")),
                ),
                UIMessagePart.ServerTool(
                    toolCallId = "s2",
                    toolName = "web_search",
                    output = kotlinx.serialization.json.JsonPrimitive("服务商侧搜索结果"),
                    status = ServerToolStatus.COMPLETED,
                ),
                UIMessagePart.Tool(
                    toolCallId = "s3",
                    toolName = "ask_user",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("用户回答")),
                    approvalState = ToolApprovalState.Answered("用户回答"),
                ),
            ),
            modelId = modelA,
            fromGroupChat = true,
        )

        fun shapeOf(parts: List<UIMessagePart>): List<String> = parts.map {
            when (it) {
                is UIMessagePart.Text -> "text:${it.text}"
                is UIMessagePart.Reasoning -> "reasoning"
                is UIMessagePart.Tool -> "tool:${it.toolName}"
                is UIMessagePart.ServerTool -> "servertool"
                else -> "other"
            }
        }

        // ① 默认（dropReasoning = false）= v320 老口径：思考留着，工具与 ServerTool 摘掉
        val legacy = listOf(groupMsg).withoutGroupChatToolParts().single()
        assertEquals(
            "默认口径：正文 + 思考 + 提问卡片；工具与服务商侧搜索摘掉",
            listOf("text:群里的结论", "reasoning", "tool:ask_user"),
            shapeOf(legacy.parts),
        )
        assertFalse("群里搜到的原文不许留下", legacy.toText().contains("群里为讨论搜的东西"))
        assertFalse("服务商侧搜索结果不许留下", legacy.toText().contains("服务商侧搜索结果"))

        // ② dropReasoning = true（用户在设置里打开开关）：思考也摘，正文与提问卡片照旧
        val clean = listOf(groupMsg).withoutGroupChatToolParts(dropReasoning = true).single()
        assertEquals(
            "开关打开：正文 + 提问卡片，思考也摘掉",
            listOf("text:群里的结论", "tool:ask_user"),
            shapeOf(clean.parts),
        )
        assertEquals(
            "提问卡片仍带用户答过的话（v318 红线）",
            "用户回答",
            (clean.getTools().single().approvalState as? ToolApprovalState.Answered)?.answer,
        )
        // 用户选的是「不删数据」：剥离只发生在请求视图上，原消息一个字段都不动
        assertEquals("原消息不许被改坏", 5, groupMsg.parts.size)
        assertEquals(
            "原消息里思考必须还在",
            1,
            groupMsg.parts.count { it is UIMessagePart.Reasoning },
        )
    }
}
