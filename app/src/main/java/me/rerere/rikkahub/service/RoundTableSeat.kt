package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import kotlin.uuid.Uuid

/**
 * v213 圆桌座位模型。
 *
 * 核心思想：**「会议位置」与「坐在这个位置上的模型」彻底分开。**
 *
 * - 位置（座位）由 [RoundTableSeat.seatId] 唯一标识，一整轮圆桌里固定不变；
 * - 模型只是当前坐在这个位置上的人，可以被停止、更换、重试、跳过；
 * - 每次更换都会让 [RoundTableSeat.attempt] +1，旧请求即使晚到也不允许再写结果（防"幽灵结果"）。
 *
 * 本文件只放纯数据与判定逻辑，不依赖 Android，方便单元测试。
 */

/** 座位当前状态 */
internal enum class RoundTableSeatStatus {
    /** 排队中，还没开始 */
    PENDING,

    /** 正在生成 */
    RUNNING,

    /** 正常产出，可以作为后续阶段材料 */
    SUCCEEDED,

    /** 有文字但疑似无效（只复述角色说明、只说"我准备去核查"、太短）；默认不进入最终拍板 */
    SUSPECT,

    /** 出错且不再自动重试 */
    FAILED,

    /** 用户手动停止了这个位置 */
    STOPPED,

    /** 用户手动跳过这个位置 */
    SKIPPED,

    /** App 被系统结束等原因意外中断；不会自动重新调用模型 */
    INTERRUPTED,
    ;

    /** 调度层认为这个位置已经处理完（不再等它） */
    val isTerminal: Boolean
        get() = this != PENDING && this != RUNNING

    /** 是否为"缺席"：位置存在但没有拿到可用结果 */
    val isMissingResult: Boolean
        get() = this == SUSPECT || this == FAILED || this == STOPPED ||
            this == SKIPPED || this == INTERRUPTED

    /**
     * 缺席检查点是否需要为这个位置提醒用户补齐。
     *
     * v215：「跳过」表示用户已经明确放弃这个位置，不再提醒；
     * 「停止」只是先别跑了，位置仍然在编，检查点照旧提醒补齐。
     * 这是「停止」与「跳过」之间唯一的调度层区别。
     */
    val countsAsGap: Boolean
        get() = isMissingResult && this != SKIPPED
}

/**
 * v223：结束原因判定（圆桌侧独立实现）。
 *
 * 为什么不复用子代理那份：架构红线要求圆桌源码禁止引用 `me.rerere.rikkahub.agent.*`
 * （由 AgentDependencyBoundaryTest 守护），因此这里保留一份等价实现。
 *
 * 各服务商对「达到输出上限被截断」的叫法不同：
 * - OpenAI Chat Completions：`length`
 * - OpenAI Response API：`incomplete:max_output_tokens`
 * - Claude：`max_tokens`
 * - Google：`MAX_TOKENS`
 * 统一转小写后按关键字匹配，避免逐家硬编码。
 */
internal object RoundTableFinishReason {
    private val TRUNCATION_KEYWORDS = listOf(
        "length",
        "max_token",
        "max_output_token",
        "maxtoken",
        "incomplete",
        "truncat",
    )

    /** 该结束原因是否代表「输出没写完就被强行截断」 */
    fun isTruncated(finishReason: String?): Boolean {
        val normalized = finishReason?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return TRUNCATION_KEYWORDS.any { normalized.contains(it) }
    }
}

