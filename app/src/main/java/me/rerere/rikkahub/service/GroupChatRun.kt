package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.UIMessage
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
internal class GroupChatRun(
    conversationId: Uuid,
    turnsBudget: Int,
) {
    private val _state = MutableStateFlow(
        GroupRunState(conversationId = conversationId, turnsBudget = turnsBudget)
    )
    val state: StateFlow<GroupRunState> = _state.asStateFlow()

    fun snapshot(): GroupRunState = _state.value

    /** 开始一批讨论：把批次计数清零，额度刷新 */
    fun beginRun(turnsBudget: Int): Boolean {
        val current = _state.value
        if (current.status == GroupRunStatus.SPEAKING) return false
        if (current.status == GroupRunStatus.WAITING_FOR_USER) return false
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
