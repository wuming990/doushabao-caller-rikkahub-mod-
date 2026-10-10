package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * v313：AI 讨论群组的运行时状态（纯状态机，不依赖 Android，可直接单测）。
 *
 * 为什么单独一个文件、而且刻意不碰模型调用：
 * 圆桌的教训是「状态、调度、调模型、写页面」四件事混在一个 2700 行的 ChatService 里，
 * 出问题时只有读源码这一条路。这里把**决策**（下一个谁说话、这批跑完没、要不要停下等用户）
 * 收进一个纯函数状态机，执行层只负责照它说的做。
 *
 * 与圆桌 [RoundTableRun] 的关系：两者互不引用。圆桌是「一次性流水线」，
 * 群组是「可反复继续的共享时间线」，硬凑一套抽象会让两边都变脆。
 */

/** 群讨论当前在等什么 */
enum class GroupRunStatus {
    /** 刚建好，还没开始 */
    IDLE,

    /** 有成员正在说话 */
    SPEAKING,

    /** 成员向用户提问，等回答 */
    WAITING_FOR_USER,

    /** 这一批跑完了，用户可以点「继续讨论」再跑一批 */
    PAUSED,

    /** 用户主动停止 */
    STOPPED,

    /** 整场结束（成员全没了、或用户选择结束） */
    FINISHED,
    ;

    /** 还能不能往下推进 */
    val canAdvance: Boolean
        get() = this == IDLE || this == PAUSED
}

/**
 * 一次群讨论的可见状态。
 *
 * 只放界面要显示的东西与推进决策需要的信息；成员发言本身是普通消息，
 * 存在对话的 messageNodes 里，不在这里重复存一份（重复必漂移）。
 */
data class GroupRunState(
    val conversationId: Uuid,
    val status: GroupRunStatus = GroupRunStatus.IDLE,
    /** 本次「继续讨论」已经说了几条 */
    val turnsInRun: Int = 0,
    /** 本次批次允许几条（来自配置，界面可见，避免用户以为卡住） */
    val turnsBudget: Int = 0,
    /** 最近一个发言成员的模型 id，轮流模式靠它决定下一个 */
    val lastSpeakerModelId: Uuid? = null,
    /** 正在发言的成员 */
    val currentSpeakerModelId: Uuid? = null,
    val currentSpeakerName: String = "",
    /** 当前成员这次开口已产出的字数（界面进度用） */
    val chars: Int = 0,
    /** 停下等用户时，是哪个成员在问 */
    val askedByModelId: Uuid? = null,
    /** 上一次推进失败的原因（null = 没有）。必须显示给用户，别只写日志 */
    val error: String? = null,
    /**
     * v319：用户点名「下一个就他说」的那位成员；null = 没人被点名（照轮流/主持人走）。
     *
     * 用户口径：「新增一个途中切换成员模型和直接跳过中间 ai 指定某个 ai 来回答的功能」。
     * **只生效一次**（引擎取用后立刻清空）：留着的化他会一直插队，轮流顺序就废了。
     * 新字段带默认值，不影响任何既有构造点。
     */
    val nextSpeakerOverride: Uuid? = null,
) {
    /** 这批的额度用完了 */
    val budgetExhausted: Boolean
        get() = turnsBudget > 0 && turnsInRun >= turnsBudget
}

/**
 * 群讨论状态机。
 *
 * 刻意做成**单发言人串行**：用户说的是「实时讨论」，而多人同时输出会互相看不见对方
 * 刚说的话（并发读到的上文是同一份），讨论质量反而更差，还会在同一对话上并发写消息。
 * 串行 + 逐字流式显示，才是"看着像实时在聊"。
 */