/**
 * 一个座位。
 *
 * @param seatId 稳定标识，例如 `contract`、`explorer:0`、`main_draft`、`rebuttal`、`summary`
 * @param slot 页序槽位，决定这一页在节点里排第几（与 v208 起的 RoundTableSlot 一致）
 * @param ordinal 同角色内的序号，从 1 起；用于"独立探索 2"这类展示
 * @param attempt 第几次尝试；更换模型或重试都会 +1
 * @param reruns 真正重跑过几次（自动重试、手动重试、换模型）。
 *   v215 新增：[attempt] 同时承担"作废旧请求"的职责，停止/跳过/超时也会推高它，
 *   直接显示给用户会让人误以为在重试，因此界面只显示 [reruns]。
 * @param pendingCommand 是否有用户指令已下达但还没被执行协程处理完（界面显示"正在停止…"）
 * @param includeInSummary 是否参与最终拍板的材料；用户可手动开关
 * @param finishReason v223 新增：Provider 返回的结束原因（stop / end_turn / length / max_tokens / incomplete:...）
 * @param truncated v223 新增：是否因达到模型单次输出上限被截断。
 *   在此之前圆桌完全不看结束原因，半截产出会被当成「已完成」（字数够）或
 *   误判成「疑似无效·太短」（字数不够），用户无法区分模型是摆烂还是被砍断。
 * @param continuations v223 新增：这个位置「继续输出」过几次（区别于重跑，续跑不丢已产出内容）
 * @param autoContinuations v229 新增：程序自动续跑过几次（用户手动点的算在 [continuations] 里）。
 *   自动续跑有独立预算 [RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS]，用完就停下等用户。
 * @param scheduled v229 新增：当前有没有执行协程在负责这个位置。
 *   历史 bug：重试 / 换模型 / 续跑会把座位复活成 [RoundTableSeatStatus.PENDING]，
 *   但如果那一组的调度器已经退出（例如最终综合阶段跑完了），就没有任何协程会去跑它，
 *   座位永久停在「排队中」，界面上连重试按钮都不显示。把这个信息放进快照，
 *   调度层可以据此补跑，界面也能据此显示救援入口。
 */
internal data class RoundTableSeat(
    val seatId: String,
    val role: RoundTableRole,
    val slot: Int,
    val ordinal: Int = 1,
    val modelId: Uuid? = null,
    val modelName: String = "",
    val attempt: Int = 1,
    val reruns: Int = 0,
    val autoRetries: Int = 0,
    val pendingCommand: Boolean = false,
    val status: RoundTableSeatStatus = RoundTableSeatStatus.PENDING,
    val chars: Int = 0,
    val startedAtMillis: Long = 0L,
    val lastActivityAtMillis: Long = 0L,
    val error: String? = null,
    val suspectReason: RoundTableContentVerdict? = null,
    val messageId: Uuid? = null,
    val includeInSummary: Boolean = true,
    val finishReason: String? = null,
    val truncated: Boolean = false,
    val continuations: Int = 0,
    val autoContinuations: Int = 0,
    val scheduled: Boolean = false,
    val chatConversationId: String? = null,
) {
    /** v224：这个位置有没有对应的独立对话可以点进去看（真实 Conversation，不是自绘页面） */
    val hasSeatChat: Boolean
        get() = !chatConversationId.isNullOrBlank()

    /** 这个位置是否已经拿到了可以送去拍板的材料 */
    val hasUsableResult: Boolean
        get() = status == RoundTableSeatStatus.SUCCEEDED

    /**
     * v229：这个位置「卡在没人负责的状态」了吗？
     *
     * 复现路径（用户实测）：最终综合超时失败 → 那一组的调度器判定全部有结论后退出 →
     * 用户点「换模型」，座位被复活成 [RoundTableSeatStatus.PENDING] →
     * 但已经没有任何执行协程会来跑它，也没人消费指令 →
     * 界面永远显示「排队中 · 已重跑 1 次」，而「重试」「继续输出」按钮的显示条件都不含
     * PENDING，「停止」按下去也没人执行，等于整个位置焊死，只能整轮作废。
     *
     * 调度层用它决定要不要补跑；界面用它决定要不要额外给出救援入口与说明。
     */
    val stalledUnscheduled: Boolean
        get() = !scheduled && !status.isTerminal

    /**
     * v223：这个位置能不能「继续输出」。
     *
     * 用户原话：「不稳定的模型已经跑得差不多了，结果截断了，前面的时间和花费都浪费了，
     * 明明可以直接喊模型『继续』的」。
     *
     * 允许续跑的情形（都留下了半截内容，值得接着写）：
     * - 已完成但被截断：达到模型单次上限；
     * - 疑似无效：往往就是被截断导致太短；
     * - 用户停止：v215 起会保留停止前的半截内容；
     * - 意外中断 / 失败：流式已吐出的部分仍在快照里。
     *
     * 不允许：还在排队/运行（本来就在写）、用户明确跳过（已放弃这个位置）、
     * 以及正常完整写完的产出（再喊继续只是白花钱）。
     */
    val canContinue: Boolean
        get() = when (status) {
            RoundTableSeatStatus.SUCCEEDED -> truncated

            RoundTableSeatStatus.SUSPECT,
            RoundTableSeatStatus.STOPPED,
            RoundTableSeatStatus.INTERRUPTED,
            RoundTableSeatStatus.FAILED,
                -> true

            RoundTableSeatStatus.PENDING,
            RoundTableSeatStatus.RUNNING,
            RoundTableSeatStatus.SKIPPED,
                -> false
        }

    /**
     * 距离上一次"有动静"过了多久。
     *
     * 只在 [RoundTableSeatStatus.RUNNING] 时有意义；没跑起来时返回 0。
     * 注意：这里的"有动静"不只是屏幕上的字数，也包含思考内容、工具调用等，
     * 由调用方在收到任何流式更新时刷新，避免 MAX 长思考被误判成卡死。
     */
    fun idleMillis(nowMillis: Long): Long = when {
        status != RoundTableSeatStatus.RUNNING -> 0L
        lastActivityAtMillis <= 0L -> (nowMillis - startedAtMillis).coerceAtLeast(0L)
        else -> (nowMillis - lastActivityAtMillis).coerceAtLeast(0L)
    }

    /** 这一次尝试总共跑了多久 */
    fun elapsedMillis(nowMillis: Long): Long = when {
        startedAtMillis <= 0L -> 0L
        else -> (nowMillis - startedAtMillis).coerceAtLeast(0L)
    }
}

