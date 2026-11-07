package me.rerere.rikkahub.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_MODEL_ACTIVE
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_RESUME_CHECKPOINT
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentFinishReason
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.tools.withSmallAgentOutputLimit
import me.rerere.rikkahub.agent.tools.createAgentReadOnlyTools
import me.rerere.rikkahub.agent.tools.createAgentWritableTools
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.agentMcpToolRecords
import me.rerere.rikkahub.data.ai.mcp.agentMcpStalenessNote
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.agentStepBudget
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.displayLabel
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.time.Instant
import kotlin.uuid.Uuid

/** 模型不可用（未配置/已删除）时抛出 */
class AgentModelUnavailableException(message: String) : IllegalStateException(message)

/**
 * v236：一次模型尝试的失败包装。
 *
 * 备用模型链要判断「能不能换下一个模型重来」，关键在于**这次尝试有没有已经吐出内容**：
 * 已经吐了半截再换模型重跑，会把两个模型的输出拼在一起，报告直接错乱。
 * 那种情况交给续跑机制（保留已产出内容、从中断处接着写）更安全。
 */
private class AgentAttemptFailure(
    val failure: Throwable,
    val producedOutput: Boolean,
) : Exception(failure)

/** v222：续跑时追加的指令（v251 起由 [buildResumeInstruction] 按模式分派，此常量保留给旧引用/门禁） */
internal const val AGENT_RESUME_INSTRUCTION =
    "上一次输出被中断或因达到长度上限被截断。请直接从中断处继续往下写：" +
        "不要重新开头，不要重复已经输出过的内容。" +
        "如果任务其实已经做完，就直接补齐「结论 / 证据 / 不确定项 / 建议」四段后结束。"

/**
 * v251：接尾续写指令（suffix 模式）。
 * v254：照圆桌那条 ROUND_TABLE_CONTINUE_INSTRUCTION 重写，删掉一切「可以重交完整报告」的出口。
 *
 * 只用于「真正被截断 / 被手动停止，缺的是后半段」：只补缺失的后半段，禁止重抄，
 * 禁止只回「无需重复」之类的元话语。
 *
 * 为什么必须改文案（用户真机现象「点继续输出却像重新生成」的主因）：
 * v251 版最后一句明确授权「就交一份完整、紧凑、可解析的报告（机器会把它当替换稿处理）」。
 * 模型照做就是重做一遍任务 —— 用户等于为同一件事付两次钱，观感上就是「重新生成」。
 * 对照圆桌那条用户认可的续跑指令：通篇没有「完整」「替换」「重新交」任何字样，
 * 只有「从中断处继续往下写 / 不要重新开头 / 停在哪个字就从那个字补完」。这是唯一但决定性的差别。
 */
internal const val AGENT_RESUME_SUFFIX_INSTRUCTION =
    "你上一次的输出没有写完就被中断了（可能是达到单次输出长度上限，也可能是被手动停止）。\n" +
        "上面那条 assistant 消息就是你已经写出来的全部内容。请**只补缺失的后半段**：\n" +
        "1. 不要重新开头，不要重复已经写过的任何内容，不要写「接上文」「继续」之类的过渡语；\n" +
        "2. 如果上一次正好停在句子、代码或标记中间，就从那个字继续补完；\n" +
        "3. 机器会自动把你这次写的内容接到前半段后面，所以你不需要、也不要重抄前面已有的内容；\n" +
        "4. 前面已经写过 <report> 开标记的，本次只要在结尾补上 </report>；" +
        "前面还没开过的，就在补写的内容里把 <report> 与 </report> 补齐；\n" +
        "5. 不要只回「无需重复」「上一轮已完成」之类的话；如果该说的其实已经写完了，" +
        "就只补结尾缺的部分（结论 / 证据 / 不确定项 / 建议）后结束。"

/**
 * v254：v251 版接尾指令的原文，**已停用**，只留给 [isResumeInstructionText] 认历史。
 *
 * 老线程的历史消息里可能落过这段文本（旧版写入，或调用方把指令原样转给了
 * send_agent_message）。回放续跑输入时必须能把它认出来滤掉，
 * 否则它会被当成「用户补充指令」重复加进上下文。
 */
internal const val AGENT_RESUME_SUFFIX_INSTRUCTION_V251 =
    "上一次输出被中断（只写到了一半）。请**只补缺失的后半段**：" +
        "不要重新开头，不要重复已经输出过的内容，也不要只回「无需重复」「上一轮已完成」之类的话。" +
        "直接从断点继续往下写，写完把完整结果用 <report> 与 </report> 包好。" +
        "如果缺的部分恰好能构成一份完整报告，就交一份完整、紧凑、可解析的报告（机器会把它当替换稿处理）。"

/**
 * v251：完整替换修复指令（repair 模式）。
 *
 * 只用于「上一轮交的报告没被采用」（占位符 / 格式无效 / 本地裁剪 / 旧线程历史不完整）：
 * 交一份完整、紧凑、可解析的替换报告，不能只解释「上一轮已完成」，不能追加到旧报告后面。
 */
internal const val AGENT_RESUME_REPAIR_INSTRUCTION =
    "你上一轮交出的报告没有被采用（占位符 / 格式无效 / 历史不完整）。" +
        "请重新交一份**完整、紧凑、可解析**的替换报告，用 <report> 与 </report> 包好，" +
        "不要只解释「上一轮已完成」「无需重复」之类的话，也不要把它追加到旧报告后面。" +
        "若确实没有找到目标，也要说明检查了哪些范围、为什么没有结果。"

/** v251：按续跑模式取对应的追加指令。 */
internal fun buildResumeInstruction(mode: AgentResumeMode): String = when (mode) {
    AgentResumeMode.SUFFIX -> AGENT_RESUME_SUFFIX_INSTRUCTION
    AgentResumeMode.REPAIR -> AGENT_RESUME_REPAIR_INSTRUCTION
}

/**
 * v251：一段 user 文本是不是恰好等于某条续跑指令模板（旧版 [AGENT_RESUME_INSTRUCTION]
 * 或 v251 的 [AGENT_RESUME_SUFFIX_INSTRUCTION] / [AGENT_RESUME_REPAIR_INSTRUCTION]）。
 *
 * 续跑输入末尾只会追加**一条**本轮指令；如果历史里落过一条内容恰好等于指令模板的
 * user 消息（例如旧版写入、或调用方把指令文本原样转给了 send_agent_message），
 * 回放时把它滤掉，避免「旧的续跑指令当历史重复加入」。
 */
internal fun isResumeInstructionText(text: String?): Boolean {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return false
    return t == AGENT_RESUME_INSTRUCTION.trim() ||
        t == AGENT_RESUME_SUFFIX_INSTRUCTION.trim() ||
        // v254：接尾指令换了文案，老线程历史里的 v251 旧文案同样要被认出来滤掉
        t == AGENT_RESUME_SUFFIX_INSTRUCTION_V251.trim() ||
        t == AGENT_RESUME_REPAIR_INSTRUCTION.trim()
}

/**
 * v251：本地裁剪留下的省略标记（clipAgentReportText / clipAgentMessageContent 共用）。
 * 报告正文里出现它说明内容被本地裁剪过，不能再当「接着写」处理，必须完整替换修复。
 */
internal const val AGENT_OMISSION_MARKER = "…（中间省略"

/** v222：续跑上下文最多回灌的历史消息条数与总字符数（防止上下文爆炸与重复收费） */
private const val RESUME_HISTORY_MAX_MESSAGES = 12
private const val RESUME_HISTORY_MAX_CHARS = 24 * 1024

/**
 * v236：只读子代理的步数上限。
 *
 * v240 从 20 提到 32。v236 的注释原话是「v218 起一直是 20，本轮不动，没有证据说不够」——
 * 现在有证据了：本轮真机实测，一个只读探测子代理（要核对 3 个文件的十来个点）
 * 跑满 20 步后被切断，finish_reason=tool_calls、报告一个字都没有。
 * 廉价模型尤其爱反复搜同一个词，20 步很容易在还没动笔时就用完。
 */
internal const val AGENT_MAX_STEPS_READONLY = 32

/** v236：拿到写权限的子代理步数上限（读改读改一轮就吃好几步，20 步明显不够） */
internal const val AGENT_MAX_STEPS_WRITABLE = 48

/**
 * v238→v270：同一个模型连续试几次才换下一个。
 *
 * 为什么不是一次就换：限流（429）和上游 5xx 往往几秒后就恢复，
 * 立刻换模型等于把一次本来能成的调用浪费掉，还可能换到更贵的模型上。
 *
 * v269 曾把这个值做成独立设置项「换模型前重试几次」；v270 用户拍板合并：
 * 「续跑次数没了直接换下个模型就行了，不用额外设置一个按键」——
 * 同模型重试次数直接复用「自动续跑次数」（Settings.agentAutoResumeMax），
 * 不再单设旋钮。该值为 0（不自动续跑）时至少保留首次尝试
 * （coerceAtLeast(1)），否则整条模型链一个都不会跑。
 */

/** v238：同一个模型两次尝试之间的等待（限流类错误需要喘口气） */
internal const val AGENT_MODEL_RETRY_DELAY_MILLIS = 3_000L

/** v238：超时分钟数的上界（与设置界面、PreferencesStore 的 coerceIn 保持一致） */
internal const val AGENT_THREAD_TIMEOUT_MAX_MINUTES = 120

/**
 * v239：一次尝试里「连续多久没有任何新内容」就判它卡住。
 *
 * 为什么必须有它（用户真机反馈：「正常情况怎么会好几分钟没有新内容？」）：
 * v238 只有「这一次尝试整体最多跑多久」这一道闸，默认 10 分钟。上游只要保持连接、
 * 一个字都不吐，前 10 分钟内没有任何机制会察觉 —— 界面上就是干等，用户完全无法
 * 判断它是在长思考还是已经死了。圆桌那边早就有看门狗（2 分钟提示 / 20 分钟强停），
 * 子代理这边一直没有。
 *
 * 判到卡住之后不是直接失败，而是当作「这次尝试失败」接上既有链条：
 * 同一个模型再试一次 → 还不行就换下一个备用模型。
 */
internal const val AGENT_IDLE_TIMEOUT_MILLIS = 4 * 60 * 1000L

/**
 * v243：静默判据的**上限**（最多容忍多久没动静）。
 *
 * v239~v242 的算法是 `min(4 分钟, 总超时/2)` —— 4 分钟在那里是**上限**，于是用户把
 * 「子代理单次超时」调到 20 分钟也没用，静默判据仍然死死卡在 4 分钟。
 *
 * 真机实测（v242 复测时抓到）：一条要核对 8 处代码的复核任务，连续两趟都撞在这 4 分钟上
 * —— 模型在长时间读文件与思考，期间确实不产出可见内容，于是被判卡死，两次自动续跑的
 * 额度就这么烧光，最后报失败。任务本身并没有问题。
 *
 * v243 改成：`总超时/2`，再夹在 [AGENT_IDLE_TIMEOUT_MILLIS]（下限 4 分钟）与本上限之间，
 * 并且不超过总超时本身。默认总超时 10 分钟 → 静默阈值 5 分钟；调到 20 分钟 → 10 分钟。
 */
internal const val AGENT_IDLE_TIMEOUT_MAX_MILLIS = 10 * 60 * 1000L

/**
 * v244：单趟的**硬兜底死线**，只为防止模型陷进死循环无限烧钱。
 *
 * 用户原话：「把这个单个子代理超时也改一下，改成多久没进行新发言就判卡死而不是按照
 * 总运行时长。」在此之前有两道闸并存：总运行时长一到就砍（不管它是不是正在好好干活），
 * 外加静默看门狗。正在持续产出的重任务因此被无谓地砍掉。
 *
 * v244 起「判卡死」只看静默，总时长只留这一条远得碰不到的兜底。
 *
 * 为什么是 4 小时而不是 2 小时：静默阈值本身最大可以设到
 * [AGENT_THREAD_TIMEOUT_MAX_MINUTES]（120 分钟）。兜底如果也是 2 小时，用户把阈值拉满时
 * 两条闸会同时到点，甚至兜底先到 —— 那就又变成「按总时长砍」，正是这次要改掉的东西。
 * 4 小时给到阈值上限的两倍，确保任何合法设置下都是静默判据先生效。
 */
// v1024：历史4小时死线已撤，用户手填分钟不能再被隐藏的总时长砍掉。
// 保留旧符号供既有诊断/测试读取；Long.MAX_VALUE是协程API的无期限哨兵。
internal const val AGENT_ATTEMPT_HARD_DEADLINE_MILLIS = Long.MAX_VALUE

/** v244：静默阈值的默认值（设置里填 0 或非法值时用它） */
internal const val AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS = 10 * 60 * 1000L

/**
 * v244：算出这一趟允许「多久没有新内容」。
 *
 * 用户原话：「把这个单个子代理超时也改一下，改成多久没进行新发言就判卡死而不是按照
 * 总运行时长。」
 *
 * 所以这个设置项的语义从 v244 起就**是**静默阈值本身，不再从总时长折算：
 * 填 5 就是「连续 5 分钟没有任何新内容才算卡住」，填 0（或非法值）用默认 10 分钟。
 * 总运行时长只剩 [AGENT_ATTEMPT_HARD_DEADLINE_MILLIS] 这一条防死循环的兜底。
 *
 * 之前（v239~v243）的算法是「总超时的一半，夹在 4~10 分钟」，两个后果都被真机踩到：
 * ① 正在持续产出的重任务被总时长砍掉；② 用户把总时长设成不限时，静默看门狗被一起
 * 关掉，一条子代理连续 23 分钟没动静却无人管。
 *
 * 抽成顶层函数是为了能直接单测（内联在生成流程里只能靠 grep 源码断言，
 * 而「4 分钟上限」这个真实缺陷正是这样漏过去的）。
 */
internal fun agentIdleTimeoutMillis(idleMinutes: Int): Long {
    if (idleMinutes <= 0) return AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS
    // v1024：正数逐分钟生效，0/非法仍按原默认10分钟。
    return idleMinutes * 60_000L
}

/** v239：静默看门狗的检查间隔 */
internal const val AGENT_IDLE_CHECK_INTERVAL_MILLIS = 15 * 1000L

/**
 * v245：工具执行期间允许的最长等待。
 *
 * ## 修的是什么真机故障
 *
 * 工具是**同步**执行的：GenerationLoop 在一轮里先把模型输出 emit 出去，然后在
 * `toolsToProcess.forEach` 里逐个跑工具，跑完才再 emit 一次。也就是说「工具正在执行」
 * 的整段时间里，子代理后端一个数据块都收不到 —— 而静默看门狗恰恰只认数据块。
 *
 * 后果：一个耗时超过静默阈值的工具（联网搜索卡住、对很大的目录做文本搜索）会被判成
 * 「卡死」，而且此时 `producedOutput` 往往是 false，于是走的是「无产出 → 同模型重试 →
 * 换备用模型」，和真卡死完全无法区分。用户设的阈值越小（真机现场是 5 分钟）越容易踩。
 *
 * 所以 v245 起：**工具在跑的时候不按「没动静」算**，但工具自己要有上限。
 *
 * v246：上限从写死的 10 分钟改成用户可调（设置项「工具最长执行时间」）。
 * 真机反馈：用户把卡住判定设成 5 分钟，却发现 8 分钟都没停下 —— 因为当时正卡在一次
 * 工具调用里、走的是这条 10 分钟的闸，而界面上一个字都没提，只会以为设置没生效。
 */
internal const val AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES = 10

/** v246：工具上限的可调范围（与设置界面、PreferencesStore 的 coerceIn 保持一致） */
internal const val AGENT_TOOL_TIMEOUT_MAX_MINUTES = 60

/** v246：算出「一个工具最多允许跑多久」。见 [AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES] */
internal fun agentToolWaitCeilingMillis(toolTimeoutMinutes: Int): Long {
    val minutes = if (toolTimeoutMinutes <= 0) {
        AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES
    } else {
        toolTimeoutMinutes
    }
    return minutes * 60_000L
}

/**
 * v245：这一刻该不该判「卡死」。
 *
 * 抽成顶层纯函数是为了能真单测 —— 「工具跑久了被当成没动静」这种缺陷靠 grep 源码抓不到，
 * 而看门狗内联在协程里根本没法喂数据。
 *
 * 规则：
 * - 静默阈值 <= 0（例如非流式，见 [GenerationAgentBackend.run]）→ 永不按静默判，只留硬兜底；
 * - 有工具在执行 → 只看这个工具跑了多久，超过上限才算卡死（工具时间不计入静默）；
 * - 没有工具在执行 → 按「多久没有新内容」判。
 */
internal fun shouldJudgeStuck(
    idleForMillis: Long,
    idleTimeoutMillis: Long,
    toolInFlight: Boolean,
    toolRunningForMillis: Long,
    toolWaitCeilingMillis: Long,
): Boolean {
    // v1024：非流式不判模型静默，但工具自身超时仍必须生效。
    if (toolInFlight) return toolWaitCeilingMillis > 0L && toolRunningForMillis >= toolWaitCeilingMillis
    if (idleTimeoutMillis <= 0L) return false
    return idleForMillis >= idleTimeoutMillis
}

/**
 * v245：报告标记只开不闭 → 说明写到一半被切断了。
 *
 * ## 修的是什么漏检
 *
 * [extractAgentReportPayload] 的正则是 `<report>(.*?)</report>`，**匹配不到就原样返回全文**。
 * 于是「模型写了 `<report>` 开头、正文写到一半被切断」这条路整链漏检：
 * 提取回退成全文 → conclusion 非空 → 不是 reportEmpty → 不置 truncated →
 * canResume 为 false → 不自动续写 → 主模型拿到一份**看着完整的半截报告**。
 *
 * 判据刻意取「最后一个开标记之后有没有闭标记」：模型先示范一次完整格式、再写正式报告
 * 时不会误判；完全没用标记的（廉价模型不听话）也不误判，保持与 v244 一致的保守行为。
 */
internal fun looksLikeUnclosedReport(raw: String): Boolean {
    val text = raw.lowercase()
    val lastOpen = text.lastIndexOf("<report>")
    if (lastOpen < 0) return false
    return text.indexOf("</report>", lastOpen) < 0
}

/**
 * v238：一次模型尝试超时。
 *
 * ## 为什么需要
 *
 * 用户真实反馈：「子代理卡半天我很难判断是不是卡住了」。v237 之前**单独派发的子代理
 * 完全没有超时**（只有流水线的每一棒有 900 秒上限），上游一挂住连接就无限期挂着，
 * 界面永远显示「正在查证...」，而且死线程一直占着并发额度。
 *
 * ## 为什么超时要按「每次尝试」算而不是「整条线程」算
 *
 * 按整条线程算的话，第一个模型卡住就会把后面的重试与换模型机会一起吃掉 ——
 * 超时反而破坏了备用模型链。按每次尝试算才能和「同模型重试次数」组合成
 * 「卡住 → 算这次失败 → 同模型再试 → 还卡 → 换下一个模型」的完整链条。
 *
 * 消息里刻意带 `timeout` 字样：[me.rerere.rikkahub.agent.model.AgentErrorKind.classify]
 * 会据此判成 RECOVERABLE，从而允许自动续跑。
 */