/**
 * v319：点名的四种结果。**必须是顶层类型** —— 一开始误写在 GroupChatRun 里面，
 * 成了嵌套类型，ChatService / ChatVM / ChatPage 三处按顶层路径引用全部报
 * Unresolved reference（:app:compileDebugKotlin 实测 14 处红）。挪回顶层。
 *
 * 为什么必须把结果交回界面（审查位 v319 抓的 P1）：讨论正有人在说话、或正卡在
 * 「等用户回答提问」时点「你来说」，名字确实记下了，但界面一个字都不动 ——
 * 用户只会以为按钮坏了，然后反复点、或者以为功能没做。
 * 每种结果必须各有一句人话，不留静默路径。
 */
enum class GroupPickOutcome {
    /** 已记下，等当前这位说完就轮到他（**这位已经出声了**，撤掉会丢他写下的内容） */
    QUEUED,

    /** 讨论本来就停着：已当场开一批，他马上就说 */
    STARTED,

    /**
     * v320：当前这位**还没出声**（无正文、无已执行工具、无图片文件），已当场撤掉他，
     * 点名的那位马上接话。用户口径：「下一个模型只是准备开始说话，思考都没开始思考，
     * 就被锁在这个模型了，还得强制等待这个模型说完话才能换到我选择的模型」。
     */
    INTERRUPTED,

    /** 这个对话没有启用中的讨论群（服务层已在对话里贴了错误卡片） */
    NO_GROUP,
}

/**
 * v320：用户点名时，能不能**当场撤掉**正在开口的那位。
 *
 * 判据刻意不是「一个字都还没吐」，而是「没有值得留下来的东西」：
 * - 只有思考（Reasoning）→ 撤掉不心疼，思考本来就不算发言内容
 * - 只挂着**还没执行完**的工具调用 → 可以撤；整条消息会被一起删掉，
 *   所以不会留下「有调用没结果」的形状（那种形状服务商可能直接拒收下一句）
 * - 已有正文 / 已有执行完带回结果的工具 / 已有图片视频文件 → **不许撤**，
 *   撤了就是丢内容，且半截工具调用会留在对话里
 * - 工具已经被用户答过（Answered）→ 绝不许撤，那是用户自己写下的答案
 *
 * 抽成顶层纯函数只为了能真单测（Compose 与协程都跑不了单测，判据必须留在能跑的地方）。
 */
internal fun groupUtteranceHasKeptContent(message: UIMessage?): Boolean {
    message ?: return false
    return message.parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image,
            is UIMessagePart.Video,
            is UIMessagePart.Audio,
            is UIMessagePart.Document,
            -> true

            is UIMessagePart.Tool ->
                part.isExecuted ||
                    part.approvalState is ToolApprovalState.Answered ||
                    // v320 审查位抓的真窗口：**等用户批准的卡片**（ask_user 提问、需要审批的工具）
                    // 一旦出现在流式内容里就不许再撤 —— 撤掉等于把「TA 问了什么」连同卡片一起删掉，
                    // 用户本来打算回答的东西凭空消失。
                    // 注意这不影响本轮要修的症状：**正在执行中**的工具（approvalState 是 Auto/Approved、
                    // 还没结果）仍然算没出声、仍然可以撤 —— 那才是用户抱怨的「干等他说完」。
                    part.isPending
            is UIMessagePart.ServerTool -> part.isFinished
            // 旧形状：ToolResult 已经是跑完带回内容的（有真东西）；ToolCall 只是「想调用」，还没结果
            is UIMessagePart.ToolResult -> true
            is UIMessagePart.ToolCall -> part.approvalState is ToolApprovalState.Answered
            // 只有思考过、没有说过
            is UIMessagePart.Reasoning -> false
            // 官方已 @Deprecated 的空标记（没有任何内容）→ 跟 Reasoning 同一侧
            // ⚠️ 这一条是**编译器抓出来的**：我原本用「data class」找全部分片类型，
            // 而 Search 写成 `data object`，漏了。故意不写 else 才让它当场报错而不是静默漏判。
            is UIMessagePart.Search -> false
            // ⚠️ 刻意**不写 else**：官方以后新增一种分片时，编译器会直接报错，
            // 逼着人重新判断「这算不算已经出声」。写成 else 会把新类型默默归进「可以删」那一侧，
            // 那是会丢用户内容的错法（手册 §7.4「静默退化」同一族）。
        }
    }
}