/** 圆桌整轮进行到哪一步 */
internal enum class RoundTableRunPhase {
    /** 第一步：理清任务与共享事实 */
    CONTRACT,

    /** 第二步：各模型并行出方案（含主模型初案） */
    PROPOSALS,

    /** 出方案后发现有位置缺席，停下等用户决定 */
    PROPOSAL_GAP,

    /** 第三步：针对性反驳 */
    REBUTTAL,

    /** 第四步：主模型最终拍板 */
    SUMMARY,

    /** 本轮结束（正常跑完、用户结束或整场取消） */
    FINISHED,
}

/** 缺席检查点上用户的选择 */
internal enum class RoundTableGapDecision {
    /** 我要先补齐缺席位置：不继续，留在检查点，等用户逐个更换或重试 */
    FILL_SEATS,

    /** 就用现有的有效方案继续 */
    CONTINUE_WITH_CURRENT,

    /** 结束本次圆桌，不再往下跑 */
    END_RUN,
}

/**
 * v229：检查点是为什么停下来的。
 *
 * 三种场景共用同一套「停下来等用户」的机制，但提示语与后果不同：
 * - [PROPOSALS]：出方案阶段有位置缺席（v213 起的原有行为）；
 * - [REVIEW]：某个阶段出现「疑似无效（太短 / 复述角色说明 / 只说要去做什么）」的产出。
 *   用户明确要求：这种情况**不自动续跑**，而是整场停下来等他看过再决定算不算进方案，
 *   避免模型只写了一点点就被强制续写，产生噪音；**后续阶段也必须一起停住**，
 *   不允许当前阶段停着、后面的阶段先开跑；
 * - [SUMMARY]：最终综合没拿到可用结果。用户明确要求这一步不能直接收工，
 *   否则前面几步的成果全废；必须停下来允许重试 / 换模型 / 续跑。
 */
internal enum class RoundTableGapStage {
    PROPOSALS,
    REVIEW,
    SUMMARY,
}

/** 缺席检查点信息，交给界面显示 */
internal data class RoundTableGapPrompt(
    val usableCount: Int,
    val missingCount: Int,
    /** 继续下去还会额外调用几次模型（用于提示费用） */
    val remainingCalls: Int,
    /** v229：这次是为什么停下来的，决定界面提示语 */
    val stage: RoundTableGapStage = RoundTableGapStage.PROPOSALS,
)

/** 界面对某个座位下的指令 */
internal sealed interface RoundTableSeatCommand {
    /**
     * 停止这个位置。
     *
     * v215：已经吐出来的半截内容会被保留成一页（默认不算进结论，用户可手动勾回），
     * 位置本身仍然在编，缺席检查点会提醒补齐。
     */
    data object Stop : RoundTableSeatCommand

    /**
     * 跳过这个位置。
     *
     * v215：本轮直接放弃这个位置，不保留半截内容，缺席检查点也不再为它提醒。
     */
    data object Skip : RoundTableSeatCommand

    /** 用同一个模型重试 */
    data object Retry : RoundTableSeatCommand

    /**
     * v223：继续输出（续跑）。
     *
     * 与 [Retry] 的根本区别：重试是**清空半截内容从头再跑**，续跑是**保留已产出内容、
     * 让模型从中断处接着往下写**。用于「模型已经跑得差不多了却被截断」的场景，
     * 避免前面的时间与花费白费。
     */
    data object Continue : RoundTableSeatCommand