class AgentAttemptTimeoutException(minutes: Int, idle: Boolean = false) :
    IllegalStateException(
        // v242：两种「卡死」判据的措辞必须分清。
        //
        // 旧文案一律写「单次尝试超过 X 分钟没有结束」，但真机上绝大多数触发的其实是
        // 静默看门狗 ——「连续 X 分钟没有任何新内容」，阈值是 min(4 分钟, 总超时/2)。
        // 用户看到「超过 4 分钟没有结束」，又发现总超时设置写着 10 分钟，只会以为是
        // 总时长被砍了。本轮真机实测就踩到这个误解。
        //
        // 两个分支都必须保留 `timeout` 字样：AgentErrorKind.classify 靠它判 RECOVERABLE，
        // 从而允许自动续跑。
        if (idle) {
            "子代理连续 $minutes 分钟没有任何新内容（timeout），已按卡死处理"
        } else {
            "子代理单次尝试超过 $minutes 分钟没有结束（timeout），已按卡死处理"
        }
    )

/**
 * v238：一次尝试的产出进度。
 *
 * 超时是在 [GenerationAgentBackend.run] 外层用 `withTimeoutOrNull` 实现的，
 * 那条路径拿不到 `runWithModel` 内部的 `messageFirstSeen` / `emittedToolEvents`。
 * 但「有没有已经吐出内容」直接决定能不能重试（重试会造成内容重复），
 * 所以用这个小盒子把进度传出来。
 */
private class AgentAttemptProgress {
    @Volatile
    var producedOutput: Boolean = false

    /**
     * v239：最后一次有任何动静（新消息 / 新事件 / 工具进度）的时刻。
     * 静默看门狗靠它判断「这次尝试是不是已经僵住了」。
     */
    @Volatile
    var lastActivityAtMillis: Long = System.currentTimeMillis()

    /** v239：是否由静默看门狗掐掉的（用于区分两种超时的说明文案） */
    @Volatile
    var idleTimedOut: Boolean = false

    /** v245：这次判卡是不是「工具跑太久」造成的（文案要说清，别让人以为模型没吐字） */
    @Volatile
    var toolTimedOut: Boolean = false

    /**
     * v245：正在执行中的工具（按 toolCallId 记）。
     *
     * 工具是同步执行的，执行期间一个数据块都不会来，所以必须把这段时间从「没动静」里
     * 剔除，否则慢工具会被判成卡死（上限见 [AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES]）。
     */
    private val inFlightTools: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** v245：当前这批工具是什么时候开始的（0 表示没有工具在跑） */
    @Volatile
    var toolStartedAtMillis: Long = 0L

    /** v245：是否有工具正在执行 */
    val toolInFlight: Boolean get() = inFlightTools.isNotEmpty()

    /** v245：当前这批工具已经跑了多久 */
    val toolRunningForMillis: Long
        get() = if (toolInFlight && toolStartedAtMillis > 0L) {
            System.currentTimeMillis() - toolStartedAtMillis
        } else {
            0L
        }

    fun markToolCall(toolCallId: String) {
        if (inFlightTools.add(toolCallId) && toolStartedAtMillis == 0L) {
            toolStartedAtMillis = System.currentTimeMillis()
        }
    }

    fun markToolResult(toolCallId: String) {
        inFlightTools.remove(toolCallId)
        if (inFlightTools.isEmpty()) toolStartedAtMillis = 0L
    }

    fun touch() {
        lastActivityAtMillis = System.currentTimeMillis()
    }
}

/**
 * v234：子代理报告（conclusion）的字符上限。
 *
 * 原值 4 * 1024 过小：一份「结论 / 证据 / 不确定项 / 建议」四段式报告，
 * 只要引用几段源码原文就会在第三段附近被砍断。真机实测正是如此。
 * 提到 12K 后大部分报告可完整落库；仍超限时由 [clipAgentReportText] 保留头尾，
 * 并且**必须**把 truncated 置位（见 [GenerationAgentBackend] 的 v234 注释）。
 */
internal const val AGENT_REPORT_MAX_CHARS = 12 * 1024

/**
 * v235：报告里三个列表字段的收束上限。
 *
 * `evidence` / `uncertainties` / `suggestions` 现在会被真正填充（v234 之前恒为空），
 * 所以要给个上界，避免子代理往列表里灌几百条把主线上下文冲垮。
 * 超限同样算「截断」，主线可据此决定要不要让它续写。
 */
internal const val AGENT_REPORT_LIST_MAX_ITEMS = 30
internal const val AGENT_REPORT_LIST_ITEM_MAX_CHARS = 1000

/** v235：报告边界标记。取**最后**一个匹配，避免模型先示范格式再写正文时取错。 */
private val AGENT_REPORT_TAG_REGEX = Regex(
    "<report>(.*?)</report>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/**
 * v235：从子代理的原始输出里剥出 `<report>…</report>` 里的正文。
 *
 * ## 为什么需要
 *
 * 报告取的是「最后一条 assistant 消息的 [UIMessage.toText]」，而 `toText()` 的实现是
 * `parts.joinToString("\n")`：一次回合里模型在工具调用之间说的过渡话和最终报告
 * **都是 Text 片段，会被一视同仁拼在一起**。真机实测：报告开头挂着两句
 * 「Let me verify the 2.4.10 line anchors…」，属于思考过程泄漏。
 *
 * 提示词已要求用标记包裹（见 [buildAgentSystemPrompt]）。这里做提取，
 * **找不到标记就原样返回**——廉价模型不一定听话，回退保证最坏也不比以前差。
 *
 * ## v246：为什么不能死取「最后一个块」
 *
 * 真机事故（本轮抓到的）：一个子代理的任务正好是核实报告标记相关的代码，于是它在
 * **报告正文里引用了 `<report>` 这个字样**（写成 `append("……（<report> 标记没有闭合）")`）。
 * 非贪婪正则取「最后一个匹配」时，起点落在这个被引用的字样上，截出来的是一段
 * **从 JSON 中间开始的碎片** —— 报告开头正好是那句引用后面的 ` 标记没有闭合）")`。
 * 于是 JSON 解析失败、整段碎片被当成 conclusion 交给主模型。
 *
 * 这不是个别情况：只要子代理在报告里提到这个标记（讨论报告格式时很常见），就会中招。
 *
 * 修法：把「第一个开标记 → 最后一个闭标记」这一整段也作为候选，
 * **优先取能真正解析成结构化报告的那一段**；都解析不了才退回 v235 的行为
 * （最后一个非贪婪块），这样「模型先示范格式、再写正式报告」那种情况仍然取到正文。
 */
internal fun extractAgentReportPayload(raw: String): String {
    val matches = AGENT_REPORT_TAG_REGEX.findAll(raw).toList()
    if (matches.isEmpty()) return raw
    // v235 的原行为：最后一个非贪婪块
    val lastBlock = matches.last().groupValues.getOrNull(1)?.trim().orEmpty()
    // v246 新增候选：第一个开标记到最后一个闭标记之间的整段（跨过正文里被引用的标记）
    val greedyBlock = run {
        val open = raw.indexOf("<report>", ignoreCase = true)
        val close = raw.lastIndexOf("</report>", ignoreCase = true)
        if (open < 0 || close <= open) "" else raw.substring(open + "<report>".length, close).trim()
    }
    // 谁能解析成真正的结构化报告就用谁（顺序上仍然偏向 v235 的选择）
    listOf(lastBlock, greedyBlock).firstOrNull { looksLikeAgentReportJson(it) }?.let { return it }
    return lastBlock.ifBlank { raw }
}

/**
 * v246：这段文本是不是一份能解析的结构化报告。
 *
 * 只用于在多个候选块里挑一个（见 [extractAgentReportPayload]），
 * 以及判断「标记没闭合」是不是真的截断了（见 [GenerationAgentBackend.run] 里的 unclosedReport）。
 * 解析失败一律返回 false，绝不抛异常。
 */
internal fun looksLikeAgentReportJson(text: String): Boolean {
    val unfenced = stripJsonFence(text.trim())
    if (!unfenced.startsWith("{")) return false
    return runCatching { AgentReport.decode(unfenced) }.getOrNull() != null
}

/**
 * v251：整段模板占位判定（保守，只拦「整段就是占位文字」的短文本）。
 *
 * 不用「少于 N 字就失败」：短但真实的报告必须能成功。这里只认
 * 「待补充 / 待填写 / 待数据收集 / TODO / TBD / placeholder」这类模板占位——
 * 正文里顺带提到这些词不算（长度超过 [MAX] 就不再判占位）。
 */
internal const val AGENT_PLACEHOLDER_TEXT_MAX_CHARS = 80

internal fun isTemplatePlaceholder(text: String?): Boolean {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return true
    if (t.length > AGENT_PLACEHOLDER_TEXT_MAX_CHARS) return false
    val lower = t.lowercase()
    return listOf(
        "待补充", "待填写", "待完善", "待数据收集", "待数据收集后填写", "待收集数据",
        "占位", "placeholder", "todo", "tbd", "待插入", "待确认", "稍后补充",
    ).any { lower.contains(it) }
}

/**
 * v251：元话语判定 —— 续跑时模型只回了「无需重复 / 上一轮已完成 / 无需再写」这类话。
 *
 * 这类回复没有任何新内容，绝不能覆盖已有的报告。只拦短文本，避免误伤正文。
 */
internal fun isMetaTalkOnly(text: String?): Boolean {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return false
    if (t.length > 120) return false
    val lower = t.lowercase()
    return listOf(
        "无需重复", "不需要重复", "不用重复", "无需再写", "不用再写", "无需继续", "无需续写",
        "上一轮已完成", "上轮已完成", "已经完成", "已经写完了", "前面已足够", "任务已完成",
        "已经提交", "无需再补", "无需补充", "不再重复",
    ).any { lower.contains(it) }
}

/** v251：文本里是否带本地裁剪的省略标记（见 [AGENT_OMISSION_MARKER]）。 */
internal fun containsOmissionMarker(text: String?): Boolean =
    text?.contains(AGENT_OMISSION_MARKER) == true

/**
 * v251：续跑时把「中断前已产出的原文」与「本轮新输出」合并。
 *
 * 规则：
 * 1. 新输出以旧文本尾部开头 → 先去掉重复部分（廉价模型续跑时经常把旧结尾重抄一遍）；
 * 2. **无缝拼接优先**：半截 JSON 就是模型从断点继续写的，中间不能插换行
 *    （`... "evidence": ["e1"]` + `, "uncertainties": []}` 才是合法 JSON）；
 * 3. 无缝拼接拼不出可解析结构（普通文本续写场景）才退回「换行拼接」。
 *
 * 「新输出单独是完整报告」的场景由评估器在合并之前用 REPLACE_COMPLETE 直接替换处理，
 * 不会走到这里 —— 这里只处理「新输出确实是缺失的后缀」的情况。
 */
internal fun mergeResumeText(carried: String, fresh: String): String {
    val head = carried.trim()
    val tail = fresh.trim()
    if (head.isEmpty()) return tail
    if (tail.isEmpty()) return head

    // 1) 新输出以旧文本尾部开头 → 去掉重复（最长公共后缀前缀）
    var overlap = minOf(tail.length, head.length)
    while (overlap > 0 && !head.endsWith(tail.substring(0, overlap))) {
        overlap--
    }
    val deduped = if (overlap > 0) tail.substring(overlap).trim() else tail
    if (deduped.isEmpty()) return head

    // 2) 无缝拼接优先（半截 JSON 续写场景）
    val seamless = head + deduped
    if (looksLikeAgentReportJson(normalizeAgentReportText(extractAgentReportPayload(seamless)))) {
        return seamless
    }
    // 3) 普通文本续写 → 换行拼接
    return head + "\n" + deduped
}

/**
 * v251：报告评估器（纯逻辑，可单测）。
 *
 * 判定顺序刻意按「安全优先」排：
 * 1. 本轮新输出单独就是完整结构化报告 → [AgentReportAssessment.REPLACE_COMPLETE]（直接替换旧稿）；
 * 2. 续跑轮没写新内容 / 只回了元话语 → [AgentReportAssessment.NO_PROGRESS]（保留旧稿）；
 * 3. 合并后是完整、可解析、非占位、未裁剪的结构化报告 → [AgentReportAssessment.COMPLETE]（接尾成功）；
 * 4. 本地裁剪省略标记 → [AgentReportAssessment.REPAIR_REPLACE]（裁剪稿不能靠接尾修）；
 * 5. Provider 截断 / tool_calls / 未闭合且没有完整可解析报告 → [AgentReportAssessment.CONTINUE_SUFFIX]；
 * 6. 占位符 / 无效 JSON → [AgentReportAssessment.REPAIR_REPLACE]；
 * 7. 其余（非续跑轮的纯文本真实报告）→ [AgentReportAssessment.COMPLETE]。
 */
internal fun assessAgentReport(
    carriedText: String,
    freshText: String,
    finishReason: String?,
    isResume: Boolean,
    localTruncated: Boolean,
    /**
     * v254：本次续跑用的模式。接尾（SUFFIX）模式下的收尾判定要更宽一点 —— 见第 7 条规则。
     * 默认 null，既有调用点与旧测试的 5 参调用完全不受影响。
     */
    resumeMode: AgentResumeMode? = null,
): AgentReportAssessment {
    val fresh = freshText.trim()
    // v254：只有「接尾模式 + 确实有断点原文」才走宽松收尾；
    // 修复（REPAIR）模式要的本来就是一份结构化替换稿，行为一个字都不变。
    val suffixMode = resumeMode == AgentResumeMode.SUFFIX && carriedText.isNotBlank()

    // 1) 新输出单独能解析成完整结构化报告 → 直接替换旧稿（绝不与旧稿拼接）
    if (fresh.isNotBlank() && looksLikeAgentReportJson(fresh)) {
        val parsed = parseAgentReport(fresh)
        if (parsed.conclusion.isNotBlank() && !isTemplatePlaceholder(parsed.conclusion)) {
            return AgentReportAssessment.REPLACE_COMPLETE
        }
    }

    // 2) 续跑轮：什么都没写 / 只回了「无需重复」之类的元话语 → 无进展
    if (isResume && (fresh.isBlank() || isMetaTalkOnly(fresh))) {
        return AgentReportAssessment.NO_PROGRESS
    }

    // 3) 合并（去重）后解析
    val merged = mergeResumeText(carriedText, freshText)
    val payload = normalizeAgentReportText(extractAgentReportPayload(merged))
    val mergedReport = parseAgentReport(payload)
    if (looksLikeAgentReportJson(payload) &&
        mergedReport.conclusion.isNotBlank() &&
        !isTemplatePlaceholder(mergedReport.conclusion) &&
        !containsOmissionMarker(payload)
    ) {
        return AgentReportAssessment.COMPLETE
    }

    // 4) 本地裁剪留下的省略标记 → 完整替换修复（不能当简单接尾）
    if (localTruncated || containsOmissionMarker(payload)) {
        return AgentReportAssessment.REPAIR_REPLACE
    }

    // 5) Provider 截断 / tool_calls 收尾 / 报告标记未闭合，且没有完整可解析报告 → 接尾续写
    val modelTruncated = AgentFinishReason.isTruncated(finishReason)
    val unfinished = AgentFinishReason.isUnfinished(finishReason)
    val unclosed = looksLikeUnclosedReport(merged) && !looksLikeAgentReportJson(payload)
    if (modelTruncated || unfinished || unclosed) {
        return AgentReportAssessment.CONTINUE_SUFFIX
    }

    // 6) 占位符 / 无效 JSON（坏 JSON 以 { 开头但解析不出来）→ 完整替换修复
    val looksLikeBrokenJson = payload.trim().startsWith("{") && !looksLikeAgentReportJson(payload)
    if (isTemplatePlaceholder(mergedReport.conclusion) || looksLikeBrokenJson) {
        return AgentReportAssessment.REPAIR_REPLACE
    }

    // 7) 其余（非续跑轮的纯文本真实报告；续跑轮合并后仍不是结构化报告 → 修复更安全）
    //
    // v254：接尾模式例外。能走到这一步，说明「只回元话语」（第 2 条）、「本地裁剪」（第 4 条）、
    // 「截断信号 / 标记未闭合」（第 5 条）、「占位符 / 坏 JSON」（第 6 条）全都不成立 ——
    // 也就是模型确实写了实质内容，只是没用结构化格式包起来。此时再判 REPAIR_REPLACE，
    // 等于「内容都拿到手了却硬要模型重写一遍」，用户看到的仍然是「重新生成」，还多花一趟钱。
    // 所以接尾模式只要合并结果有实质内容就按完整收尾；REPAIR 模式仍要求结构化替换稿。
    if (suffixMode && merged.trim().isNotBlank()) return AgentReportAssessment.COMPLETE
    return if (isResume) AgentReportAssessment.REPAIR_REPLACE
    else AgentReportAssessment.COMPLETE
}

/**
 * v251：从「中断前已产出的原文」里还原一份可落库的旧报告（NO_PROGRESS 时保留它）。
 * 能解析成结构化报告就用结构化的，否则整段作为 conclusion 保留原文，绝不丢内容。
 */
internal fun bestReportFrom(text: String?): AgentReport {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return AgentReport(conclusion = "")
    val payload = normalizeAgentReportText(extractAgentReportPayload(t))
    return if (looksLikeAgentReportJson(payload)) parseAgentReport(payload)
    else AgentReport(conclusion = t)
}

/**
 * v254：推导本次续跑用什么模式 —— 判定依据改为**检查点**（断点完整原文），
 * 不再看 `thread.reportJson`（不升级数据库）。
 *
 * 为什么以检查点为准而不是 reportJson：
 * - reportJson 只在正常收尾分支写入；用户手动停止走的是取消路径，收尾时只改状态与时间，
 *   报告根本没来得及落库。此时 reportJson 为空/无法解码，按它判必然误入
 *   「重新交一份完整报告」（REPAIR）—— 这就是用户真机看到的「继续输出却重新生成」。
 * - 检查点一定在：每次流式更新都会把「已累计的未裁剪原文」覆盖写入 agent_events
 *   （RESUME_CHECKPOINT），停止前写过多少就留多少，它才是「能不能接着写」的真实依据。
 * - 与圆桌对齐：圆桌续跑是「有 continueFrom 就无条件接着写」，没有重新生成分支；
 *   子代理只要断点原文是真实内容，就应当一律接着写，只有检查点本身是废稿才修复。
 *
 * - 检查点为空/空白（旧线程或从未产出内容）→ [AgentResumeMode.REPAIR]：真没内容可接；
 * - 检查点里出现省略标记**不再**判修复（v254 撤掉）：检查点是无损原文，不会自带这个标记，
 *   能命中的只有「模型逐字写了这串字」，那时断点原文是好的，不该退化成重新生成；
 * - 检查点整体是模板占位（待补充之类）→ [AgentResumeMode.REPAIR]：废稿接尾没意义；
 * - 其余（有真实内容的半截输出）→ 一律 [AgentResumeMode.SUFFIX]，从断点接着写。
 */
internal fun resolveAgentResumeMode(
    thread: AgentThread,
    checkpoint: String?,
): AgentResumeMode {
    // 没有检查点（旧线程 / 从未产出任何内容）→ 真没内容可接，只能兼容修复
    if (checkpoint.isNullOrBlank()) return AgentResumeMode.REPAIR

    // v254：以下全部只看检查点。reportJson 在手动停止时是空的（见上方 KDoc），
    // 不能作为「能不能接着写」的判定依据。
    //
    // 刻意**不**检查省略标记：检查点的写入点故意不做任何裁剪（见 runWithModel 里的注释），
    // 它自己永远不可能带上省略标记；真正能命中这一条的只有「模型正好逐字输出了这串字」
    // —— 本项目的子代理经常审查裁剪相关源码，真的会发生。那时断点原文完好无损，
    // 却会退化成「重新交一份完整报告」，与 v246 踩过的「正文引用报告标记被误判成截断」同类。
    val payload = extractAgentReportPayload(checkpoint.trim())
    if (isTemplatePlaceholder(payload)) return AgentResumeMode.REPAIR
    return AgentResumeMode.SUFFIX
}

/**
 * v235：合并连续空行。
 *
 * `toText()` 把非文本片段（工具调用等）换成空字符串再参与拼接，
 * 于是一条含多次工具调用的消息会留下大片空行。这里顺手压掉，纯粹为省上下文。
 */
internal fun normalizeAgentReportText(text: String): String =
    text.lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** v235：剥掉可能存在的 ```json 代码块围栏，模型很爱加这个 */
private fun stripJsonFence(text: String): String {
    if (!text.startsWith("```")) return text
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return text
    val body = text.substring(firstBreak + 1)
    val endFence = body.lastIndexOf("```")
    return (if (endFence >= 0) body.substring(0, endFence) else body).trim()
}