/**
 * v320：用户点名换人 —— 服务层用它撤掉「还没出声」的那位成员。
 *
 * 为什么做成异常而不是返回值：发言是在流式 collect 里往外写的，撤掉只能靠**取消那次生成**，
 * 取消一定会以 CancellationException 的形式冒出来；而引擎必须能区分
 * 「用户点了停止」（整场结束）与「用户点名换人」（这一轮作废、当场换人继续跑）。
 * 用一个专属异常类型把两者分开，是唯一不会靠猜的做法。
 *
 * 它**不占批次额度、不算成员失败、不推进轮流游标** —— 那位一句话都还没说。
 */
internal class GroupSpeakerAbortedException(
    val modelId: Uuid,
    val speakerName: String,
) : Exception("已撤掉还没开口的发言：$speakerName")

/**
 * v320：撤场时把那条「什么都没留下」的发言从对话里删掉。
 *
 * 只删**本次调用新建的**那一条（[createdMessageId] 为空表示本次根本没写过消息，直接返回原样）。
 * 回答提问后的续写路径走的是「原地覆盖同一条」，那条消息里带着用户写下的答案，
 * 绝不允许被这个函数删掉 —— 所以调用方传进来的 id 只在 persist 且 overwriteMessageId == null 时才有值。
 * 判据再核一遍 [groupUtteranceHasKeptContent]：有任何值得留下的东西就不删（宁可留一条半截也不丢内容）。
 */
internal fun removeEmptyGroupUtterance(
    conversation: Conversation,
    createdMessageId: Uuid?,
): Conversation {
    val messageId = createdMessageId ?: return conversation
    val nodeIndex = conversation.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (nodeIndex < 0) return conversation
    val node = conversation.messageNodes[nodeIndex]
    val message = node.messages.firstOrNull { it.id == messageId } ?: return conversation
    if (groupUtteranceHasKeptContent(message)) return conversation
    val kept = node.messages.filterNot { it.id == messageId }
    val newNodes = conversation.messageNodes.toMutableList()
    if (kept.isEmpty()) {
        newNodes.removeAt(nodeIndex)
    } else {
        newNodes[nodeIndex] = node.copy(messages = kept, selectIndex = (node.selectIndex - 1).coerceAtLeast(0))
    }
    return conversation.copy(messageNodes = newNodes.toList())
}

/**
 * v320：群讨论停下来时，该不该给用户发一条系统通知、以及通知说成什么。
 *
 * 症状与根因（用户原话「弹出提问工具时，已经没有在继续生成了，但我处于后台不会弹出通知」）：
 * 通知是由 [me.rerere.rikkahub.service.ChatNotificationManager] 订阅事件总线产生的，
 * 而群讨论这条路**从来不往总线上发任何信号** —— 主对话能收到通知、群里收不到，差的就是这一段。
 *
 * 只在「停下来等人」时发，且**每批只发一次**（调用点在批次协程的收尾处，不在状态变更点）：
 * 轮流推进时 markTurnDone 会短暂落到 PAUSED 再立刻换下一个人，若在状态变化点通知，
 * 每句话都会响一次，比不发还糟。
 */
fun groupWaitReasonOf(status: GroupRunStatus, error: String?): AppEvent.GroupWaitReason? = when (status) {
    GroupRunStatus.WAITING_FOR_USER -> AppEvent.GroupWaitReason.QUESTION
    GroupRunStatus.PAUSED -> if (error != null) AppEvent.GroupWaitReason.FAILED else AppEvent.GroupWaitReason.TURN_DONE
    // v320：整场结束与「这批说完了」是两件事，文案必须分开（见 AppEvent.GroupWaitReason.ENDED 的说明）
    GroupRunStatus.FINISHED -> AppEvent.GroupWaitReason.ENDED
    // IDLE = 新一批刚开跑；SPEAKING = 有人正在说；STOPPED = 用户自己点的停止，不该再吵他
    GroupRunStatus.IDLE,
    GroupRunStatus.SPEAKING,
    GroupRunStatus.STOPPED,
    -> null
}