    /** 换成另一个模型来坐这个位置 */
    data class Replace(val modelId: Uuid, val modelName: String) : RoundTableSeatCommand

    /** 看门狗判定长时间没有任何动静，自动停止这个位置 */
    data class Timeout(val idleMillis: Long) : RoundTableSeatCommand
}

/** 一整轮圆桌对外暴露的快照，界面只读这个 */
internal data class RoundTableRunState(
    val runId: Uuid,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val phase: RoundTableRunPhase,
    val autoSummarize: Boolean,
    val seats: List<RoundTableSeat>,
    val gapPrompt: RoundTableGapPrompt? = null,
) {
    fun seat(seatId: String): RoundTableSeat? = seats.firstOrNull { it.seatId == seatId }

    val isFinished: Boolean get() = phase == RoundTableRunPhase.FINISHED

    /** 当前阶段还在跑或还没跑的位置 */
    val activeSeats: List<RoundTableSeat> get() = seats.filterNot { it.status.isTerminal }

    val usableSeats: List<RoundTableSeat> get() = seats.filter { it.hasUsableResult }
}

/** 座位 id 生成规则，集中在一处，避免各处拼错 */
internal object RoundTableSeatIds {
    const val CONTRACT = "contract"
    const val MAIN_DRAFT = "main_draft"
    const val REBUTTAL = "rebuttal"
    const val SUMMARY = "summary"

    fun explorer(index: Int): String = "explorer:$index"

    fun explorerIndexOf(seatId: String): Int? =
        seatId.substringAfter("explorer:", "").toIntOrNull()
}

/**
 * v276：检查点是否需要停下来等用户。
 *
 * 判定以「真实可用结果集合」为准，而不是只看座位 status：
 * - [RoundTableGapStage.REVIEW]：这组座位里只要有**非「用户主动跳过」**的座位不在
 *   可用结果集合里（失败、停止、意外中断、被截断未纳入摘要等），就需要停在检查点；
 * - [RoundTableGapStage.SUMMARY]：可用结果集合为空才需要（否则拍板会重复空话）。
 */
internal fun roundTableStageNeedsReview(
    stage: RoundTableGapStage,
    seats: List<RoundTableSeat>,
    usableSeatIds: Set<String>,
): Boolean = when (stage) {
    RoundTableGapStage.SUMMARY -> usableSeatIds.isEmpty()
    else -> seats.any { it.status != RoundTableSeatStatus.SKIPPED && it.seatId !in usableSeatIds }
}

/**
 * v276：检查点上要展示为「缺席（missing）」的座位 id 集合。
 *
 * 与 [roundTableStageNeedsReview] 用同一规则：REVIEW 时「非跳过且不在可用集合」的座位，
 * SUMMARY 时「不在可用集合」的座位。界面据此显示缺了哪些位置，避免与「是否停在检查点」
 * 判定口径不一致（截断 / 失败 / 停止应当显示在检查点上）。
 */
internal fun roundTableMissingSeatIds(
    stage: RoundTableGapStage,
    seats: List<RoundTableSeat>,
    usableSeatIds: Set<String>,
): Set<String> = when (stage) {
    RoundTableGapStage.SUMMARY -> seats.map { it.seatId }
        .filter { it !in usableSeatIds }
        .toSet()
    else -> seats
        .filter { it.status != RoundTableSeatStatus.SKIPPED && it.seatId !in usableSeatIds }
        .map { it.seatId }
        .toSet()
}

/**
 * v276：续跑时「本次真正新增」的文字长度。
 *
 * 合并结果相对前缀去掉前后空白与拼接换行后，若与前缀相同或只多了空白，就是无进展 -> 返回 0。
 * 用于：续跑没有新增内容时不再判定成功；字数统计不要把旧前缀算成新模型的产出。
 */
internal fun roundTableContinuationDelta(prefix: String?, mergedText: String): Int {
    if (prefix.isNullOrBlank()) return mergedText.trim().length
    val trimmedMerged = mergedText.trim()
    val trimmedPrefix = prefix.trim()
    if (trimmedMerged.isEmpty()) return 0
    if (trimmedMerged == trimmedPrefix) return 0
    // 去掉前缀本身（容忍前缀结尾与新增开头之间的空白 / 换行），剩下的才是新增文字
    val remaining = trimmedMerged.removePrefix(trimmedPrefix).trim()
    return remaining.length
}