/**
 * v235：尝试把报告正文按 JSON 解析成结构化的四段；失败就整段当 conclusion。
 *
 * [AgentReport.decode] 早就存在（配了 `ignoreUnknownKeys`），只是此前没人喂它 JSON，
 * 三个列表字段被硬编码成空表。结构化之后主线可以**分字段取用**：
 * 例如只吃 `evidence`（带文件行号、可机械核对）而不吃 `suggestions`（主观判断），
 * 这对用廉价模型跑子代理尤其重要。
 *
 * 解析失败不是错误路径，是正常回退。
 */
internal fun parseAgentReport(payload: String): AgentReport {
    val trimmed = payload.trim()
    val unfenced = stripJsonFence(trimmed)
    if (unfenced.startsWith("{")) {
        AgentReport.decode(unfenced)?.let { return it }
    }
    return AgentReport(conclusion = trimmed)
}

/**
 * v235：对整份报告做长度收束，返回「裁剪后的报告」与「是否发生过裁剪」。
 *
 * 第二个返回值会并入 `truncated`，所以任何一处被裁都能让主线看见、并允许续写。
 */
internal fun clipAgentReport(report: AgentReport): Pair<AgentReport, Boolean> {
    var clippedAny = false

    val conclusion = if (report.conclusion.length > AGENT_REPORT_MAX_CHARS) {
        clippedAny = true
        clipAgentReportText(report.conclusion, AGENT_REPORT_MAX_CHARS)
    } else {
        report.conclusion
    }

    fun clipList(items: List<String>): List<String> {
        if (items.size > AGENT_REPORT_LIST_MAX_ITEMS) clippedAny = true
        return items.take(AGENT_REPORT_LIST_MAX_ITEMS).map { item ->
            if (item.length > AGENT_REPORT_LIST_ITEM_MAX_CHARS) {
                clippedAny = true
                clipAgentReportText(item, AGENT_REPORT_LIST_ITEM_MAX_CHARS)
            } else {
                item
            }
        }
    }

    return AgentReport(
        conclusion = conclusion,
        evidence = clipList(report.evidence),
        uncertainties = clipList(report.uncertainties),
        suggestions = clipList(report.suggestions),
    ) to clippedAny
}

/**
 * v218：真实代理后端（接入 GenerationLoop）。
 *
 * 与主会话/圆桌完全解耦：
 * - 独立消息列表（system=角色说明+任务，user=任务），不携带主对话；
 * - 独立助手副本：清空记忆、MCP、技能、正则、模式注入、知识库、网络搜索；
 * - 只读工具集（列目录/查找/搜索/读文件），无任何写/删/执行能力；
 * - 模型默认继承主助手，可用 thread.modelId 覆盖；
 * - 全程只发可观察事件/消息到 AgentDatabase，不进入主会话。
 *
 * v222 四项修复（都是「看不见子代理在干嘛」的真实原因）：
 * 1. **消息不再刷屏**：v221 每来一个流式数据块就 insert 一条全新记录，
 *    一次运行往库里塞几百上千条层层递增的重复消息，界面根本没法看。
 *    现在同一条模型消息用稳定 id 覆盖写入，界面看到的是一条实时增长的消息。
 * 2. **思考过程可见**：v221 用 `toText()` 只取纯文本，Reasoning 与工具调用全被丢掉。
 *    现在用 [renderAgentMessageContent] 保留思考、工具入参与工具结果。
 * 3. **工具轨迹实时**：v221 等整个 collect 结束后才一次性补事件，跑的时候什么都看不到。
 *    现在工具一开始调用就落一条事件，拿到结果再落一条。
 * 4. **识别截断**：读取 Provider 的 finishReason，命中 length/max_tokens/incomplete
 *    时标记 truncated，界面可据此亮出「继续输出」。
 */