internal class GroupChatRun(
    conversationId: Uuid,
    turnsBudget: Int,
) {
    private val _state = MutableStateFlow(
        GroupRunState(conversationId = conversationId, turnsBudget = turnsBudget)
    )
    val state: StateFlow<GroupRunState> = _state.asStateFlow()

    fun snapshot(): GroupRunState = _state.value

    /**
     * v320：场次编号（每真正开一批 +1）。
     *
     * 修的是「点了没反应」这一族症状里的一个隐藏根因：
     * [beginRun] 会先把状态改成 IDLE，**再**由调用方取消上一批还挂着的协程；旧协程临死的
     * catch 里无条件写 `markStopped()`，就会把新一批盖成 STOPPED —— 新一批一睁眼看到
     * 「已停止」立刻退出，用户得到的就是「点了『你来说』，全场安静」。
     * 收尾时先对一下场次编号，已经不是自己那一场就不再改写状态。
     *
     * 为什么不用「取消前先把状态设成 STOPPED」那种写法：那会把 beginRun 与 cancel 之间
     * 的窗口原样留下，只是换个方向赌时序。
     */
    @Volatile
    var runEpoch: Long = 0L
        private set

    /**
     * v320：只有还在同一场次里才落 STOPPED。
     *
     * @return false = 这一批已经被更新的一批顶掉了，什么都不该改
     */
    fun markStoppedIfCurrent(epoch: Long): Boolean {
        if (runEpoch != epoch) return false
        markStopped()
        return true
    }

    /** 开始一批讨论：把批次计数清零，额度刷新 */
    fun beginRun(turnsBudget: Int): Boolean {
        val current = _state.value
        if (current.status == GroupRunStatus.SPEAKING) return false
        if (current.status == GroupRunStatus.WAITING_FOR_USER) return false
        // v320：开新场次先记账，再改状态（顺序别颠倒：颠倒时旧协程可能读到「新状态 + 旧场次」
        // 仍然把自己那一份 STOPPED 盖上来）
        runEpoch++
        _state.update {
            it.copy(
                status = GroupRunStatus.IDLE,
                turnsInRun = 0,
                turnsBudget = turnsBudget,
                error = null,
            )
        }
        return true
    }

    /** 某个成员开始说话 */
    fun markSpeaking(modelId: Uuid, modelName: String) {
        _state.update {
            it.copy(
                status = GroupRunStatus.SPEAKING,
                currentSpeakerModelId = modelId,
                currentSpeakerName = modelName,
                chars = 0,
                error = null,
            )
        }
    }

    /** 流式进度：只动字数，不动状态 */
    fun touch(chars: Int) {
        _state.update { if (it.status == GroupRunStatus.SPEAKING) it.copy(chars = chars) else it }
    }

    /**
     * 这个成员说完了。
     *
     * 返回 true 表示「这批还有额度，可以继续下一个」；false 表示调用方应当收尾停下。
     */
    fun markTurnDone(modelId: Uuid): Boolean {
        var canContinue = false
        _state.update {
            val turns = it.turnsInRun + 1
            canContinue = turns < it.turnsBudget
            it.copy(
                // 两种情况都只是「这批先停在这里」：额度用完时不自动续跑，
                // 由用户点「继续讨论」再开一批 —— 群组是长会话，不能像流水线那样一路烧到底。
                status = GroupRunStatus.PAUSED,
                turnsInRun = turns,
                lastSpeakerModelId = modelId,
                currentSpeakerModelId = null,
                currentSpeakerName = "",
                chars = 0,
            )
        }
        return canContinue
    }

    /**
     * 成员调用了提问工具，等用户回答。
     *
     * 注意：**不消耗批次额度**。用户答完后同一个成员接着说，那才算一条发言完成。
     */
    fun markWaitingForUser(modelId: Uuid) {
        _state.update {
            it.copy(
                status = GroupRunStatus.WAITING_FOR_USER,
                askedByModelId = modelId,
                currentSpeakerModelId = modelId,
                chars = 0,
            )
        }
    }

    /**
     * 放弃这次等待（用户点「跳过提问」，或那条提问已经被作废）。
     *
     * 审查位抓到的 P0：成员提问后用户点了停止，提问工具会被标成已取消，
     * 于是 answerGroupQuestion 的判据永远不再成立 —— 群卡在 WAITING_FOR_USER，
     * 而界面此时既不给「继续」也不给「停止」，整场永久焊死。
     * 这个出口就是那条死路的终点：状态落回 PAUSED，用户还能继续讨论。
     */
    fun markQuestionAbandoned() {
        _state.update {
            it.copy(
                status = GroupRunStatus.PAUSED,
                askedByModelId = null,
                currentSpeakerModelId = null,
                currentSpeakerName = "",
            )
        }
    }

    /** 用户答完了，让刚才提问的成员继续 */
    fun resumeFromQuestion(): Uuid? {
        val asked = _state.value.askedByModelId ?: return null
        _state.update {
            it.copy(
                status = GroupRunStatus.SPEAKING,
                askedByModelId = null,
                currentSpeakerModelId = asked,
            )
        }
        return asked
    }

    /**
     * v319：用户点名 —— 让指定成员下一个说话（跳过当前轮流顺序）。
     *
     * 已停 / 已结束的场次不接受点名：那时该走「继续讨论」，悄悄把状态改回可跑
     * 会让用户以为讨论自己在动。
     *
     * @return false = 当前状态不接受点名（界面要告诉用户为什么没反应，不许静默）
     */
    fun requestSpeaker(modelId: Uuid): Boolean {
        val current = _state.value
        if (current.status == GroupRunStatus.STOPPED || current.status == GroupRunStatus.FINISHED) {
            return false
        }
        _state.value = current.copy(nextSpeakerOverride = modelId)
        return true
    }

    /** v319：取走点名并清空（引擎每轮开头调一次；没人被点名返回 null） */
    fun takeRequestedSpeaker(): Uuid? {
        val pending = _state.value.nextSpeakerOverride ?: return null
        _state.value = _state.value.copy(nextSpeakerOverride = null)
        return pending
    }

    fun markStopped() {
        _state.update {
            it.copy(
                status = GroupRunStatus.STOPPED,
                currentSpeakerModelId = null,
                askedByModelId = null,
            )
        }
    }

    fun markFinished() {
        _state.update {
            it.copy(
                status = GroupRunStatus.FINISHED,
                currentSpeakerModelId = null,
                askedByModelId = null,
            )
        }
    }

    /**
     * 某个成员这一轮失败。
     *
     * 关键取舍：**跳过它继续下一个，而不是整场报错停下**。群里少一个人说话不影响讨论，
     * 而整场停住会让用户以为功能坏了。原因必须留在 state 里给界面显示（手册 §7 续跑铁律 ③：
     * 只显示「中断」不显示原因，用户和 AI 都没法定位）。
     *
     * ⚠️ 失败时**也必须推进 lastSpeakerModelId**。轮流模式的下一个发言人是靠它算的，
     * 不推进就会把同一个刚失败的成员再挑一次 —— 一个稳定报错的成员会把整批额度全吃掉，
     * 别的成员一句话都说不上。这条是写完这版才发现的，别改回去。
     * 失败同样占一条额度，理由同上：不占就等于无限重试。
     */
    fun markTurnFailed(modelId: Uuid, speakerName: String, reason: String): Boolean {
        var canContinue = false
        _state.update {
            val turns = it.turnsInRun + 1
            canContinue = turns < it.turnsBudget
            it.copy(
                status = GroupRunStatus.PAUSED,
                turnsInRun = turns,
                lastSpeakerModelId = modelId,
                currentSpeakerModelId = null,
                currentSpeakerName = "",
                chars = 0,
                error = failureText(speakerName, reason),
            )
        }
        return canContinue
    }

    companion object {
        /** 失败原因里带上是谁 */
        fun failureText(speakerName: String, reason: String): String =
            if (speakerName.isBlank()) reason else "$speakerName：$reason"
    }
}

/**
 * v313：这条发言是不是「成员在向用户提问」。
 *
 * ask_user 是 needsApproval 的工具，GenerationLoop 会把它标成 Pending 然后结束这一股流
 * （`generateText: waiting for tool approval`）。所以群引擎判断「该停下来等用户」的依据
 * 就是：**最后一条消息里有没有还没执行的 ask_user**。
 *
 * 刻意不看正文里有没有问号 —— 那是猜，误判会把正常发言当成提问、把讨论卡住。
 */
internal fun groupTurnAskedUser(message: UIMessage?): Boolean {
    message ?: return false
    return message.getTools().any { tool ->
        tool.toolName == GROUP_ASK_USER_TOOL_NAME && !tool.isExecuted && tool.isPending
    }
}

/** ask_user 的工具名，与 LocalTools 注册的那个一致 */
internal const val GROUP_ASK_USER_TOOL_NAME = "ask_user"

/**
 * v313：群里「跳过提问」时回传给成员的内容。
 *
 * 形状照 v300 询问卡片的跳过按钮（answers + note），只是这里由代码直接构造 ——
 * 面板上的跳过入口拿不到那张卡片的题目列表，所以退化成一条自由文本。
 * 刻意写得明确「不要重复问同样的问题」：否则成员很可能原样再问一次，用户又被卡住。
 */