class GenerationAgentBackend(
    private val generationLoop: GenerationLoop,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val agentRepository: me.rerere.rikkahub.agent.repo.AgentThreadRepository,
    // v280：只用于「允许子代理检索历史对话」这一个开关打开时装配 conversation 工具；
    // 开关默认关，关着时这个依赖完全不被触碰。
    private val conversationRepository: ConversationRepository,
    // v1027：只用于「允许子代理调用 MCP」这一个开关打开时装配 MCP 工具；
    // 开关默认关，关着时这个依赖完全不被触碰 —— 与上面 conversationRepository 的处理一致。
    private val mcpManager: McpManager,
) : AgentBackend {

    /**
     * v304：上层指定的「这一趟从哪个候选模型开始」（线程 id → 模型 id）。
     *
     * 用途：续跑次数用完时自动换候选模型（见 AgentThreadManager.rotateResumeModel）。
     * 一次性指令 —— 读完即清，避免影响之后再往后的运行。进程内状态，不落库。
     */
    private val preferredStartModels = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun setPreferredStartModel(threadId: String, modelId: String?) {
        if (modelId.isNullOrBlank()) {
            preferredStartModels.remove(threadId)
        } else {
            preferredStartModels[threadId] = modelId
        }
    }

    override suspend fun run(
        thread: AgentThread,
        resume: Boolean,
        onMessage: suspend (AgentMessage) -> Unit,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AgentRunOutcome {
        val settings = settingsStore.settingsFlow.first()

        // v236：备用模型链（保底换人）。
        //
        // v235 的 resolveModel 用 `?:` 一路取第一个非空 id，拿到就用；万一那个模型
        // 正在崩（限流/5xx/连接被重置），自动重试也只是拿**同一个模型**再撞两次，
        // 撞完就彻底停在失败。用户实测反馈的就是这个：「一个模型崩溃之后没有保底机制」。
        // 现在把四层优先级摊平成一条候选链，前面的跑不动就往后换人。
        // v304：上层（AgentThreadManager）在「续跑次数用完 → 自动换候选模型」之后会指定
        // 这一趟从哪个模型开始。一次性指令，读完即清；那个 id 找不到（模型被删 / 线程变了）
        // 就回落整条候选链 —— 绝不能因为一个失效的 id 把候选链清空。
        val preferredStartModelId = preferredStartModels.remove(thread.id)
        val modelChain = resolveModelChain(thread, settings).let { chain ->
            if (preferredStartModelId == null) {
                chain
            } else {
                chain.dropWhile { model -> model.id.toString() != preferredStartModelId }
                    .ifEmpty { chain }
            }
        }
        if (modelChain.isEmpty()) {
            throw AgentModelUnavailableException(
                "子代理模型不可用：${thread.modelId ?: "未指定（继承主助手）"}"
            )
        }

        val history = agentRepository.messages(thread.id)
        // v251：续跑必须优先读「未裁剪的完整原文检查点」，不能再读界面裁剪过的消息。
        // 检查点存 agent_events 表的 detail（事件类型 RESUME_CHECKPOINT、稳定 id 按 threadId），
        // 与普通显示消息完全分离：eventsFlow 已排除它，UI 看不到，messages() 也读不到。
        val checkpoint = if (resume) agentRepository.resumeCheckpoint(thread.id) else null
        // v254：续跑模式由检查点（断点完整原文）推导 —— 不再看 reportJson
        //（手动停止时报告未落库、reportJson 为空，不能作为判定依据）
        val resumeMode = if (resume) resolveAgentResumeMode(thread, checkpoint) else null
        // v324：联网开关只算一次，工具装配与提示词共用同一个值（单一事实来源）。
        //
        // 为什么必须共用：提示词会被生成两次（进 messages 一次、进隔离助手副本一次），
        // 而「给不给工具」在第三处。三处各自判断迟早分叉 —— 分叉的结果就是本轮用户看到的
        // 「手里有工具却自称没工具」和「说能上网却上不了网」。
        //
        // v1027：联网与 MCP 都要求「按派活那个对话」取，所以合并成**一趟**查询，
        // 结果同时喂给两边，不再各查各的。
        //
        // 但只在真的需要时才查：编程位/审查位拿不到联网工具，MCP 又默认关，
        // 这两种情况下读会话纯属白花（v1026 审查专门抓过这条开销回归，别改回去）。
        val needsConversation = thread.role == AgentRole.EXPLORER || settings.allowAgentMcp
        val dispatchConversation = if (needsConversation) threadConversation(thread) else null
        val webSearchEnabled = agentWebSearchEnabled(thread, settings, dispatchConversation)
        val mcpCapability = agentMcpTools(thread, settings, dispatchConversation)
        // v324：先装配，再把**实际装配到的工具名**交给说明书。
        //
        // 顺序很重要：装配在前、说明书在后，说明书照抄结果，就不可能凭空宣称一个没装配的工具。
        // 第一版按布尔写说明书时，审查抓到「Brave 这类不提供抓取参数的服务根本不会装配
        // scrape_web，却被无条件写进说明书」—— 照抄结果以后这类分叉结构上无法出现。
        // MCP 同理：名字来自实际装配结果，被跳过的非法服务器不会出现在说明书里。
        val tools: List<Tool> = buildAgentTools(thread, settings, webSearchEnabled, mcpCapability.tools)
        val webSearchToolNames = tools.map { it.name }.filter { it == "search_web" || it == "scrape_web" }
        val messages = buildAgentInputMessages(
            thread, history, resume, resumeMode, checkpoint,
            memoryEnabled = settings.allowAgentConversationSearch,
            webSearchToolNames = webSearchToolNames,
            mcp = mcpCapability,
        )
        // v241：续跑时把「中断前已经写出来的部分」留住，最后要接在新内容前面。
        //
        // 真机事故（本轮抓到的）：一个子代理续跑 2 次之后，最终报告里只剩**最后一段**的
        // 残片（`"..."], "uncertainties": [], ...}` 这种 JSON 尾巴），前面写的全丢了。
        // 根因是报告提取只取「最后一条 assistant 消息」，而续跑产生的是新的一条。
        // 于是「撞上限 → 自动接着写」这条链看着通了，实际每接一次就丢一段。
        //
        // v251：数据源改成检查点优先。没有检查点的旧线程只能从已裁剪的历史回退，
        // 这是**兼容有损回退**：必须发事件明确说明，且后续按 REPAIR 模式处理，
        // 不得声称找回中间内容。
        val carriedText = if (resume) {
            if (!checkpoint.isNullOrBlank()) {
                checkpoint.trim()
            } else {
                onEvent(
                    AgentEvent(
                        threadId = thread.id,
                        type = "RESUME_LEGACY_FALLBACK",
                        detail = "旧线程没有续跑检查点，只能从已裁剪的历史消息回退（兼容模式，历史可能不完整），" +
                            "本次将按「完整替换修复」处理，不再假装能接尾",
                        createdAt = Instant.now(),
                    )
                )
                history.filter { it.role == "assistant" && it.content.isNotBlank() }
                    .joinToString("\n") { it.content.trim() }
            }
        } else {
            ""
        }

        var lastFailure: Throwable? = null
        // v244：这个设置项的语义已改成「多久没有新内容算卡住」（用户明确要求：
        //「改成多久没进行新发言就判卡死而不是按照总运行时长」）。
        // 总运行时长只保留一条远得碰不到的硬兜底，专防死循环烧钱。
        val idleLimitMinutes =
            settings.agentThreadTimeoutMinutes.coerceAtLeast(0)
        // v245：助手是不是流式输出。
        //
        // 非流式时 GenerationLoop 走的是阻塞式 generateText，一整趟只在**结束的瞬间**
        // 回调一次（真机核查：GenerationLoop 的 else 分支只调一次 onUpdateMessages）。
        // 静默看门狗认的是「多久没有新内容」，在这种模式下必然把正常生成判成卡死，
        // 然后同模型重试、换备用模型，全程重复做同一件事还一直烧钱。
        val streamingOutput = settings.getCurrentAssistant().streamOutput
        val attemptTimeoutMillis = AGENT_ATTEMPT_HARD_DEADLINE_MILLIS
        // v270：用户拍板合并——同模型重试次数直接复用「自动续跑次数」设置
        // （用户原话：「续跑次数没了直接换下个模型就行了，不用额外设置一个按键」）。
        // 该值为 0（不自动续跑）时至少保留首次尝试，否则整条模型链一个都不会跑。
        val modelAttempts = settings.agentAutoResumeMax
            .coerceAtLeast(1)
        modelChain.forEachIndexed { index, model ->
            // v311：事件里一律用「模型名 · 供应商名」。两家供应商放同一个模型名是常见用法，
            // 只印模型名的话换人前后一个字都不差，用户无法判断到底换没换成（真机反馈）。
            val modelLabel = model.displayLabel(settings.providers)
            // v1028：不管是第几个候选模型，一确定就报一次 ——
            // 界面要的是「现在正在用谁」，不能等收尾才看到（用户原话：
            // 「不要等到结束了再给我看目前所使用的模型」）。
            // 走既有 onEvent 通道，与其它事件同一条时间线，事后也能追查这次到底换没换人。
            // 注意：这里与下面的 MODEL_FALLBACK 是两件事 —— 那个只在 index>0 时发。
            onEvent(
                AgentEvent(
                    threadId = thread.id,
                    type = AGENT_EVENT_TYPE_MODEL_ACTIVE,
                    detail = modelLabel,
                    createdAt = Instant.now(),
                )
            )
            if (index > 0) {
                onEvent(
                    AgentEvent(
                        threadId = thread.id,
                        type = "MODEL_FALLBACK",
                        detail = "上一个模型连试 $modelAttempts 次都失败" +
                            "（${(lastFailure?.message ?: "未知错误").take(160)}），" +
                            "自动换用备用模型「$modelLabel」" +
                            "（第 ${index + 1}/${modelChain.size} 个候选，" +
                            "之前产出的内容为空，可以安全重来）",
                        createdAt = Instant.now(),
                    )
                )
            }
            // v238：同一个模型先试 modelAttempts 次，都失败才换下一个。
            // v270：次数复用「自动续跑次数」设置（默认 2），不再单设旋钮（用户拍板合并）。
            for (attempt in 1..modelAttempts) {
                if (attempt > 1) {
                    onEvent(
                        AgentEvent(
                            threadId = thread.id,
                            type = "MODEL_RETRY",
                            detail = "模型「$modelLabel」第 ${attempt - 1} 次失败" +
                                "（${(lastFailure?.message ?: "未知错误").take(160)}），" +
                                "${AGENT_MODEL_RETRY_DELAY_MILLIS / 1000} 秒后用同一个模型再试" +
                                "（第 $attempt/$modelAttempts 次）",
                            createdAt = Instant.now(),
                        )
                    )
                    kotlinx.coroutines.delay(AGENT_MODEL_RETRY_DELAY_MILLIS)
                }
                try {
                    // v238：给这一次尝试套超时。超时算「这次失败」，于是自动接上
                    // 「同模型再试 → 还不行就换下一个模型」的链条。
                    // v239：再加一道「静默看门狗」—— 连接不断但一个字不吐时，
                    // 不必干等整体超时，几分钟没动静就判卡住，接上同一条链条。
                    val progress = AgentAttemptProgress()
                    val idleTimeoutMillis = agentIdleTimeoutMillis(idleLimitMinutes)
                    // v245：非流式时关掉静默看门狗，只留 4 小时硬兜底（见 streamingOutput 注释）
                    val watchdogIdleMillis = if (streamingOutput) idleTimeoutMillis else 0L
                    // v245：工具执行期间不按「没动静」算，但工具自己要有上限
                    // v246：上限改成用户可调（设置项「工具最长执行时间」，默认 10 分钟）
                    val toolWaitCeilingMillis =
                        agentToolWaitCeilingMillis(settings.agentToolTimeoutMinutes)
                    // 每有一点动静就打一次时间戳，静默看门狗才知道它还活着
                    val watchedOnMessage: suspend (AgentMessage) -> Unit = { message ->
                        progress.touch()
                        onMessage(message)
                    }
                    val watchedOnEvent: suspend (AgentEvent) -> Unit = { event ->
                        progress.touch()
                        onEvent(event)
                    }
                    val outcome = runAttemptWithGuards(
                        attemptTimeoutMillis = attemptTimeoutMillis,
                        idleTimeoutMillis = watchdogIdleMillis,
                        toolWaitCeilingMillis = toolWaitCeilingMillis,
                        progress = progress,
                    ) {
                        runWithModel(
                            thread = thread,
                            resume = resume,
                            settings = settings,
                            model = model,
                            messages = messages,
                            tools = tools,
                            webSearchToolNames = webSearchToolNames,
                            mcp = mcpCapability,
                            onMessage = watchedOnMessage,
                            onEvent = watchedOnEvent,
                            progress = progress,
                            carriedText = carriedText,
                            checkpoint = checkpoint,
                            resumeMode = resumeMode,
                        )
                    }
                    if (outcome != null) return outcome
                    // 走到这里只有三种可能：整体超时、静默看门狗判它僵住了，或者工具跑太久
                    val idleMinutes = (idleTimeoutMillis / 60_000L).toInt()
                    // v245：工具跑太久要单独报，别让人以为是模型没吐字
                    val toolCeilingMinutes = (toolWaitCeilingMillis / 60_000L).toInt()
                    // v244：总时长这条闸只剩防死循环的兜底，文案里也要说清它是兜底
                    val hardDeadlineMinutes = (attemptTimeoutMillis / 60_000L).toInt()
                    val timeout = AgentAttemptTimeoutException(
                        minutes = when {
                            progress.toolTimedOut -> toolCeilingMinutes
                            progress.idleTimedOut -> idleMinutes
                            else -> hardDeadlineMinutes
                        },
                        // v242：让错误文案说清到底是「一直没吐字」还是「总时长到了」
                        idle = progress.idleTimedOut,
                    )
                    onEvent(
                        AgentEvent(
                            threadId = thread.id,
                            type = "TIMEOUT",
                            detail = "模型「$modelLabel」" +
                                if (progress.toolTimedOut) {
                                    "调用的工具连续 $toolCeilingMinutes 分钟没有返回结果，按卡死处理"
                                } else if (progress.idleTimedOut) {
                                    "连续 $idleMinutes 分钟没有任何新内容，按卡死处理"
                                } else {
                                    "这一趟连续跑了 $hardDeadlineMinutes 分钟还没结束" +
                                        "（防死循环的兜底上限），按卡死处理"
                                } +
                                if (progress.producedOutput) {
                                    "。已经产出过内容，不重试也不换模型 —— 交给续跑机制从中断处接着写"
                                } else {
                                    "（这次尝试没有任何产出，可以安全重来）"
                                },
                            createdAt = Instant.now(),
                        )
                    )
                    lastFailure = timeout
                    // 已经吐出内容了：重试或换模型都会造成内容重复，交给续跑机制
                    if (progress.producedOutput) throw timeout
                    // v269：用户明确要求「只有到达超时时间没反应才会自动切换其他模型，
                    // 其他情况下继续续跑，直到续跑次数没了再切换其他模型」。
                    //
                    // 超时（含静默看门狗、工具挂死、总时长兜底）说明这个模型/通道此刻
                    // 根本不吐字，在同一个模型上再等一整个超时窗口纯属白等 —— 直接跳出
                    // 内层重试循环，换下一个候选模型。
                    //
                    // 其余所有失败原因（网络断、限流、5xx、认证、余额、模型不存在、参数错、
                    // 解析失败）都不走这里，仍然留在同一模型上把 modelAttempts 次数耗完
                    // 才换人 —— 用户拍板：「报错重试根本用不了多久，也不会继续消耗余额」。
                    break
                } catch (e: AgentAttemptFailure) {
                    lastFailure = e.failure
                    // 已经吐出内容了：重试或换模型都会造成重复/错乱，交给续跑机制处理
                    if (e.producedOutput) throw e.failure
                }
            }
        }
        throw lastFailure ?: AgentModelUnavailableException("子代理所有候选模型都不可用")
    }

    /**
     * v239：给一次尝试同时套两道闸 —— 「整体最多跑多久」和「多久没动静」。
     *
     * 返回 null 表示这次尝试被判卡死（两道闸任意一道触发），调用方据此接上
     * 「同模型再试 → 换下一个备用模型」的链条。
     *
     * 为什么不能只用 `withTimeoutOrNull`：那是死线，跟「有没有在吐字」无关。
     * 中转站那种保持连接却不推数据的上游，会让子代理干等满整个死线（默认 10 分钟）
     * 而界面上什么都看不出来 —— 这正是用户反馈的「好几分钟没有新内容」。
     *
     * 静默看门狗掐掉的是内层的生成协程，不是本协程；本协程照常返回 null。
     * 外层若是真的被取消（用户点停止），异常原样上抛，绝不能被当成「换个模型再试」。
     */
    private suspend fun <T> runAttemptWithGuards(
        attemptTimeoutMillis: Long,
        idleTimeoutMillis: Long,
        toolWaitCeilingMillis: Long,
        progress: AgentAttemptProgress,
        block: suspend () -> T,
    ): T? = kotlinx.coroutines.coroutineScope {
        val work = async { block() }
        val guard = if (idleTimeoutMillis > 0L || toolWaitCeilingMillis > 0L) {
            launch {
                while (true) {
                    kotlinx.coroutines.delay(AGENT_IDLE_CHECK_INTERVAL_MILLIS)
                    val idleFor = System.currentTimeMillis() - progress.lastActivityAtMillis
                    // v245：工具在跑的时候不算「没动静」（工具是同步执行的，那段时间
                    // 一个数据块都不会来），但工具自己有上限，挂死照样要判。
                    if (shouldJudgeStuck(
                            idleForMillis = idleFor,
                            idleTimeoutMillis = idleTimeoutMillis,
                            toolInFlight = progress.toolInFlight,
                            toolRunningForMillis = progress.toolRunningForMillis,
                            toolWaitCeilingMillis = toolWaitCeilingMillis,
                        )
                    ) {
                        progress.toolTimedOut = progress.toolInFlight
                        progress.idleTimedOut = true
                        work.cancel(
                            kotlinx.coroutines.CancellationException(
                                "agent attempt idle timeout"
                            )
                        )
                        break
                    }
                }
            }
        } else {
            null
        }
        try {
            val settled = if (attemptTimeoutMillis > 0L) {
                kotlinx.coroutines.withTimeoutOrNull(attemptTimeoutMillis) { work.await() }
            } else {
                work.await()
            }
            if (settled == null) {
                // 整体死线到了：把生成协程也掐掉，别让它在背后继续烧钱
                work.cancel(
                    kotlinx.coroutines.CancellationException("agent attempt hard timeout")
                )
            }
            settled
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 静默看门狗掐的 → 当作这次尝试卡死；其它取消（用户停止）原样上抛
            if (progress.idleTimedOut && currentCoroutineContext().isActive) {
                null
            } else {
                throw e
            }
        } finally {
            guard?.cancel()
        }
    }

    /**
     * 用某一个具体模型跑一次。
     *
     * 失败时统一包成 [AgentAttemptFailure] 抛出，让上层知道「这次有没有已经产出内容」。
     * 协程取消不包装、原样上抛 —— 用户停止必须立刻生效，绝不能被当成"换个模型再试"。
     */
    private suspend fun runWithModel(
        thread: AgentThread,
        resume: Boolean,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        model: me.rerere.ai.provider.Model,
        messages: List<UIMessage>,
        tools: List<Tool>,
        /** v324：本次实际装配到的联网工具名，由 run() 从 tools 里取；说明书照抄它，不再自行判断 */
        webSearchToolNames: List<String> = emptyList(),
        /** v1027：run() 算好的那份 MCP 能力，整条链只传这一个对象 */
        mcp: AgentMcpCapability = AgentMcpCapability(),
        onMessage: suspend (AgentMessage) -> Unit,
        onEvent: suspend (AgentEvent) -> Unit,
        progress: AgentAttemptProgress,
        /** v241：续跑时中断前已经写出来的内容，最终报告要接在它后面（否则每续一次丢一段） */
        carriedText: String = "",
        /** v251：本轮开始前读到的续跑检查点（未裁剪原文）；null 表示没有（首轮或旧线程） */
        checkpoint: String? = null,
        /** v251：本次续跑采用的模式（suffix / repair），由线程状态推导 */
        resumeMode: AgentResumeMode? = null,
    ): AgentRunOutcome {
        // 独立助手副本：清空一切可能污染子代理上下文的字段
        //
        // v324：这里必须把 webSearchToolNames 一起传下去。这份副本里的 systemPrompt 与
        // messages 里那条 system 是**同一段话生成两次**，两边用的值必须相同，否则说明书
        // 会与手里实际的工具分叉（「有工具却自称没工具」就是这么来的）。
        val assistant = buildIsolatedAssistant(
            settings.getCurrentAssistant(),
            thread,
            memoryEnabled = settings.allowAgentConversationSearch,
            webSearchToolNames = webSearchToolNames,
            // v1027 复审后整条链只传一个对象，串参数/漏传这两类错误结构上不可能再发生
            mcp = mcp,
        )

        var latestMessages: List<UIMessage> = emptyList()
        // 同一条模型消息的首次出现时间（保持列表顺序稳定，避免每次覆盖写都把它顶到最后）
        val messageFirstSeen = mutableMapOf<String, Instant>()
        // 已经落过库的工具事件（toolCallId + 阶段），避免重复写
        val emittedToolEvents = mutableSetOf<String>()
        // v251：输入里已有的 assistant 消息（续跑回灌的检查点/历史）不算「本轮新产出」，
        // 检查点与 freshText 都只累计本轮模型真正新写的内容，避免把历史再拼一遍。
        val inputAssistantIds = messages
            .filter { it.role == MessageRole.ASSISTANT }
            .map { it.id }
            .toSet()

        try {
            generationLoop.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = agentMaxSteps(thread, settings),
                conversationSystemPrompt = null,
                conversationModeInjectionIds = emptySet(),
                conversationLorebookIds = emptySet(),
                workspaceCwd = null,
                memories = null,
                inputTransformers = emptyList(),
                outputTransformers = emptyList(),
                processingStatus = MutableStateFlow(null),
                // v248：子代理的工具返回上限单独调大（用户可设）。
                // 主对话与圆桌仍走上游那个写死的 32KB —— 主对话有终端能把被截断的内容
                // cat 回来，而且主对话的上下文纯净度是要保护的重点；子代理没有终端，
                // 拿不回被截掉的部分，而它的结果只回一份摘要，读多少都不污染主对话。
                toolOutputCharLimit = settings.toolOutputCharLimit,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        latestMessages = chunk.messages
                        // v251：每次 assistant 流式更新后，保存「已累计的完整原文」检查点。
                        // 内容 = 旧检查点（本轮开始前的累计）+ 本轮所有 assistant 文本（toText，未裁剪）。
                        // 刻意不调用 clipAgentMessageContent —— 检查点必须是无损的续跑依据。
                        // 按稳定 id 覆盖写入 agent_events 表，eventsFlow 已排除它，UI 看不到。
                        val freshAccumulated = latestMessages
                            .filter {
                                it.role == MessageRole.ASSISTANT &&
                                    it.id !in inputAssistantIds &&
                                    it.toText().isNotBlank()
                            }
                            .joinToString("\n") { it.toText().trim() }
                        if (freshAccumulated.isNotBlank()) {
                            val checkpointText = listOfNotNull(
                                checkpoint?.takeIf { it.isNotBlank() },
                                freshAccumulated,
                            ).joinToString("\n")
                            onEvent(
                                AgentEvent(
                                    id = resumeCheckpointEventId(thread.id),
                                    threadId = thread.id,
                                    type = AGENT_EVENT_TYPE_RESUME_CHECKPOINT,
                                    detail = checkpointText,
                                    createdAt = Instant.now(),
                                )
                            )
                        }
                        chunk.messages
                            .filter { it.role == MessageRole.ASSISTANT }
                            .forEach { message ->
                                val content = renderAgentMessageContent(message)
                                if (content.isBlank()) return@forEach
                                val stableId = agentMessageId(thread.id, message)
                                val createdAt = messageFirstSeen.getOrPut(stableId) { Instant.now() }
                                // v238：告诉外层「这次尝试已经吐出内容了」，超时后不可再重试
                                progress.producedOutput = true
                                onMessage(
                                    AgentMessage(
                                        id = stableId,
                                        threadId = thread.id,
                                        role = "assistant",
                                        // v249：保留头尾 —— 旧写法只留前 16KB，
                                        // 界面正文会在某个工具入参中间永久冻住（真机反馈）
                                        content = clipAgentMessageContent(content),
                                        createdAt = createdAt,
                                    )
                                )
                            }

                        // 工具事件实时落库：调用一开始就记一条，拿到结果再记一条
                        chunk.messages.flatMap { it.getTools() }.forEach { tool ->
                            val phase = if (tool.isExecuted) "RESULT" else "CALL"
                            val key = "${tool.toolCallId}:$phase"
                            if (!emittedToolEvents.add(key)) return@forEach
                            // v238：工具轨迹也算产出，超时后同样不可重试
                            progress.producedOutput = true
                            // v245：记下「工具开始 / 工具已返回」。
                            // 工具是同步执行的，执行期间一个数据块都不会来，所以这段时间
                            // 必须从「没动静」里剔除，否则慢工具会被判成卡死（真机踩过）。
                            if (tool.isExecuted) {
                                progress.markToolResult(tool.toolCallId)
                            } else {
                                progress.markToolCall(tool.toolCallId)
                            }
                            onEvent(
                                AgentEvent(
                                    id = agentEventId(thread.id, key),
                                    threadId = thread.id,
                                    type = if (tool.isExecuted) "TOOL_RESULT" else "TOOL_CALL",
                                    detail = describeTool(tool),
                                    createdAt = Instant.now(),
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AgentAttemptFailure(
                failure = e,
                producedOutput = messageFirstSeen.isNotEmpty() || emittedToolEvents.isNotEmpty(),
            )
        }

        val lastAssistant = latestMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val finishReason = lastAssistant?.finishReason
        // v241：一次运行里可能产生多条 assistant 消息（多轮工具调用 + 最后动笔），
        // 而续跑更是把内容分散到好几次运行里。只取「最后一条」会把前面写的全丢掉 ——
        // 真机上表现为报告只剩一截 JSON 尾巴。这里改成：中断前的内容 + 本次全部内容。
        // 报告标记的提取仍然取最后一个完整的 <report> 块，所以「前半段被截断、
        // 后半段补上结尾」拼起来正好是一个完整块。
        // v251：freshText 只累计「本轮新产出」（输入里回灌的 assistant 历史不算），
        // 否则续跑时会把检查点/历史再拼一遍，造成重复。
        val freshText = latestMessages
            .filter {
                it.role == MessageRole.ASSISTANT &&
                    it.id !in inputAssistantIds &&
                    it.toText().isNotBlank()
            }
            .joinToString("\n") { it.toText().trim() }
        // v251：只有新输出是确实缺失的后缀时才与检查点合并（mergeResumeText 会去重、
        // 去掉旧文本末尾的半截 JSON 残片）；新输出单独是完整报告时直接替换（见评估器）。
        val rawFinalText = mergeResumeText(carriedText, freshText)

        // v235：报告出口的三级清洗，每一级都有回退，最坏情况不比 v234 差。
        //   1. extractAgentReportPayload：剥出 <report>…</report>，剥不到就用全文
        //      （治「思考过程泄漏」：toText() 会把工具调用之间的过渡语和正式报告拼在一起）
        //   2. normalizeAgentReportText：压掉 toText() 拼接留下的大片空行
        //   3. parseAgentReport：能当 JSON 解析就填满四段，解析失败就整段当 conclusion
        //      （结构化之后主线可只吃带文件行号的 evidence，不吃主观的 suggestions）
        val payload = normalizeAgentReportText(extractAgentReportPayload(rawFinalText))
        val parsed = parseAgentReport(payload)

        // v234：本地长度裁剪也必须算「截断」。
        //
        // 病根记录（真机实测出来的）：此前这里只认「模型侧 finish_reason=length/max_tokens」，
        // 而报告被本地 take(...) 砍掉时不置位。于是整条链一起断：
        //   AgentThreadManager 落库 truncated=false
        //     → AgentThread.canResume 为 false（AgentModels：SUCCEEDED -> truncated）
        //     → AgentThreadManager.resume 直接 return null，界面不出现「继续输出」
        //     → wait_agents 只看到 status=SUCCEEDED 和一份残缺 conclusion
        //   主线于是以为拿到了完整报告。实测：四步任务只回来前两步，状态显示「成功」。
        //
        // 本地裁剪同样置位后，落库 / canResume / TRUNCATED 事件 / 界面按钮全部自动打通，
        // 不需要新增数据库列（truncated 自 v222 起就是 agent_threads 的独立列）。
        //
        // v235：判定范围从「conclusion 单字段」扩到「整份报告」——三个列表字段现在
        // 会被真正填充，任何一处被裁剪都要让主线看见（见 clipAgentReport）。
        val (report, localTruncated) = clipAgentReport(parsed)

        // v251：报告评估（纯逻辑、保守）。核心变化：
        // - 占位符 / 无效 JSON / 本地裁剪省略标记 → REPAIR_REPLACE（一次完整替换修复），不再直接成功；
        // - Provider 截断 / tool_calls / 未闭合且无完整可解析报告 → CONTINUE_SUFFIX（接尾续写）；
        // - 续跑轮只回「无需重复」等元话语 → NO_PROGRESS（保留旧报告，不覆盖）；
        // - 新输出单独是完整结构化报告 → REPLACE_COMPLETE（直接替换旧稿，绝不与旧稿拼接）。
        var assessment = assessAgentReport(
            carriedText = carriedText,
            freshText = freshText,
            finishReason = finishReason,
            isResume = resume,
            localTruncated = localTruncated,
            // v254：把本次续跑模式交给评估器 —— 接尾模式下「有实质内容但没按格式包」
            // 不再触发一轮完整重写（见评估器第 7 条规则）
            resumeMode = resumeMode,
        )
        val finalReport = when (assessment) {
            // 新输出单独成稿 → 用它替换旧报告（替换稿也要过本地裁剪）
            AgentReportAssessment.REPLACE_COMPLETE -> {
                val (replaced, replacedClipped) = clipAgentReport(parseAgentReport(freshText))
                if (replacedClipped) {
                    // 替换稿太长又被本地裁剪 → 不能算成功，改为「需要更紧凑的替换修复」
                    assessment = AgentReportAssessment.REPAIR_REPLACE
                }
                replaced
            }
            // 无进展 → 保留旧报告（管理器在 NO_PROGRESS 分支不覆盖 reportJson）
            AgentReportAssessment.NO_PROGRESS -> bestReportFrom(carriedText)
            else -> report
        }

        // v240：两种「看着成功、其实什么都没拿到」的情况必须当成没写完。
        //
        // 真机实测抓到的现场：只读子代理跑满 20 步上限后停下，
        // finish_reason=tool_calls（模型还想接着调工具），报告一个字都没有，
        // 却因为「不属于截断」被判成 SUCCEEDED + canResume=false ——
        // 用户花了钱、一个字没拿到，界面上连「继续输出」按钮都不出现。
        //
        // 置位 truncated 之后，整条链自动打通：落库 truncated=true →
        // canResume=true → 界面出现「继续输出」、主模型能 resume_agent、
        // v239 的截断自动续跑也会把它接着写完。
        val unfinished = AgentFinishReason.isUnfinished(finishReason)
        val reportEmpty = report.conclusion.isBlank() &&
            report.evidence.isEmpty() &&
            report.suggestions.isEmpty()
        // v245：报告标记只开不闭 = 写到一半被切断（见 looksLikeUnclosedReport）。
        // 这条以前整链漏检：提取回退成全文 → conclusion 非空 → 不判截断 → 不能续写，
        // 主模型于是拿到一份「看着完整」的半截报告。
        //
        // v246 补一道闸：**必须同时「解析不出结构化报告」才算截断**。
        // 真机踩过：子代理在报告正文里引用了 `<report>` 字样（讨论报告格式时很常见），
        // 只看标记会把它误判成截断，白触发一次续跑、还多花一趟钱。
        val unclosedReport = looksLikeUnclosedReport(rawFinalText) &&
            !looksLikeAgentReportJson(payload)
        if (unfinished || reportEmpty || unclosedReport) {
            onEvent(
                AgentEvent(
                    threadId = thread.id,
                    type = "REPORT_INCOMPLETE",
                    detail = buildString {
                        when {
                            reportEmpty -> append("这一轮没有交出任何报告内容")
                            unclosedReport -> append("报告写到一半被切断了（<report> 标记没有闭合）")
                            else -> append("报告已交，但这一轮是以工具调用收尾的")
                        }
                        if (unfinished) {
                            append("（finish_reason=$finishReason，模型还想继续）")
                        }
                        if (unclosedReport && !reportEmpty && !unfinished) {
                            // 这种情况通常是上游断流或输出上限，与步数无关，别给错方向
                            append("；已标记为「没写完」，会保留已写的部分并从断点接着写。")
                        } else {
                            append("；最可能的原因是步数用完了（当前上限 ")
                            append(agentMaxSteps(thread, settings))
                            append(" 步，可在设置里调）。已标记为「没写完」，会从断点接着跑。")
                        }
                    },
                    createdAt = Instant.now(),
                )
            )
        }

        // v251：truncated 由评估结果驱动 —— 只有「需要接尾」或「需要修复」才置位触发自动续跑；
        // 占位/无进展/完整成功都不置位（NO_PROGRESS 由管理器落 FAILED 并保留旧报告）。
        val truncated = when (assessment) {
            AgentReportAssessment.CONTINUE_SUFFIX,
            AgentReportAssessment.REPAIR_REPLACE,
                -> true

            AgentReportAssessment.COMPLETE,
            AgentReportAssessment.REPLACE_COMPLETE,
            AgentReportAssessment.NO_PROGRESS,
                -> false
        }

        return AgentRunOutcome(
            report = finalReport,
            finishReason = finishReason,
            truncated = truncated,
            modelId = model.id.toString(),
            assessment = assessment,
            resumeMode = resumeMode,
        )
    }

    /**
     * v236：工具装配。
     *
     * 默认仍是**纯只读**（与 v218~v235 完全一致）。只有主模型在派发时显式给了
     * 可写白名单，才追加两把受限写工具 —— 权限由白名单决定，不由角色决定，
     * 所以模型自称什么身份都无法给自己提权。
     */
    private suspend fun buildAgentTools(
        thread: AgentThread,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        webSearchEnabled: Boolean,
        mcpTools: List<Tool>,
    ): List<Tool> = withSmallAgentOutputLimit(buildList {
        addAll(createAgentReadOnlyTools(
            thread.workspaceId,
            workspaceRepository,
            settings.toolOutputCharLimit,
        ))
        // 写权限只看 thread.writablePaths（白名单），绝不由角色决定 —— 门禁测试按原文断言这一行
        addAll(createAgentWritableTools(thread.workspaceId, workspaceRepository, thread.writablePaths))
        // v244：只有「探测位」（EXPLORER）能联网搜索，而且要用户开着联网搜索。
        //
        // 用户原话：「只给探测位开」。
        //
        // 为什么只给探测位：探测这一环的本职就是去搜集信息；编程位与审查位只该盯着
        // 代码与事实，给它们联网反而容易跑偏，还多烧额度。
        //
        // v324：联网开关改成按**派出这条子代理的那个对话**取，不再读 getCurrentAssistant()。
        //
        // 为什么要改（真机 bug，用户实测报告「探测位有时能联网有时不能」）：官方 3.1 起
        // 联网开关变成了「每个对话各一份」（ConversationConfig.enableWebSearch，界面口径见
        // ChatPage 里 `conversation.config?.enableWebSearch ?: assistant.enableWebSearch`），
        // 而 ChatService.updateChatSearch 在会话已固定 config 时**只写 config 就直接 return**，
        // 助手上那个 enableWebSearch 压根没被改动。这里读的偏偏是助手开关 ——
        // 于是用户在对话里明明开了联网，子代理却读到一个从未被改过的 false，装不上搜索工具，
        // 只能回一句「本子代理只能读取工作区文件」；反过来助手为 true 而会话为 false 时它又
        // 能上网。同一款软件两种表现，就是用户看到的「有时能、有时不能」。
        //
        // 单一事实来源：这个布尔值由 run() 算一次后传进来，工具装配与提示词共用同一个值。
        // 提示词此前会被生成两次（进 messages 一次、进隔离助手副本一次），两处各自判断迟早分叉。
        //
        // 为什么仍不直接读隔离助手副本：buildIsolatedAssistant 把 enableWebSearch 硬置 false
        //（那份副本只负责生成参数，不参与工具装配），这条红线有测试钉着，不动它。
        //
        // 角色不是子代理自己能改的：它在派发那一刻由主模型指定并落库，子代理无从自我提权
        // —— 与写权限白名单同理，权限一律由派发方给定。
        if (thread.role == AgentRole.EXPLORER && webSearchEnabled) {
            addAll(createSearchTools(settings))
        }
        // v1027：MCP 工具。列表由 agentMcpTools() 按「开关 + 派活那个对话」算好后传进来，
        // 这里只负责装配，不再自行判断开关 —— 判断点越少越不容易与说明书分叉。
        //
        // 与联网那条不同，这里**不按角色过滤**：用户明确要求一个开关管所有子代理角色
        // （探测位/编程位/审查位/默认），风险由开关本身控制，而不是再叠一层角色白名单。
        //
        // 输出仍会经过 withSmallAgentOutputLimit 统一截断 —— MCP 返回的长内容同样受
        // 用户设置的「工具返回上限」约束，不因为换了来源就绕过限制。
        if (mcpTools.isNotEmpty()) {
            addAll(mcpTools)
        }
        // v280：历史对话检索。默认关，只有用户在子代理设置里主动打开才装配。
        //
        // 用户原话：「选a，可以自主选择给子代理记忆的权限。」想解决的是「这个问题以前是不是
        // 碰过」「当初为什么这么定」这类只有翻旧对话才能回答的问题 —— 让子代理自己搜比主模型
        // 代搜有效，因为搜索要按上一次的结果换关键词试，这个来回替代不了。
        //
        // 为什么不复用助手的 enableRecentChatsReference：那是主对话那条路的开关，子代理的隔离
        // 助手副本里它被硬置成 false 且有测试钉着（那道保险守的是共享路径，不动）。这里是**独立
        // 的一个设置项**，跟主对话彻底解耦 —— 关掉这个开关，子代理的行为与 v279 一字节不差。
        //
        // 不按角色区分：探测位、审查位、编程位都可能需要翻历史（例如审查时确认「这个写法当初
        // 为什么这么定」），而权限本身由用户的开关给定，与子代理自称什么身份无关。
        if (settings.allowAgentConversationSearch) {
            addAll(
                createConversationTools(
                    conversationRepo = conversationRepository,
                    assistantId = agentMemoryAssistantId(thread, settings),
                )
            )
        }
    }, settings.toolOutputCharLimit)

    /**
     * v281：历史检索该按**哪个助手**的会话来列。
     *
     * v280 传的是 `settings.getCurrentAssistant().id` —— 那是「界面上此刻选中的助手」，
     * 不是「派出这条子代理的那个对话的助手」。用户派完子代理就切走对话、或者顺手换个助手，
     * 都是常态；那时 `recent_chats` 会去列另一个助手的会话，子代理翻到的是**错的历史**，
     * 而且它没法察觉（返回的确实是一批真实对话）。
     *
     * 改成先按 `thread.conversationId` 查这条线程所属对话的助手；对话已删或 id 不合法时
     * 才回退到当前助手（回退比直接不给工具好：至少还能翻到东西）。
     *
     * `conversation_search` 是全库全文检索、本来就不按助手过滤，不受这里影响。
     */
    private suspend fun agentMemoryAssistantId(
        thread: AgentThread,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): Uuid {
        val fromConversation = try {
            conversationRepository.getConversationById(Uuid.parse(thread.conversationId))
                ?.assistantId
        } catch (e: CancellationException) {
            // 协程取消原样上抛 —— 用户点停止必须立刻生效。
            // 这里**不能**用 runCatching：它连 CancellationException 一起吞，取消信号被咽下去后
            // 协程会接着往下跑（本项目为这条规矩踩过坑，见 runWithModel 一带的注释）。
            throw e
        } catch (e: Exception) {
            // 对话已被删、conversationId 不是合法 Uuid、数据库读失败 —— 都只是「查不到」，
            // 回退到当前助手比直接不给工具好：至少还翻得到东西。
            null
        }
        return fromConversation ?: settings.getCurrentAssistant().id
    }

    /**
     * v324：查这条线程所属对话的**元数据**（不加载消息节点）；查不到返回 null，由调用方决定口径。
     *
     * 为什么不用 getConversationById：它会 loadMessageNodes 把整份历史读进内存，而这里只要
     * assistantId 与 config 两个字段。独立审查抓到这条开销回归 —— 每次派发子代理都按对话长度
     * 付一趟全量读取，长对话上尤其浪费，而且非探测位根本用不上这个判定。
     *
     * 不能用 runCatching 包整段 —— 它连 CancellationException 一起吞，用户点停止的信号
     * 会被咽下去，协程接着往下跑。这条规矩见 [agentMemoryAssistantId] 的注释。
     */
    private suspend fun threadConversation(thread: AgentThread): Conversation? = try {
        conversationRepository.getConversationMetadataById(Uuid.parse(thread.conversationId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // conversationId 不是合法 Uuid、对话已被删、数据库读失败 —— 都只是「查不到」。
        null
    }

    /**
     * v324：联网搜索该按**哪个对话**的开关来给。
     *
     * 这与 [agentMemoryAssistantId] 是同一个坑的两半：那边 v281 已经改成按
     * thread.conversationId 查所属对话，这边当时没跟着改，于是留下
     * 「主对话能搜、探测位不能搜」的错位。口径统一为
     * 「派活那一刻、那个对话的会话视角开关」，与界面上看到的一致。
     *
     * 非探测位直接返回 false：装配红线本来就禁止其他角色拿到搜索工具。
     * conversation 由 run() 查好后传进来（v1027 合并成一趟查询），这里不再自行读库。
     */
    private fun agentWebSearchEnabled(
        thread: AgentThread,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        conversation: Conversation?,
    ): Boolean {
        if (thread.role != AgentRole.EXPLORER) return false
        return agentWebSearchEnabledFrom(conversation, settings)
    }

    /**
     * v1027：按开关与**派活那个对话**的会话视角，装配子代理可用的 MCP 工具。
     *
     * 三条规矩，缺一不可：
     * 1. 开关关着 → 返回空列表，一个都不装，行为与 v1026 一字节不差。
     *    这个开关默认就是关的：MCP 会真实调用外部服务，可能写文件、改外部数据、产生费用，
     *    而且它**不受**子代理那层「不能执行命令/不能编译/不能删除」的约束，
     *    属于必须由用户主动放开的权限，不能默认给。
     * 2. 查不到派活对话 → 同样返回空（失败关闭）。与联网那条同理：
     *    拿不准用户意图时一律不放权，绝不拿「此刻界面上选中的助手」凑数。
     * 3. 能用哪些服务器仍由助手上的勾选决定 —— 这里只负责「要不要交给子代理」，
     *    不新增、不放宽、不绕过助手上的 MCP 白名单。
     *
     * 与主对话那条路的两处差异（都是刻意的）：
     * - 主对话遇到非法服务器名直接抛 InvalidMcpServerNamesException；子代理里抛会把整趟
     *   任务炸掉而用户看不见原因，这里改成跳过该服务器、少装一个工具。
     *   说明书是按实际装配结果写的，被跳过的工具不会出现在提示词里，
     *   所以不会出现「说明书说有、手里没有」—— 那正是 v324 修掉的那类分叉。
     * - 主对话把 MCP 工具混在 ChatToolFactory 里；这里单独装配，开关关掉时连 mcpManager
     *   都不碰，保证默认路径零影响。
     */
    private fun agentMcpTools(
        thread: AgentThread,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        conversation: Conversation?,
    ): AgentMcpCapability {
        val assistant = agentMcpAssistantFor(settings, conversation) ?: return AgentMcpCapability()
        // v1027 复审后：谁能挂、叫什么、什么被跳过，全部由纯函数 decideAgentMcpTools 决定；
        // 这里只把留下来的东西造成 Tool 对象。判断点只有一处，装配与说明书共用同一份结果，
        // 结构上不可能出现「说明书说有、手里没有」（v324 那类分叉）。
        //
        // 🔴 第七轮双审抓到的越权入口 4：这里原先喂的是 getAllAvailableTools，
        // 那个方法已经把 tool.enable=false 的记录滤掉了 —— 于是「禁用+要求批准」那份配置
        // 到不了决策函数，决策函数里「任一配置要求批准 → 整条身份不挂」看不到它，
        // 「启用+不要求批准」那份就照常挂上执行。函数内部守得再严也没用，因为东西
        // 在送过来之前就少了。现在改走子代理专用的纯枚举 agentMcpToolRecords：
        // 它在**任何过滤之前**先按 serverId 判重（入口 5/6），并且不过滤工具级 enable
        // （服务器启用与勾选这两道边界仍然保留）。
        // 主对话那条 getAllAvailableTools 一字未动 —— 那边「禁用就不出现」才是对的。
        // 枚举返回「记录 + 因重复 serverId 被整台放弃的台数」，两个都必须交给决策，
        // 否则计数就变成只有注释在说的事。
        //
        // 🔴 第九轮审查抓到的**快照不一致**：原先这里调 mcpManager.getAllToolRecordsForAgent，
        // 而那个方法自己读实时 store（settingsFlow.value），assistant 却来自 run()
        // 开头那份 settings 快照 —— 服务器清单与勾选集合来自**不同时刻**。
        // 期间用户改勾选的话，就会拿「旧勾选」去筛「新服务器表」，
        // 已经撤销勾选的服务器可能仍然被挂上来。MCP 会真的动外部世界，不能这样。
        // 现在两半都从同一个 settings 取，纯枚举不碰 store。
        val records = agentMcpToolRecords(settings.mcpServers, assistant.mcpServers)
        val decision = decideAgentMcpTools(records.entries, records.ambiguousServerCount)

        // 🔴 入口 7（时序版）：装配只保证「那一刻」是安全的，执行发生在几分钟之后。
        // 所以每次真正调用外部服务器之前，都用**当时最新**的设置重新问一遍：
        // 这台服务器还在吗、还是唯一一份吗、还启用吗、还在勾选里吗、
        // 这个工具还启用吗、有没有被改成需要用户批准。
        // 任何一条不满足就**拒绝执行**，把原因作为工具结果交给模型 ——
        // 复查本身不抛异常（`call` 自己失败仍上抛，由 GenerationLoop 既有 runCatching 兜住；
        // 协程取消原样上抛），也绝不在情况已经变了的情况下动外部世界。
        //
        // 勾选维度只在「勾选来自助手」时检查；勾选来自这个对话自己的 config 时
        // 不查库拿不到实时值，硬比会误拒正常配置，此时传 null 跳过该维度（已如实登记）。
        val selectedFromAssistant = conversation?.config?.mcpServers == null
        val mcpAssistantId = assistant.id
        val tools = decision.kept.map { kept ->
            buildAgentMcpTool(kept) { id, toolName, args ->
                val live = settingsStore.settingsFlow.value
                val liveSelected = if (selectedFromAssistant) {
                    live.getAssistantById(mcpAssistantId)?.mcpServers ?: emptySet()
                } else {
                    null
                }
                // 第十轮补强：复查与执行之间不留缝。
                // 抽成 runAgentMcpToolGuarded 的目的，是让「执行前情况变了 → 外部调用一次都没发生」
                // 这件事有**行为断言**，而不是只有一行注释自称守住了。
                runAgentMcpToolGuarded(
                    servers = live.mcpServers,
                    serverId = id,
                    toolName = toolName,
                    args = args,
                    liveSelectedServerIds = liveSelected,
                    // 第十一轮：总开关也必须复查 —— 用户运行中途关掉它，下一句就不许再外发。
                    // 刻意取实时值（live 上的开关字段），而不是 run() 开头那份旧快照里的：
                    // 那份是装配期的设置，用户可能已经改过。
                    agentMcpAllowed = live.allowAgentMcp,
                    call = { guardedId, guardedTool, guardedArgs ->
                        mcpManager.callTool(guardedId, guardedTool, guardedArgs)
                    },
                )
            }
        }
        return AgentMcpCapability(tools = tools, decision = decision)
    }


    /**
     * v236：步数上限按有没有写权限分开给。
     * v241：改成用户可调（设置里的「子代理单趟步数」），常量只作为默认值。
     *
     * 纯调研（只读）默认 32 步；编程位取它的 1.5 倍 —— 读文件 → 改一处 → 再读确认 →
     * 再改下一处，一轮就吃掉好几步。
     *
     * 上限存在的唯一理由是防止模型陷进死循环反复烧钱，**不是**为了截断结果：
     * 撞到上限会被标成「这趟没跑完」，然后从断点接着跑（见 run() 里的 REPORT_INCOMPLETE）。
     */
    private fun agentMaxSteps(
        thread: AgentThread,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): Int {
        // v1024：0不限，正数按用户填写；写权限仍沿原1.5倍，但不能先溢出。
        return agentStepBudget(settings.agentMaxSteps, thread.canWrite)
    }
}

/**
 * v236：把「四层模型优先级」摊平成一条候选链。
 * v237：在链子里插入用户显式指定的备用模型；同时挪成顶层函数，让它能被真正单元测试。
 *
 * 顺序是：主模型显式指定 > 角色级覆盖 > 子代理默认模型 > **备用模型（v237）** > 主助手模型。
 * 差别在于 v235 只取第一个能用的就结束，而这里全部保留 —— 前面的模型跑挂了
 * 就往后换人。去重按模型 id，避免同一个模型被连试好几遍白烧配额。
 *
 * ## v237 为什么必须加「备用模型」这一层
 *
 * v236 的注释原话是「这条链不需要任何新设置：主助手模型天然就是保底」。
 * 这个判断是错的，用户实测打回来了：默认配置下（子代理模型=跟随主助手、
 * 角色=跟随默认、主模型派活时没点名），四层里前三层全是 null，
 * 候选链去重后**只剩 1 个模型**。而 [GenerationAgentBackend.run] 里换人的代码写在
 * `if (index > 0)` 里，链子只有 1 节时这个条件恒假 —— 换人代码一次都进不去，
 * 「备用模型保底」等于没做，用户看到的仍然是「一个模型崩了就直接失败」。
 *
 * v237 让用户能在设置里显式指定 1~3 个兜底模型（`Settings.agentFallbackModelIds`），
 * 链子至少 2 节，换人才真的会发生。设置界面同时把这条链原样显示出来、
 * 只有 1 节时明确警告，避免用户再次以为配好了其实没配。
 *
 * ## 为什么是顶层函数而不是类成员
 *
 * 它只读 [thread] 与 [settings]，没有任何实例状态。放在类里的代价是：
 * 单元测试必须先造出 GenerationLoop / SettingsStore / WorkspaceRepository
 * 三个需要 Android 环境的真实对象，本项目又没有 mock 框架（只有 junit），
 * 于是 v236 的门禁只能退化成「grep 源码字符串」——**候选链的实际输出从未被执行验证过**，
 * 「默认配置下塌成 1 个模型」这个真实缺陷因此一路漏到用户手里。
 * 挪成顶层之后可以直接喂 Settings 断言返回值，见 AgentFallbackModelPickerTest。
 */
internal fun resolveModelChain(
    thread: AgentThread,
    settings: me.rerere.rikkahub.data.datastore.Settings,
): List<me.rerere.ai.provider.Model> {
    val candidateIds = buildList {
        thread.modelId?.let { add(it) }
        settings.agentRoleModelOverrides[thread.role.name.lowercase()]?.let { add(it.toString()) }
        settings.agentModelId?.let { add(it.toString()) }
        // v237：用户显式指定的备用模型（最多 3 个），排在主对话模型之前。
        settings.agentFallbackModelIds.forEach { add(it.toString()) }
        add(settings.chatModelId.toString())
    }
    return candidateIds
        .distinct()
        .mapNotNull { raw ->
            runCatching { kotlin.uuid.Uuid.parse(raw) }.getOrNull()
                ?.let { settings.findModelById(it) }
        }
        .distinctBy { it.id }
}

/**
 * v222：把一条 UIMessage 渲染成子代理界面可读的文本。
 *
 * 为什么不能用 `UIMessage.toText()`：它只 join Text part，
 * Reasoning（思考）与 Tool（工具调用/结果）会被整段丢弃，
 * 用户因此完全看不到子代理在干什么（v221 的真实缺陷）。
 */
/**
 * v234：报告文本超长时保留**头尾**，而不是一刀切掉尾部。
 *
 * 结构化报告的结论都在末尾。本轮真机实测：一份四步任务的报告被 4KB 上限砍断后，
 * 丢掉的恰好是第四步「合并建议」——最有价值的那段，而前两步完好无损。
 * 因此超限时按「头 70% + 尾 30%」保留，中间明示省略了多少字符。
 *
 * 返回值长度保证不超过 [maxChars]（省略标记按上界预留）。
 */
internal fun clipAgentReportText(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text
    val markerOf: (Int) -> String = { omitted ->
        "\n\n…（中间省略 $omitted 字符，完整过程见子代理线程）…\n\n"
    }
    // 用 text.length 估长度是安全上界：实际省略数一定小于总长，位数不会更多
    val reserve = markerOf(text.length).length
    val budget = (maxChars - reserve).coerceAtLeast(0)
    if (budget == 0) return text.take(maxChars)
    val headLen = (budget * 7) / 10
    val tailLen = budget - headLen
    val head = text.take(headLen)
    val tail = text.takeLast(tailLen)
    return head + markerOf(text.length - headLen - tailLen) + tail
}

/**
 * v249：子代理界面消息的显示上限。
 *
 * ## 修的是什么真机故障
 *
 * v222 起这里只做前缀截取，只留**前** 16KB，多出来的静默丢弃、
 * 界面上一个字都不提。真机反馈（用户附了截图）：详情页正文停在
 * 「【工具】workspace_search_text 入参：…/agent/Ge」这样一个被切在半路的位置，
 * 用户以为「一直卡在这个工具这里」，而顶部的「没动静」显示 0 秒。
 *
 * 两个现象其实都是真的：模型确实一直在吐字（所以 touchActivity 一直把「没动静」归零），
 * 但**界面正文早已被 16KB 上限冻住**，之后写的全被砍掉 —— 于是「还在跑」和「卡住」
 * 在界面上长得一模一样，用户根本无法判断要不要干预。
 */
internal const val AGENT_MESSAGE_DISPLAY_MAX_CHARS = 32 * 1024

/**
 * v249：超长时保留「头 40% + 尾 60%」，中间明示省略了多少字符。
 *
 * 与 [clipAgentReportText] 的比例刻意不同：报告的价值集中在结论（末尾），
 * 而过程正文里用户最需要的是「它现在写到哪了」，所以**尾部必须留住**、给得更多。
 * 返回值长度保证不超过 [maxChars]（省略标记按上界预留）。
 *
 * 只作用于子代理界面显示，与主对话、圆桌无关。
 */
internal fun clipAgentMessageContent(
    text: String,
    maxChars: Int = AGENT_MESSAGE_DISPLAY_MAX_CHARS,
): String {
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text
    val markerOf: (Int) -> String = { omitted ->
        "\n\n…（中间省略 $omitted 字符；界面只显示头尾，完整过程仍在模型上下文里）…\n\n"
    }
    val reserve = markerOf(text.length).length
    val budget = (maxChars - reserve).coerceAtLeast(0)
    if (budget == 0) return text.takeLast(maxChars)
    val headLen = (budget * 4) / 10
    val tailLen = budget - headLen
    return text.take(headLen) + markerOf(text.length - headLen - tailLen) + text.takeLast(tailLen)
}

internal fun renderAgentMessageContent(message: UIMessage): String = buildString {
    message.parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> {
                val text = part.text.trim()
                if (text.isNotEmpty()) {
                    appendLine(text)
                    appendLine()
                }
            }

            is UIMessagePart.Reasoning -> {
                val reasoning = part.reasoning.trim()
                if (reasoning.isNotEmpty()) {
                    appendLine("【思考】")
                    appendLine(reasoning.take(8 * 1024))
                    appendLine()
                }
            }

            is UIMessagePart.Tool -> {
                appendLine("【工具】${part.toolName}")
                val input = part.input.trim()
                if (input.isNotEmpty() && input != "{}") {
                    appendLine("入参：${input.take(600)}")
                }
                if (part.isExecuted) {
                    val output = part.output
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                        .trim()
                    if (output.isNotEmpty()) {
                        appendLine("结果：${output.take(1200)}")
                    } else {
                        appendLine("结果：（无文本输出）")
                    }
                } else {
                    appendLine("状态：执行中…")
                }
                appendLine()
            }

            else -> Unit
        }
    }
}.trim()

/** v222：同一条模型消息在 AgentDatabase 中的稳定 id（用于覆盖写而不是不断追加） */
internal fun agentMessageId(threadId: String, message: UIMessage): String =
    "$threadId:${message.id}"

/** v222：工具事件的稳定 id（同一工具同一阶段只落一条） */
internal fun agentEventId(threadId: String, key: String): String = "$threadId:$key"

/** v222：工具事件的可读描述 */
internal fun describeTool(tool: UIMessagePart.Tool): String = buildString {
    append(tool.toolName)
    val input = tool.input.trim()
    if (input.isNotEmpty() && input != "{}") {
        append(" ")
        append(input.take(200))
    }
    if (tool.isExecuted) {
        val output = tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
            .trim()
        if (output.isNotEmpty()) {
            append(" → ")
            append(output.take(300))
        }
    }
}

/**
 * v222：构造子代理的输入消息列表。
 *
 * 首次运行：system + 任务 + 历史补充指令（与 v221 行为一致）。
 * 续跑：优先回灌**未裁剪的完整检查点**（不按条数/字符数裁头尾），
 *      末尾追加 [buildResumeInstruction] 让模型按模式（接尾/修复）继续；
 *      没有检查点的旧线程才回退到历史消息（按 [RESUME_HISTORY_MAX_CHARS] 既有
 *      限额截断），并追加「兼容模式，历史可能不完整」的说明。
 */
internal fun buildAgentInputMessages(
    thread: AgentThread,
    history: List<AgentMessage>,
    resume: Boolean,
    resumeMode: AgentResumeMode? = null,
    checkpoint: String? = null,
    memoryEnabled: Boolean = false,
    webSearchToolNames: List<String> = emptyList(),
    mcp: AgentMcpCapability = AgentMcpCapability(),
): List<UIMessage> {
    val system = UIMessage.system(
        buildAgentSystemPrompt(thread, memoryEnabled, webSearchToolNames, mcp),
    )
    if (!resume) {
        val followUps = history.filter { it.role == "user" }.map { UIMessage.user(it.content) }
        return buildList {
            add(system)
            add(UIMessage.user(thread.task))
            addAll(followUps)
        }
    }

    // v251：续跑模式（推导不出来时按 REPAIR 兜底 —— 宁可完整重写也不要拿残缺历史接尾）
    val mode = resumeMode ?: AgentResumeMode.REPAIR

    // v251：有检查点 → 完整原文直接回灌，不做任何裁剪（绝不能把检查点裁成头尾）。
    // 同时把历史里由 send_agent_message / resume(extraInstruction) 写入的 user 补充指令
    // 一并带上（契约：这些补充指令在下次重跑时生效），顺序为
    // system -> task -> checkpoint(assistant) -> user 补充指令（按落库顺序）-> 本轮唯一的续跑指令。
    // 内容恰好等于续跑指令模板的 user 消息不算补充指令，过滤掉，避免把旧指令当历史重复加入。
    if (!checkpoint.isNullOrBlank()) {
        val followUps = history
            .filter { it.role == "user" && it.content.isNotBlank() && !isResumeInstructionText(it.content) }
            .map { UIMessage.user(it.content.trim()) }
        return buildList {
            add(system)
            add(UIMessage.user(thread.task))
            add(UIMessage.assistant(checkpoint.trim()))
            addAll(followUps)
            add(UIMessage.user(buildResumeInstruction(mode)))
        }
    }

    // v251：旧线程无检查点 → 兼容有损回退（按既有限额裁剪历史），并明确说明不完整
    // 从后往前取，保证「最近的进展」一定在上下文里
    val recent = ArrayList<AgentMessage>()
    var budget = RESUME_HISTORY_MAX_CHARS
    for (message in history.asReversed()) {
        if (recent.size >= RESUME_HISTORY_MAX_MESSAGES) break
        if (budget - message.content.length < 0 && recent.isNotEmpty()) break
        budget -= message.content.length
        recent.add(message)
    }
    recent.reverse()

    return buildList {
        add(system)
        add(UIMessage.user(thread.task))
        recent.forEach { message ->
            if (message.role == "user") {
                add(UIMessage.user(message.content))
            } else {
                add(UIMessage.assistant(message.content))
            }
        }
        add(
            UIMessage.user(
                "【兼容模式】这条旧线程没有完整的续跑检查点，下面的历史消息可能已被界面裁剪，中间内容无法恢复。" +
                    "请按修复模式重新交一份完整、紧凑、可解析的报告，不要试图接着残缺的历史往下写。\n\n" +
                    buildResumeInstruction(mode)
            )
        )
    }
}

/**
 * v324：子代理联网工具的开关口径 —— 与主对话同源（纯函数，便于行为测试）。
 *
 * 走 `Settings.getAssistantOf(conversation)`：那是主对话一直在用的同一条读取路径
 * （getStoredAssistantOf + withConversation），**不是**给子代理另起一套判断。
 * 会话已固定 config 时它取 config 里的开关，没固定时才取助手自身的值 ——
 * 与 ChatPage 上 `conversation.config?.enableWebSearch ?: assistant.enableWebSearch`
 * 完全等价。
 *
 * 查不到对话时**失败关闭**（返回 false），不回退到当前助手。
 *
 * 为什么这里不能照搬 v281 那句「回退比直接不给工具好」：那条说的是查历史对话 ——
 * 读的是用户自己的数据，不外发、不花钱，少给一次能力只是查不到。联网不一样，
 * 它会把查询发出去、并按供应商规则消耗搜索额度。在「不知道派活那个对话开没开」的情况下
 * 拿「此刻界面上恰好选中的助手」当依据，等于用**无关对话**的开关替用户决定要不要外发和花钱 ——
 * 而那恰恰是本轮要消灭的错位本身（审查两路都抓到了这条）。所以口径拿不准时一律不给。
 */
internal fun agentWebSearchEnabledFrom(
    conversation: Conversation?,
    settings: me.rerere.rikkahub.data.datastore.Settings,
): Boolean = conversation?.let { settings.getAssistantOf(it).enableWebSearch } ?: false

/**
 * v1027：子代理这次该按**哪个助手**去枚举 MCP 工具；返回 null 表示一个都不给。
 *
 * 三条给不出来的路，全部是刻意失败关闭：
 * 1. 开关关着（默认就是关）—— 行为与 v1026 一字节不差；
 * 2. 查不到派活那个对话，或那个对话所属的助手已被删除 —— MCP 会真实调用外部服务、
 *    可能改外部数据或产生费用，拿不准用户意图时一律不放权。
 *    🔴 这条与联网那一路**故意不同**：联网走 getAssistantOf 的当前助手回退（与主对话
 *    同源，v324 定的口径），MCP 则在回退之前先查助手是否还存在，不存在就直接不给 ——
 *    拿别的助手勾的服务器放权是越权，不是同源。
 * 3. 助手上本来就没勾任何 MCP 服务器 —— 由返回的 mcpServers 为空自然体现，
 *    枚举出来就是空列表。
 *
 * 走 Settings.getAssistantOf(conversation)：与主对话同一条读取路径（会话视角）。
 * 会话固定过 config 时取 config 里的 mcpServers，没固定时才取助手自身的值 ——
 * 这样「你在某个对话里勾了哪些 MCP」就是子代理看到的范围，不随界面切换漂移。
 */
internal fun agentMcpAssistantFor(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    conversation: Conversation?,
): me.rerere.rikkahub.data.model.Assistant? {
    if (!settings.allowAgentMcp) return null
    val conv = conversation ?: return null
    // v1027 复审修正：getAssistantOf 内部是 getStoredAssistantOf，助手被删除时
    // 会**退回此刻选中的助手**。对联网那一路这是「与主对话同源」，但对 MCP 不行 ——
    // 那等于用另一个助手勾的服务器去调外部服务、还可能改外部数据或产生费用，
    // 属于真实越权。这里先确认会话所属助手还在，不在就失败关闭。
    if (settings.getAssistantById(conv.assistantId) == null) return null
    return settings.getAssistantOf(conv)
}

/**
 * 与主对话 ChatToolFactory 同一套字符集规则，避免两边对「合法服务器名」判断不一致。
 *
 * v1027 复审后从类成员改成顶层函数：纯决策函数也要用它，而规则只许存在一份
 * （主对话那份在 ChatToolFactory，两边由 AgentMcpAccessTest 钉住字面一致）。
 */
internal fun isMcpServerNameUsable(name: String): Boolean =
    name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

/**
 * 工具名长度上限。
 *
 * 🔴 为什么必须有：全项目没有任何地方限制工具名长度，名字是原样发给供应商的
 * （ChatCompletionsAPI 里 put("name", tool.name)）。OpenAI 与 Claude 的函数名上限都是 64，
 * Gemini 是 63 且不接受连字符。「服务器名 + 工具名」由用户和外部服务器自己决定，
 * 拼成 mcp__…__… 再加撞名区分码很容易越过上限 —— 一旦被上游判非法，
 * **整个请求会被拒**（不只是这个工具用不上），用户看到的就是「莫名其妙发不出去」。
 *
 * 取 63 的依据（本轮已联网核实到原文，不再是「通行说法」）：
 * - OpenAI：官方 SDK 规格原文 `Must be a-z, A-Z, 0-9, or contain underscores and
 *   dashes, with a maximum length of 64`。
 * - Claude：官方 API 400 报错原文 `^[a-zA-Z0-9_-]{1,64}$`。
 * - Gemini：老版 protos 写 **63**；新版 SDK 文档写 128；但线上实际 400 报错是
 *   `maximum length of 64`。三家取最小值 → 63。新版文档的 128 不可依赖。
 *
 * 字符集同样必须管：OpenAI/Claude 只接受 `[a-zA-Z0-9_-]`，Gemini 要求以字母或下划线开头。
 * 而 MCP 服务器自己给的工具名可以带点号、空格甚至中文（公开案例里 `codesys.project.open`
 * 这种就曾被 Claude 直接 400）。所以我们拼名字时一律先清洗成安全字符，
 * 执行时仍用**原始** serverId + 工具名，模型看到的名字与真正调用的目标不是一回事。
 *
 * 为什么宁可「不挂并说清原因」：一个名字不合法或超长，上游拒的是**整个请求**，
 * 爆炸半径是整条对话，不是某一个工具。
 */
internal const val MAX_MCP_TOOL_NAME_LENGTH = 63

/**
 * 挂给模型的 MCP 工具描述。
 *
 * 名字被清洗或被编号过的时候，必须把「实际调用的是哪台服务器的哪个原始工具名」
 * 写进模型真正看得见的 description 里 —— 光存在 decision 对象里不算送到，
 * 这一步原本只有我看代码确认，现在抽成纯函数由测试钉住。
 */
internal fun agentMcpToolDescription(kept: AgentMcpKeptTool): String =
    if (kept.identityNote == null) {
        kept.tool.description ?: ""
    } else {
        listOfNotNull(kept.tool.description?.takeIf { it.isNotBlank() }, kept.identityNote)
            .joinToString(" ｜ ")
    }

/**
 * 把一个决策保留项装配成模型真正看得见的 Tool 对象。
 *
 * 🔴 第八轮审查点名：这一层以前只有源码字面量断言，没有行为证据 ——
 * 而「 displayName 可能被清洗或加过区分码，执行却必须打回原始目标」这条承诺
 * 恰恰落在这里。抽成可注入 call 的窄函数之后，测试能真的调用这些闭包，逐项验：
 *  - parameters 返回**代表记录**的 inputSchema
 *  - needsApproval 如实传递，不许写死 false（写死等于替用户自动批准外部操作）
 *  - execute 用**原始 serverId + 原始工具名**调用，绝不从 displayName 反推
 *
 * call 由调用方注入，所以本函数不碰 mcpManager，可被单测直接调用。
 */
internal fun buildAgentMcpTool(
    kept: AgentMcpKeptTool,
    call: suspend (serverId: Uuid, toolName: String, args: kotlinx.serialization.json.JsonObject) -> List<UIMessagePart>,
): Tool = Tool(
    name = kept.displayName,
    description = agentMcpToolDescription(kept),
    parameters = { kept.tool.inputSchema },
    // 保留原表达式而不是写死 false：万一过滤条件以后被改动，
    // 审批标志仍然如实传递，不会悄悄变成「全部自动批准」。
    needsApproval = { kept.tool.needsApproval },
    // 🔴 路由用决策里带的 serverId 与**原始**工具名，绝不从名字反推 ——
    // 撞名加过区分码之后，名字已经不是原始 (服务器名, 工具名) 了。
    execute = { call(kept.serverId, kept.tool.name, it.jsonObject) },
)

/**
 * v1027 第十轮：**执行前**的最后一道门。
 *
 * ## 为什么非要有这一层
 *
 * 装配（[decideAgentMcpTools]）只证明「几分钟前那一刻」是安全的。执行发生在之后，
 * 中间用户完全可能：停用服务器、取消勾选、删掉配置、再加一份同 id 的配置、
 * 或者把某个工具改成「需要用户批准」。这些都是真实的界面操作，不是假想。
 * MCP 会动外部世界（改文件、下单、发消息），所以「拿旧结论去执行新状态」不可接受。
 *
 * ## 为什么抽成独立函数
 *
 * 上一版把判断直接内联在装配 lambda 里，测试只能断言「源码里有这句话」——
 * 而审查位明确指出：那**不是**行为证据。抽出来之后，测试可以注入一个会记账的
 * `call`，然后证明「情况变了 → call 计数为 0」，以及「一切没变 → 恰好调用 1 次、
 * 参数原样传出」。生产路径与测试走的是同一个函数，不是另写一份仿制品。
 *
 * ## 边界（照实写，不许再写成「全防」）
 *
 * - **复查本身不抛异常**：拒绝执行时把原因作为工具结果返回，让子代理写进报告，
 *   而不是炸掉整趟任务。但 `call` 自己失败仍然会往上抛，由 GenerationLoop 既有的
 *   runCatching 兜住（协程取消原样上抛，不在这里吞掉）。
 * - 拒绝时**一个字节都不外发**：`call` 在拒绝分支里根本不会被调用。
 * - 不改 `needsApproval` 语义：需要批准就是拒绝，绝不替用户自动放行。
 * - 总开关（`allowAgentMcp`）也复查：用户运行中途撤权，下一句就不许再外发。
 * - **已知残余**：勾选来自这个对话自己的 config 时（`liveSelectedServerIds == null`），
 *   执行期不复查勾选维度 —— 拿库里的助手勾选去硬比会误拒正常配置。其余六项照查。
 */
internal suspend fun runAgentMcpToolGuarded(
    servers: List<McpServerConfig>,
    serverId: Uuid,
    toolName: String,
    args: kotlinx.serialization.json.JsonObject,
    liveSelectedServerIds: Set<Uuid>?,
    agentMcpAllowed: Boolean,
    call: suspend (serverId: Uuid, toolName: String, args: kotlinx.serialization.json.JsonObject) -> List<UIMessagePart>,
): List<UIMessagePart> {
    // 🔴 第十一轮（双审独立抓到）：这里是「用户撤回授权」最直接的一条，
    // 而上一版漏了 —— 用户把总开关关掉后，已经挂上的工具照样继续真实外发。
    // 判在 agentMcpStalenessNote 之前：撤权优先于一切细则。
    if (!agentMcpAllowed) {
        return listOf(
            UIMessagePart.Text(
                "MCP 工具本次没有执行：子代理调用 MCP 的总开关在执行前被关掉了（用户在运行期间撤回了授权）。" +
                    "请把这件事写进 uncertainties 交回主对话，不要重试。",
            ),
        )
    }
    val refusal = agentMcpStalenessNote(servers, serverId, toolName, liveSelectedServerIds)
    if (refusal != null) return listOf(UIMessagePart.Text(refusal))
    return call(serverId, toolName, args)
}

/**
 * 名字分配最后的防御性自检：名单里万一仍有重复，**丢掉后来的并计数，绝不抛异常**。
 *
 * 抽成纯函数是为了能真的测到 —— 这段分支在 claimMcpToolName 正确时不可达，
 * 若只写在主流程里，等于一段永远没有证据的代码。
 */
internal fun dropDuplicateMcpNames(planned: List<String?>): Pair<List<String?>, Int> {
    val seen = mutableSetOf<String>()
    var dropped = 0
    val out = planned.map { name ->
        when {
            name == null -> null
            seen.add(name) -> name
            else -> { dropped += 1; null }
        }
    }
    return out to dropped
}

/**
 * 在**全局**已用名集合里给候选名占位：撞了就追加 `_2`、`_3`… 直到空闲。
 *
 * 为什么必须全局：上一版只在撞名组内编号，`get.file` + `get_file` + `get_file_2`
 * 会产出两个相同名字，随后自检抛异常把整趟子代理炸掉。现在任何候选名都要
 * 对着同一个集合占位，谁撞谁让，且**永不抛异常**。
 */
internal fun claimMcpToolName(candidate: String, used: MutableSet<String>): String {
    if (used.add(candidate)) return candidate
    var suffix = 2
    while (true) {
        val next = "${candidate}_$suffix"
        if (used.add(next)) return next
        suffix += 1
    }
}

/**
 * 把名字清洗成三家都接受的字符集（字母/数字/下划线）。
 *
 * 服务器名另有 stricter 规则（非法名直接不挂，与主对话一致）；这里管的是
 * **工具名** —— 它完全由外部 MCP 服务器决定，`codesys.project.open`、
 * `Get Data!` 这类都真实存在过，原样拼进名字会被 OpenAI/Claude 400，
 * 而且拒的是整个请求。清洗只影响**给模型看的名字**，不影响执行目标
 * （执行用 kept.serverId + kept.tool.name，见 agentMcpTools）。
 */
internal fun sanitizeMcpToolNamePart(value: String): String =
    value.map { ch ->
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_') ch else '_'
    }.joinToString("")

/** 撞名时依次尝试的服务器 id 前缀长度；都不够就退到完整 id。 */
private val MCP_ID_PREFIX_WIDTHS = listOf(8, 12, 16, 20, 24, 28, 32)

/** 一条真正要挂上子代理的 MCP 工具，以及它最终叫什么名字。 */
internal data class AgentMcpKeptTool(
    val serverId: Uuid,
    val serverName: String,
    val tool: me.rerere.rikkahub.data.ai.mcp.McpTool,
    val displayName: String,
    /**
     * 身份说明。给模型看的名字可能被清洗或编号，这时必须另有一处写清
     * 「实际调用的是哪台服务器的哪个工具」，否则两条名字相近的工具在模型眼里
     * 无法区分，它就会凭猜测选错 —— 与「名字唯一」是两件事。
     */
    val identityNote: String? = null,
)

/**
 * v1027：MCP 装配的**纯决策**（不碰 mcpManager、不造 Tool 对象，因此能被行为测试直接调用）。
 *
 * 复审指出：上一版把「非法名被跳过」「待批准被跳过」这两件事只写成源码字面量断言，
 * 把真正的过滤删掉、只留下计数，测试照样全绿。改成纯函数后，测试直接喂工具列表拿结果。
 */
internal data class AgentMcpDecision(
    val kept: List<AgentMcpKeptTool> = emptyList(),
    /** 要求用户手动批准，但子代理没有批准入口，只能不挂 */
    val needsApprovalCount: Int = 0,
    /** 服务器名不合法（与主对话同一套字符集规则），只能不挂 */
    val invalidNameCount: Int = 0,
    /** 最终名字超过供应商长度上限，只能不挂 */
    val nameOverflowCount: Int = 0,
    /** 名字分配最后的防御性自检发现重复，丢掉后来的（正常不可达；单独计数以免与「超长」混淆） */
    val conflictDroppedCount: Int = 0,
    /**
     * 🔴 越权入口 5：同一 serverId 在设置里出现多份配置时被**整台放弃的服务器数量**。
     * 量纲是「台」，不是「个工具」，说明书必须分开讲 —— 混进工具数会虚报影响面。
     * 这个数字来自枚举层（McpManager.agentMcpToolRecords），决策函数只是如实转记，
     * 因为「同一台服务器有两份配置」这件事只有在拿到完整服务器清单时才看得见。
     */
    val ambiguousServerCount: Int = 0,
)

/**
 * 决定这批 MCP 工具里哪些能挂给子代理、各自叫什么名字。四条规矩都有真实后果：
 *
 * 0. **任何过滤都不许排在身份归并之前**（🔴 第六轮双审独立点名，第七轮又查出第三个入口）。
 *    第五轮修掉的是「先滤审批、后归并」；第六轮发现同一类病的第二个入口 ——
 *    非法服务器名的过滤同样排在归并前面。于是同一 (serverId + 工具名) 身份若有两条
 *    配置记录（一条服务器名非法且 `needsApproval=true`、另一条名字合法且 `false`；
 *    重复 serverId 的配置在导入/恢复路径上并没有去重保证，McpManager 是逐行展开的），
 *    非法那条先消失，归并时只剩 `false`，这个「要求用户批准」的工具照样挂上并
 *    **以无审批状态执行** —— 又等于替用户放权。安全边界只能由本函数自己闭合，
 *    不能赌配置数据里不会出现重复条目。
 *    第七轮复查修复时查出第三个同类入口：`filter { tool.enable }` 也排在归并前，
 *    于是「禁用+要求批准」那份配置被当成禁用扔掉后，「启用+不要求批准」那份照样挂上。
 *    现在的写法是**先归并、再在身份内部按 a→b→c 逐条问**，归并之前一条记录都不扔。
 *
 * 1. **同一台服务器的同名工具只留一条**（按 serverId + 工具名去重）。
 *    上一版给重复项加 `_2` 序号，名字确实两两不同了，可那个尾巴跟服务器身份毫无关系 ——
 *    模型看到 mcp__orders__refund_2 完全不知道自己调的是哪一台。而这两条指向的是
 *    **同一个** serverId + 同一个工具名，本来就是同一个工具，留一条即可，不必编号骗人。
 *
 * 2. **撞名（两台不同服务器同名同工具）时加服务器 id 区分码**，长度按冲突程度自动加长
 *    （8→12→16→20→24→28→完整 id）。上一版写死前 8 位，而 UUID 前 8 位相同是真实存在的 ——
 *    等于赌概率；测试还因为「序号兜底让名单仍然两两不同」而全绿，是个摆设。
 *
 * 3. **加上区分码会突破供应商名字长度上限时，整组不挂**，并如实计入 nameOverflowCount。
 *    两个害处都不能要：发超长名字可能让整次请求被拒；偏向先出现的那条则会把调用
 *    打到错误的服务器上（外部数据误操作）。宁可不给这个工具。
 *
 * 计数口径（第六轮统一、第七轮扩为五类）：五个计数里前四个以**逻辑工具身份**（serverId + 原始工具名）
 * 为单位，而且每个身份只落在一种原因里，互斥不重复。原先审批与非法名按「配置记录数」计、
 * 超长与冲突按「身份数」计 —— 同一份数据两种量纲，说明书里那句「N 个工具要求批准」
 * 就会虚报；审查点名要求明确口径，这里定为身份数。
 */
internal fun decideAgentMcpTools(
    entries: List<Triple<Uuid, String, me.rerere.rikkahub.data.ai.mcp.McpTool>>,
    ambiguousServerCount: Int = 0,
): AgentMcpDecision {
    // 规矩 0（🔴 第六轮提出、第七轮贯彻到底）：**归并之前一条记录都不许滤**。
    // 这个洞一共出现过三次，每次的症状都是「多了一个排在归并之前的过滤」：
    //   · 第五轮：先滤掉 needsApproval=true 再归并 → 同身份「一条要批准、一条不要」时，
    //     要批准那条消失，不要批准那条被挂上并以无审批状态执行，等于替用户放权。
    //   · 第六轮：双审独立指出「非法服务器名」的过滤也排在归并前 → 同身份「非法名+要批准」
    //     与「合法名+不要批准」并存时，同一个洞从新维度重新打开。
    //   · 第七轮：复查上面的修复又发现第三个入口 —— `filter { tool.enable }` 同样在归并前。
    //     同身份「禁用+要求批准」与「启用+不要求批准」并存时，要求批准那条被当成禁用滤走，
    //     剩下那条照样挂上执行。已用行为模型推演确认确实越权，不是理论洁癖。
    // 结论：只要归并前存在任何按记录维度的过滤，这个洞就能从新维度再开一次。
    // 所以现在改成「先归并，再在每个身份内部逐条问」，一条记录都不先扔。
    val byIdentity = entries.groupBy { (serverId, _, tool) -> serverId to tool.name }
    val distinct = mutableListOf<Triple<Uuid, String, me.rerere.rikkahub.data.ai.mcp.McpTool>>()
    var needsApprovalCount = 0
    var invalidNameCount = 0
    byIdentity.values.forEach { group ->
        // a) 该身份的每一份配置都被用户禁用 → 不挂，但**不计入任何「用不了」的原因**：
        //    那是用户自己的选择，说明书把它报成「N 个工具因为审批/非法名用不了」会误导。
        val on = group.filter { (_, _, tool) -> tool.enable }
        if (on.isEmpty()) return@forEach
        // b) 审批取最严格，而且要问**这个身份的全部配置**（含被禁用的那一份）：
        //    只问启用记录的话，「禁用+要求批准」那条一不算就等于替用户放权 —— 入口 3。
        if (group.any { it.third.needsApproval }) {
            needsApprovalCount += 1
            return@forEach
        }
        // c) 代表必须取「已启用 + 服务器名合法」的那条。全组启用配置的名字都不可用时
        //    整个身份不挂并计数。主对话碰到非法名是直接抛 InvalidMcpServerNamesException；
        //    子代理里抛会把整趟任务炸掉而用户看不见原因，所以这里改成跳过 + 如实交代。
        //    残余风险（不谎报成「没有后果」）：同一身份的多份配置若 description /
        //    inputSchema 不一致，这里只能取代表那一份。能保证的是**执行路由不变** ——
        //    仍按 serverId + 原始工具名调用，不会打到别的服务器上；
        //    不能保证的是模型会照哪份 schema 生成参数：parameters 取的就是代表记录的
        //    inputSchema，而参数是直接发给 MCP 服务端的（McpSessionRegistry 里没有
        //    按 schema 再做校验）。所以「schema 取哪一份」在异常配置下是有实际影响的，
        //    只是它属于重复配置这种本不该存在的数据，未额外扩大处理。
        val rep = on.firstOrNull { (_, serverName, _) -> isMcpServerNameUsable(serverName) }
        if (rep == null) {
            invalidNameCount += 1
            return@forEach
        }
        distinct.add(rep)
    }

    // 解构顺序：first = serverId、second = serverName、third = tool。写错顺序编译器不一定拦得住。
    // 名字一律先清洗再拼装：外部服务器给的工具名可能带点号、空格、中文，原样发出去会被
    // OpenAI/Claude 拒整个请求（公开案例 codesys.project.open 就被 400）。
    // 清洗只改「给模型看的名字」，执行仍用 serverId + 原始工具名。
    val natural = distinct.map { (_, serverName, tool) -> "mcp__${serverName}__${tool.name}" }
    val base = distinct.map { (_, serverName, tool) ->
        "mcp__${sanitizeMcpToolNamePart(serverName)}__${sanitizeMcpToolNamePart(tool.name)}"
    }
    val planned = arrayOfNulls<String>(distinct.size)
    val notes = arrayOfNulls<String>(distinct.size)
    // 🔴 第五轮审查抓到的真缺陷：上一版只在「本组内」编号，于是
    // get.file / get_file / get_file_2 三件套会产出两个一模一样的中文名，
    // 随后 check() 抛异常把整趟子代理炸掉。名字分配必须是**全局**的：
    // 所有候选名都对着同一个 used 集合占位，谁撞谁让。
    val used = mutableSetOf<String>()

    // 第一轮：不撞名的先占到自己的本名 —— 它们本来就干净，不该被撞名组的编号挤掉
    base.indices.groupBy { base[it] }.forEach { (_, indexes) ->
        if (indexes.size == 1) {
            val i = indexes[0]
            used.add(base[i])
            planned[i] = base[i]
        }
    }

    // 第二轮：撞名组
    base.indices.groupBy { base[it] }.forEach { (_, indexes) ->
        if (indexes.size == 1) return@forEach
        val ids = indexes.map { distinct[it].first.toString().replace("-", "") }
        val width = MCP_ID_PREFIX_WIDTHS.firstOrNull { w ->
            ids.map { it.take(w) }.distinct().size == ids.size
        }
        if (width != null) {
            // 服务器 id 能区分：加 id 前缀（身份说明里用**实际采用的那一段**，
            // 不能固定写前 8 位 —— 前 8 位相同的两台服务器会出现说明也相同的窘境）
            indexes.forEachIndexed { position, i ->
                val prefix = ids[position].take(width)
                val candidate = "mcp__${sanitizeMcpToolNamePart(distinct[i].second)}_" +
                    "${prefix}__${sanitizeMcpToolNamePart(distinct[i].third.name)}"
                planned[i] = claimMcpToolName(candidate, used)
                notes[i] = "实际调用服务器 ${distinct[i].second}（id $prefix）的工具 ${distinct[i].third.name}"
            }
        } else {
            // 同一台服务器上不同工具名被清洗成了同一段：按出现顺序编号，
            // 但候选名仍要过全局占位，绝不允许与别的真实工具名重合
            indexes.forEachIndexed { position, i ->
                val candidate = if (position == 0) base[i] else "${base[i]}_${position + 1}"
                planned[i] = claimMcpToolName(candidate, used)
                notes[i] = "实际调用服务器 ${distinct[i].second} 的工具 ${distinct[i].third.name}"
            }
        }
    }

    // 被清洗过、但没撞名的条目同样要让模型知道原始工具名，
    // 否则它看到 project_open 完全不知道对应的是 project.open。
    distinct.indices.forEach { i ->
        val name = planned[i] ?: return@forEach
        if (notes[i] == null && name != natural[i]) {
            notes[i] = "实际调用服务器 ${distinct[i].second} 的工具 ${distinct[i].third.name}"
        }
    }

    // 规矩 3（唯一的长度闸门）：不论撞不撞名，超上限的一律不挂并计数
    var overflow = 0
    planned.indices.forEach { i ->
        val name = planned[i] ?: return@forEach
        if (name.length > MAX_MCP_TOOL_NAME_LENGTH) {
            planned[i] = null
            overflow += 1
        }
    }

    // 最后一道自证：claim 已保证两两不同，这里**绝不抛异常**再炸一次用户任务。
    // 重复丢弃单独计数，不再混进「超长」——否则说明书会把原因说错（审查点名）。
    val (finalPlanned, conflictDropped) = dropDuplicateMcpNames(planned.toList())
    val kept = distinct.mapIndexedNotNull { i, entry ->
        val name = finalPlanned[i] ?: return@mapIndexedNotNull null
        AgentMcpKeptTool(entry.first, entry.second, entry.third, name, notes[i])
    }
    return AgentMcpDecision(
        kept = kept,
        needsApprovalCount = needsApprovalCount,
        invalidNameCount = invalidNameCount,
        nameOverflowCount = overflow,
        conflictDroppedCount = conflictDropped,
        ambiguousServerCount = ambiguousServerCount,
    )
}

/**
 * v1027：子代理本次实际拿到的 MCP 能力 —— 只装**实际装配结果 + 那份决策**。
 *
 * 上一版是三个散装参数（工具名列表 + 两个计数）手传四个函数、六个调用点：
 * 红验证证明「只把计数从装配返回值里拿掉、说明书侧不动」测试照样全绿（计数是摆设），
 * 而批量替换时也确实串过一次变量名。现在只有一个对象要传，
 * 漏传与串参数这两类错误在结构上无法发生；五个计数全部由 decision 派生，
 * 说明书不可能拿到与装配不一致的数字。
 *
 * - tools：真正挂上去的工具（已跳过需要用户批准的那些）
 * - decision：那份完整决策，说明书需要的每个数字都从这里读，不许自己判断开关
 *
 * 被跳过的数量必须告诉模型，否则它会以为自己没有这项能力却不知道为什么，
 * 或者反复尝试一个根本不会执行的调用。
 */
internal data class AgentMcpCapability(
    val tools: List<Tool> = emptyList(),
    val decision: AgentMcpDecision = AgentMcpDecision(),
) {
    /** 说明书宣称的数量只能来自这里 */
    val toolNames: List<String> get() = tools.map { it.name }
    val approvalSkippedCount: Int get() = decision.needsApprovalCount
    val invalidNameSkippedCount: Int get() = decision.invalidNameCount
    val nameOverflowCount: Int get() = decision.nameOverflowCount
    val conflictDroppedCount: Int get() = decision.conflictDroppedCount
    /** 第八轮入口 5：被整台放弃的服务器「台数」，量纲与其它四项不同，说明书要分开讲 */
    val ambiguousServerCount: Int get() = decision.ambiguousServerCount
    /**
     * 五类「开关开着却没挂上」的原因合计；与工具名一起决定说明书提不提 MCP。
     *
     * ⚠️ 措辞历史（别改回去）：
     *  · 第六轮指出旧注释写「三种」与实现不符，实际四类；
     *  · 第八轮补上「重复 serverId 整台放弃」，变五类。
     * 其中「防御性重名丢弃」正常不可达，属兜底，界面不列；
     * 所以界面文案列的是**四类常见原因**（审批、非法服务器名、重复配置、名字超长）。
     * 说明书侧五类都单独讲。这句注释写错，下一个改代码的人会按旧分类去理解。
     */
    val skippedTotal: Int
        get() = decision.needsApprovalCount + decision.invalidNameCount +
            decision.nameOverflowCount + decision.conflictDroppedCount +
            decision.ambiguousServerCount
}

/** 构建独立助手副本：清空记忆、MCP、技能、正则、模式注入、知识库、网络搜索等一切污染源 */
internal fun buildIsolatedAssistant(
    base: me.rerere.rikkahub.data.model.Assistant,
    thread: AgentThread,
    memoryEnabled: Boolean = false,
    webSearchToolNames: List<String> = emptyList(),
    mcp: AgentMcpCapability = AgentMcpCapability(),
): me.rerere.rikkahub.data.model.Assistant = base.copy(
    systemPrompt = buildAgentSystemPrompt(thread, memoryEnabled, webSearchToolNames, mcp),
    enableMemory = false,
    useGlobalMemory = false,
    mcpServers = emptySet(),
    localTools = emptyList(),
    enabledSkills = emptySet(),
    regexes = emptyList(),
    presetMessages = emptyList(),
    modeInjectionIds = emptySet(),
    lorebookIds = emptySet(),
    enableWebSearch = false,
    enableRecentChatsReference = false,
)

/** 生成子代理系统提示词（角色说明 + 任务 + 上下文摘要） */
internal fun buildAgentSystemPrompt(
    thread: AgentThread,
    memoryEnabled: Boolean = false,
    webSearchToolNames: List<String> = emptyList(),
    mcp: AgentMcpCapability = AgentMcpCapability(),
): String = buildString {
    appendLine(when (thread.role) {
        AgentRole.DEFAULT -> "你是一个子代理，负责完成分配给你的明确任务。"
        AgentRole.EXPLORER -> "你是探索型子代理（explorer）。你的职责是快速、准确地探索代码库/资料，追踪真实执行路径，引用文件和符号；除非主代理要求，不要提出修改方案。优先使用快速搜索和定向读取。"
        AgentRole.REVIEWER -> "你是审查型子代理（reviewer）。你的职责是像负责人一样审查代码：优先关注正确性、安全性、行为回归和缺失的测试。先给出具体发现，需要时给出复现步骤，避免只提风格问题。"
        AgentRole.PROGRAMMER -> "你是编程型子代理（programmer）。你的职责是按主代理给的要求，在允许改的文件里做出最小、正确的改动。先读懂现有写法再动手，跟随文件既有风格；不要顺手重构无关代码，不要新增依赖。"
    })
    appendLine()
    // v236：写权限由白名单决定，不由角色决定。没白名单就还是那句「只能看不能动」。
    if (thread.canWrite) {
        appendLine("你可以读取工作区文件，并且**只能修改下面这些路径**：")
        thread.writablePaths.forEach { appendLine("- $it") }
        appendLine("白名单之外的任何文件都改不了（工具会直接拒绝），不要浪费步数去试。")
        appendLine("你没有终端、不能执行命令、不能编译、不能删除或改名文件。")
        appendLine("编译与测试由主代理统一执行 —— 你只负责把改动做对，然后如实汇报改了哪几处。")
    } else {
        appendLine("你只能读取工作区文件进行查证，不能修改任何文件、不能执行命令。")
    }
    appendLine()
    // v281：开关打开时必须把这件能力**写进提示词**，光把工具塞进 tools 列表不够。
    //
    // 为什么：上面那两句写的是「你只能读取工作区文件」，模型看完会认定自己的能力边界就到
    // 工作区为止 —— 真机实测过一条子代理，明明手里有工具，仍在报告里写「我只能搜工作区文件，
    // 没有查历史对话的手段」。能力不写进提示词，等于开关白开。
    //
    // 中文关键词那句也是必要的：全文索引走的是 jieba 分词（simple 扩展），
    // 整句话丢进去往往一条都搜不中，得用两三个字的短词分几次搜。
    if (memoryEnabled) {
        appendLine("## 查用户的历史对话（用户已授权）")
        appendLine()
        appendLine("你另外有两把工具可以查用户过去跟主代理的对话：")
        appendLine("- `recent_chats`：列最近的对话标题和日期（只有标题，没有正文）；")
        appendLine("- `conversation_search`：按关键词全文搜正文，返回命中片段。")
        appendLine()
        appendLine("上面那句「只能读取工作区文件」说的是**不能改文件、不能执行命令**，不是禁止你查历史。")
        appendLine()
        appendLine("什么时候值得查：任务里出现「这个以前是不是碰过」「当初为什么定成这样」「上次那个报错是怎么解决的」")
        appendLine("这类只有旧对话才能回答的问题。跟历史无关的任务不要浪费步数去搜。")
        appendLine()
        appendLine("怎么搜得准：**关键词要短**（中文两三个字最好，例如「续跑」「签名」「额度」），")
        appendLine("整句话丢进去大概率一条都搜不到；一次没搜中就换个词再试，别在同一个长句上反复。")
        appendLine()
        appendLine("引用历史内容时同样按 evidence 的规矩写清来源（对话标题 + 日期），不要把旧对话里的")
        appendLine("结论当成自己的判断 —— 里面可能有已经被推翻的说法。")
        appendLine()
    }
    // v324：联网能力必须写进提示词，光把工具塞进 tools 列表不够 —— 与 v281 那条教训同源。
    //
    // 上面那句「你只能读取工作区文件进行查证」就是子代理自称「我上不了网」的直接来源。
    // 真机实测一条探测位，报告原文「本子代理只能读取工作区文件，不能按要求实际联网搜索」，
    // 句式与那句说明书几乎一字不差 —— 它是在**复读说明书**，不是在看自己手里有什么工具。
    //
    // 为什么这里读的是 webSearchToolNames（实际装配到的工具名）而不是一个布尔开关：
    // 说明书只要自己判断一次，就迟早与 tools 分叉。本轮第一版正是按布尔写的，
    // 结果无条件宣称了 scrape_web —— 可 SearchTools 只在搜索服务提供抓取参数时才装配它
    //（Brave 等返回 null，根本不会装配），等于又埋一个「说明书说有、手里没有」。
    // 改成照抄装配结果以后，这类分叉在结构上就不可能出现：没装配的工具名进不了这句话；
    // 非探测位拿不到工具、列表为空，也自然一个字不提，不必在这里再重复一遍角色判断
    //（重复判断本身就是下一次分叉的起点）。
    if (webSearchToolNames.isNotEmpty()) {
        appendLine("## 联网搜索（本次已开启）")
        appendLine()
        appendLine("你手里有这些联网工具（这就是本次全部可用的，没列出来的那个不存在）：")
        webSearchToolNames.forEach { name ->
            appendLine(
                when (name) {
                    "scrape_web" ->
                        "- `scrape_web`：抓指定网址的正文，可带 query 只取相关内容，长页可分段读。"
                    else ->
                        "- `search_web`：按关键词搜网页，返回标题、来源网址与相关片段。"
                },
            )
        }
        appendLine()
        appendLine("上面那句「只能读取工作区文件」讲的是**不能改文件、不能执行命令**，")
        appendLine("不是禁止你联网。任务要求查网上的信息时直接用上面的工具，")
        appendLine("**不要**在报告里写「我无法联网搜索」「只能读取工作区文件」这类结论。")
        appendLine()
        appendLine("真的查不到时，如实说明是哪一步失败的（搜索无结果 / 抓取报错 / 该网站需要登录），")
        appendLine("并写清你试过哪些关键词或网址；不要笼统地说自己没有联网能力。")
        appendLine()
    }
    // v1027：MCP 能力同样必须写进说明书 —— 与 v281（查历史）、v324（联网）是同一条教训：
    // 能力不写进去，光把工具塞进 tools 列表，模型会照着「你只能读取工作区文件」那句
    // 自称没这能力，开关等于白开。
    //
    // 这里只报**数量**、不逐个列名字：MCP 工具名由用户自配的服务器决定，可能几十个，
    // 全列进提示词会白占上下文。名称与参数模型本来就能在工具列表里看到，
    // 所以「有 N 个 + 见工具列表」足以消除「自称没能力」的误判。
    //
    // 数量取自实际装配结果（agentMcpTools 的返回值）：被跳过的非法服务器不算在内，
    // 所以这里宣称的数量不可能大于手里真有的工具。
    val mcpToolNames = mcp.toolNames
    if (mcpToolNames.isNotEmpty() || mcp.skippedTotal > 0) {
        if (mcpToolNames.isNotEmpty()) {
            appendLine("## MCP 外部工具（本次已开启，可用 ${mcpToolNames.size} 个）")
            appendLine()
            appendLine("本次还给你挂了 ${mcpToolNames.size} 个 MCP 工具。名字一般形如 mcp__服务器名__工具名；")
            // 措辞要跟实现对得上：只有**最终拼出来的名字会撞车**时才加区分码，
            // 两台同名服务器各提供不同工具时名字其实是干净的 —— 上一版说成
            // 「重名就带 id」并不准确（第五轮审查指出）。
            appendLine("如果两个工具拼出来的名字会撞车，名字里会加服务器 id 片段或序号来区分；")
            appendLine("凡是名字被改动过的，描述里都会写清实际调用的是哪台服务器的哪个工具。")
            appendLine("具体名称与参数见本次提供的工具列表 —— 那里列出的就是全部，没列出的不存在。")
            appendLine()
            appendLine("这些工具会**真实调用外部服务**：可能写文件、改外部数据、产生费用。")
            appendLine("它们不受上面「不能执行命令、不能编译、不能删除」那层限制约束 ——")
            appendLine("那层限制管的是本地命令，管不到 MCP 服务器在外面做了什么。")
            appendLine()
            appendLine("所以：只有任务确实需要、而且你能说清它会对**外部世界**做什么时，才调用。")
            appendLine("拿不准会不会改坏外部数据时，把这一点写进 uncertainties 交回主代理决定，")
            appendLine("不要自己按下去。调用过哪些 MCP 工具、各自做了什么，报告里必须写清楚。")
            appendLine()
        } else {
            // 一个都没挂上，但仍然要说 —— 不说的话模型只看到「没有 MCP 工具」，
            // 会以为自己压根没这能力，或者凭空编一个工具名去调。
            // 为什么没挂上，原因也必须照实际丢掉的数量讲，不许只讲其中一种。
            appendLine("## MCP 外部工具（本次已开启，但没有一个能直接用）")
            appendLine()
            // 「开关是开着的」必须无条件先讲一句：只有非法名、没有待批准工具时，
            // 上一版会整段不讲开关状态 —— 本轮测试就是把它抓出来的。
            appendLine("MCP 开关是开着的，可本次没有任何 MCP 工具能挂上来，原因是：")
            if (mcp.approvalSkippedCount > 0) {
                appendLine("・${mcp.approvalSkippedCount} 个要求用户手动批准，而子代理没有批准入口；")
            }
            if (mcp.invalidNameSkippedCount > 0) {
                appendLine("・${mcp.invalidNameSkippedCount} 个所在的 MCP 服务器名称不合法")
                appendLine("（只允许英文字母和数字）。主对话碰到这种名字会直接报错，")
                appendLine("  子代理为了不炸掉整趟任务改成跳过这一台；")
            }
            if (mcp.ambiguousServerCount > 0) {
                // 量纲是「台」不是「个工具」，措辞必须分开，否则用户会以为只少了几个工具。
                appendLine("・${mcp.ambiguousServerCount} 台 MCP 服务器在设置里存在多份相同 id 的配置，")
                appendLine("  这些服务器上的工具本次一个都不提供：连接层按 id 只认一份（后出现的覆盖前面的），")
                appendLine("  而工具说明取自另一份配置 —— 硬挂上去等于可能把外部操作发往你没看到的那台服务器。")
                appendLine("  请把重复的那一份配置删掉再试；")
            }
            if (mcp.conflictDroppedCount > 0) {
                // 正常不可达（claim 已保证唯一），但一旦发生必须说清是「重名冲突被丢」，
                // 不许混进「名字超长」—— 那是两种完全不同的原因（第五轮审查点名）。
                appendLine("・${mcp.conflictDroppedCount} 个与其他工具最终撞成同一个名字，")
                appendLine("  为避免调用打到错误的对象，重复的那些本次没有挂上来；")
            }
            if (mcp.nameOverflowCount > 0) {
                // 这一条必须讲，而且措辞要同时覆盖两种成因：
                // ① 工具名自己就长（不撞名也超长）；② 两台服务器重名、加区分码后顶爆上限。
                // 第四轮复审正是抓到「只讲重名」会让第一种成因说法失真。
                appendLine("・${mcp.nameOverflowCount} 个的名字超过供应商函数名长度上限")
                appendLine("（${MAX_MCP_TOOL_NAME_LENGTH} 字符；可能是工具名本身太长，也可能是两台服务器")
                appendLine("  重名后加区分码顶爆的）。硬发出去会让整个请求被供应商拒，")
                appendLine("  所以这些工具本次没有挂上来；")
            }
            appendLine("所以本次你手里没有 MCP 工具。")
            appendLine("任务确实需要它们时，把这一点写进 uncertainties 交回主代理处理，")
            appendLine("不要凭空调用列表里不存在的工具名。")
            appendLine()
        }
        if (mcpToolNames.isNotEmpty() && mcp.skippedTotal > 0) {
            // 部分被跳过也必须讲出来：模型知道「有什么、缺什么、为什么缺」，
            // 才不会在缺的那类操作上反复撞墙。
            val skippedParts = buildString {
                if (mcp.approvalSkippedCount > 0) {
                    append("${mcp.approvalSkippedCount} 个要求用户手动批准（子代理没有批准入口）")
                }
                if (mcp.invalidNameSkippedCount > 0) {
                    if (isNotEmpty()) append("，")
                    append("${mcp.invalidNameSkippedCount} 个所在的 MCP 服务器名称不合法（只允许英文字母和数字）")
                }
                if (mcp.nameOverflowCount > 0) {
                    if (isNotEmpty()) append("，")
                    append("${mcp.nameOverflowCount} 个名字超过供应商函数名长度上限（$MAX_MCP_TOOL_NAME_LENGTH 字符）")
                }
                if (mcp.ambiguousServerCount > 0) {
                    if (isNotEmpty()) append("，")
                    append("${mcp.ambiguousServerCount} 台服务器有多份相同 id 的重复配置（这些服务器上的工具全部没挂）")
                }
                if (mcp.conflictDroppedCount > 0) {
                    if (isNotEmpty()) append("，")
                    append("${mcp.conflictDroppedCount} 个与其他工具撞成同一个名字")
                }
            }
            appendLine("补充：另有 $skippedParts 的 MCP 工具没有挂上来。")
            appendLine("需要时写进 uncertainties 交回主代理，不要尝试调用工具列表里没有的名字。")
            appendLine()
        }
    }
    // v238：开局自动去找项目约定文件。
    //
    // 为什么加这个：此前子代理开局完全是白纸，路径约定、构建规则、禁区全靠主代理
    // 在任务文字里手写一遍。本轮实测主代理为 4 个子代理写了 8300 多字任务，
    // 目测一半以上是在重复解释项目常识 —— 这些字既占主代理的上下文，又每次都要重打。
    // 更糟的是漏写就出事：本轮主代理漏说「白名单路径要带源码树前缀」，
    // 三个子代理各自白跑十几分钟才诊断出路径不对。
    //
    // 约定文件是主代理写一次、所有子代理读 N 次，而且跨会话存活。
    //
    // ⚠️ v248 改掉了「自己去搜」这个做法。真机事故：一条子代理开局并行发了 3 个
    // workspace_find_files（**/TASKBOARD.md、**/AGENTS.md、**/PROJECT-MANUAL.md，从工作区根搜），
    // 跑了 8 分 35 秒还没返回就被人工停止，什么活都没干成。原因是工作区整棵树有 44 万个条目，
    // 而这类模式全树往往只有 1 个匹配 —— 匹配额度永远凑不满，于是必须走完整棵树。
    // v248 已给检索加了目录剪枝与时间预算（最坏 10 秒返回），但**从根本上就不该让它去搜**：
    // 路径由主代理在任务说明里直接给出，是零成本且不会出错的做法。
    appendLine("## 开工前")
    appendLine()
    appendLine("如果主代理在任务说明里给了项目约定文件的路径（`TASKBOARD.md` / `AGENTS.md` / `PROJECT-MANUAL.md` 这类），")
    appendLine("先读一遍再动手，**里面写的约束优先于你的直觉和常规做法**；没给就直接开工。")
    appendLine()
    appendLine("⚠️ **不要自己用查找工具去搜这些文件名。** 工作区可能有几十万个条目，")
    appendLine("从根目录做全树查找要花好几分钟，纯属浪费 —— 需要什么路径就跟主代理要，或者只在明确的子目录里找。")
    appendLine()
    // v247：把「读文件能续读」写进提示词。
    //
    // 为什么加：读文件工具单次最多回 32KB，旧版超了就直接截断、没有任何续读办法。
    // 真机实测抓到一条子代理审查 71614 字节的源码文件，只看到前 45%，它在报告里如实写了
    // 「函数体我未能直接读到原文，只能依据搜索返回的行内片段推断」—— 结论因此只能靠猜。
    // 工具已经支持 start_line 续读，但只写在工具说明里，模型未必会主动用，这里再点一次。
    appendLine("## 读大文件（别拿半截内容下结论）")
    appendLine()
    appendLine("读文件工具单次只给一段（按行切）。返回里如果带 `next_start_line`，说明这个文件还没读完 ——")
    appendLine("用同一个路径、把 `start_line` 填成那个数字再读一次，接着往下看，直到不再返回它。")
    appendLine("需要判断某段代码对不对，就必须真的读到那一段：只凭搜索命中的零散行去推断，很容易得出相反的结论。")
    appendLine()
    // v248：教它跳读。此前它的习惯是从第 1 行一段段读到目标位置，白花好几步。
    appendLine("**想看某个具体的东西时，先搜再跳读，别从头翻**：用搜索工具拿到行号，")
    appendLine("再把 `start_line` 直接填成那个行号（可以往前留几十行看上下文）。这比从第 1 行一段段读快得多。")
    appendLine()
    appendLine("## 输出契约（必须遵守）")
    appendLine()
    appendLine("你的最终报告必须整段包在 <report> 与 </report> 之间。")
    appendLine("标记之外的一切内容（思考过程、「我先看一下…」这类过渡说明）都不会交给主代理，写了等于浪费。")
    appendLine()
    appendLine("标记里优先使用下面这个 JSON 结构，这样主代理可以分字段取用：")
    appendLine("{")
    appendLine("  \"conclusion\": \"结论正文\",")
    appendLine("  \"evidence\": [\"文件路径:行号 —— 原文片段\"],")
    appendLine("  \"uncertainties\": [\"没查实的地方，以及为什么\"],")
    appendLine("  \"suggestions\": [\"给主代理的建议\"]")
    appendLine("}")
    appendLine("四段的含义依次是：结论、证据、不确定项、建议。")
    appendLine("写不出合法 JSON 时，直接在标记里写纯文本也可以，不要为了凑格式牺牲内容。")
    appendLine()
    appendLine("evidence 每一条都必须给出可核对的位置（文件路径 + 行号）和原文片段；")
    appendLine("没有位置与原文支撑的判断请放进 suggestions 或 uncertainties，不要混进 evidence。")
    if (thread.role == AgentRole.PROGRAMMER && thread.canWrite) {
        appendLine()
        appendLine("你是编程位，evidence 请逐条写成「文件路径:行号 —— 改前 → 改后（要点）」，")
        appendLine("让主代理不用重读整个文件就能核对你到底动了哪里。")
    }
    if (thread.role == AgentRole.REVIEWER) {
        appendLine()
        appendLine("你是审查位，必须额外输出一个字段表明结论：")
        appendLine("  \"verdict\": \"pass\"   // 没问题、可以往下走")
        appendLine("  \"verdict\": \"fail\"   // 有问题，必须先修")
        appendLine("这两个值之一，不要写其他词。没有把握就写 fail 并在 conclusion 里说明为什么没把握 ——")
        appendLine("主代理会接手，写 fail 不算你失职；把没审明白的东西写成 pass 才是真出事。")
        appendLine("每条问题都要写成「文件路径:行号 违反了什么」，不要只写「我觉得不好」。")
        appendLine()
        // v238：本轮真机实测抓到的真实事故 —— 审查位在 conclusion 里写「全部满足、可以往下走」，
        // 却漏了 verdict 字段，于是被判成「没表态」按不通过处理，流水线白跑。
        appendLine("**verdict 必须是报告 JSON 的第一个字段，漏写它等于判不通过。**")
        appendLine("在 conclusion 里写「全部满足」「可以往下走」这类话**不算表态** —— 机器只认 verdict 字段。")
        appendLine("漏写会触发一次补问，白花一次调用；补问后还不写就直接按不通过处理。")
    }
    appendLine()
    appendLine("任务：")
    appendLine(thread.task)
    thread.contextSummary?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine("主代理提供的上下文：")
        appendLine(it)
        appendLine()
        // v239：主对话原文现在可以整份下传，所以必须补一段读法。
        // 原文里往往夹着已经被推翻的旧结论和走过的弯路，廉价模型会照着做。
        appendLine("上面这段是背景资料，不是新任务。用法：")
        appendLine("- **任务书优先**：与任务书冲突的地方一律以任务书为准；")
        appendLine("- 里面可能有已经被推翻的旧结论、走过的弯路，不要照着做；")
        appendLine("- 不要复述它，也不要被它带跑题。")
    }
}