internal const val GROUP_SKIP_ANSWER =
    "用户跳过了本次询问，没有作答。请基于现有信息自行决定并继续，" +
        "不要重复问同样的问题；如果确实必须知道，请改用普通文字再问一次。"

/**
 * v313：这个对话是不是「群讨论停在等用户回答」。
 *
 * 用户答完提问后，主对话那条路会把续跑交给**助手默认模型**
 * （`handleToolApproval` → `handleMessageComplete` → `findModelById(assistant.chatModelId ?: …)`）。
 * 群里照抄会变成「A 提问、B 回答」，讨论直接串味 —— 所以那条路必须先问这个函数，
 * 是的话改走群引擎，用**提问的那位成员**继续。
 *
 * 判据用引擎自己的状态而不是「消息里有 pending 工具」：后者在普通对话里也成立，
 * 拿它当分岔条件会把主对话的提问功能一起改掉（禁区 #13）。
 */
internal fun GroupChatRun.isWaitingForUserAnswer(): Boolean =
    snapshot().status == GroupRunStatus.WAITING_FOR_USER && snapshot().askedByModelId != null

/**
 * v313：把成员的一条发言写进对话，返回新的对话状态。
 *
 * 为什么单独抽成纯函数（而不是在 ChatService 里就地写）：这是群功能唯一会**改动用户数据**
 * 的动作，写坏了就是丢消息或写出重复内容，必须能被真单测覆盖。
 *
 * 两种情况：
 * - [targetMessageId] 为 null = 新发言。若最后一条已经是这条消息本身（流式刷新中，
 *   生成过程里同一条助手消息的 id 不变），原地覆盖；否则追加为新节点。
 * - [targetMessageId] 非空 = 回答提问后的续写，必须**原地覆盖那一条**。
 *   另起一条会让用户看到同一次发言被拆成两截。
 *
 * ⚠️ 这里刻意**不用** `updateCurrentMessages`：那个函数按下标整表对齐，而群成员的上文里
 * 多了一条「不入库的临时指令」，按下标写回会把那条指令永久存进对话，
 * 之后每个成员都会收到一堆重复的会议规则。
 */
internal fun upsertGroupUtterance(
    conversation: Conversation,
    utterance: UIMessage,
    targetMessageId: Uuid?,
): Conversation {
    val nodes = conversation.messageNodes
    if (targetMessageId != null) {
        val index = nodes.indexOfFirst { node -> node.messages.any { it.id == targetMessageId } }
        if (index >= 0) {
            val node = nodes[index]
            val messageIndex = node.messages.indexOfFirst { it.id == targetMessageId }
            val updated = node.messages.toMutableList()
            // 保留原 id：覆盖同一条，而不是新增一条
            updated[messageIndex] = utterance.copy(id = targetMessageId)
            val newNodes = nodes.toMutableList()
            newNodes[index] = node.copy(messages = updated, selectIndex = messageIndex)
            return conversation.copy(messageNodes = newNodes.toList())
        }
        // 目标消息已经不在了（用户删了那条发言）：退回追加，别把这次产出丢掉
    }
    val last = nodes.lastOrNull()
    if (last != null && last.messages.any { it.id == utterance.id }) {
        val updated = last.messages.toMutableList()
        updated[updated.indexOfFirst { it.id == utterance.id }] = utterance
        val newNodes = nodes.toMutableList()
        newNodes[nodes.lastIndex] = last.copy(messages = updated)
        return conversation.copy(messageNodes = newNodes.toList())
    }
    return conversation.copy(
        messageNodes = nodes + MessageNode(messages = listOf(utterance), selectIndex = 0)
    )
}

/**
 * v314：用户在群里插了一句话之后，要不要**顺手开一批**讨论。
 *
 * 用户拍板（v314 选项 A）：「我说的话成为讨论的一部分，当前这位说完后就轮到成员接着讨论」。
 * 所以只有两种情况不插手：
 * - 正在说话：当前这位说完，引擎自己会接着挑人，这里再开一批会打架（`beginRun` 也会拒绝）；
 * - 正在等你回答：提问还挂着，讨论的下文必须由那位成员接着说，不能另起一批。
 *
 * 其余（没跑过 / 暂停 / 停过 / 这轮已结束）全部自动开一批 —— 用户刚插了话却没反应，
 * 比多花一次调用更糟。
 */
fun shouldAutoContinueAfterInterjection(status: GroupRunStatus?): Boolean =
    status != GroupRunStatus.SPEAKING && status != GroupRunStatus.WAITING_FOR_USER

/**
 * v314：群面板上那个主按钮该显示成什么。
 *
 * 单独抽成纯函数只为了能**真单测** —— 面板本身是 Compose，`contains` 型断言证明不了
 * 「按钮在什么状态下给什么」，而按钮给错状态正是用户抱怨的「不知道怎么开始、怎么停」。
 *
 * ⚠️ 每一个取值都必须被面板真正消费（审查位 v314 指出：返回值没被界面用上就是半个假开关）。
 * 面板用的是 `when (primary)` 全量分派，没有第二套判据。
 */
enum class GroupPrimaryAction {
    /** 显示「开始/继续讨论」大按钮，点了走 onContinue */
    DISCUSSION,

    /** 显示「跳过这次提问」大按钮，点了走 onSkipQuestion */
    SKIP_QUESTION,

    /** 不给主按钮：有成员正在说话，那个场合只该给「停止」 */
    NONE,
}

fun resolveGroupPrimaryAction(status: GroupRunStatus): GroupPrimaryAction = when (status) {
    GroupRunStatus.SPEAKING -> GroupPrimaryAction.NONE
    GroupRunStatus.WAITING_FOR_USER -> GroupPrimaryAction.SKIP_QUESTION
    GroupRunStatus.IDLE,
    GroupRunStatus.PAUSED,
    GroupRunStatus.STOPPED,
    GroupRunStatus.FINISHED,
    -> GroupPrimaryAction.DISCUSSION
}
