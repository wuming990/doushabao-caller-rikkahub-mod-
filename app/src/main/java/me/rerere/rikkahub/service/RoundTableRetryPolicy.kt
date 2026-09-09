package me.rerere.rikkahub.service

/** 再试也一样的错误（鉴权、余额、参数、内容策略、模型不存在），重试与续跑共用 */
private val NOT_RETRYABLE_MARKERS = listOf(
    "unauthorized",
    "authentication",
    "invalid api key",
    "invalid_api_key",
    "api key",
    "permission",
    "forbidden",
    "insufficient",
    "quota",
    "billing",
    "balance",
    "payment",
    "arrearage",
    "unsupported",
    "not support",
    "invalid parameter",
    "invalid_request_error",
    "model not found",
    "model_not_found",
    "does not exist",
    "content policy",
    "content_filter",
    "safety",
    "余额",
    "欠费",
    "配额",
    "未授权",
    "无权限",
    "不支持",
    "密钥",
)

private fun isNotRetryable(error: Throwable?): Boolean {
    val text = buildString {
        append(error?.message.orEmpty())
        append(' ')
        append(error?.cause?.message.orEmpty())
        append(' ')
        append(error?.javaClass?.name.orEmpty())
    }.lowercase()
    return NOT_RETRYABLE_MARKERS.any { text.contains(it) }
}

/**
 * v213：某个圆桌位置失败后，要不要自动重来一次。
 *
 * 三条硬规则（都是用户明确要求的"稳定优先"）：
 * 1. **已经吐出内容再断线的，绝不自动重试**——否则会出现两段半截回答，还要多付一次钱；
 * 2. 一个位置最多自动重来一次，之后交给用户决定重试、换模型还是跳过；
 * 3. 鉴权、余额、参数不支持这类"再试也一样"的错误不重试，直接提示换模型或改配置。
 */
internal enum class RoundTableRetryVerdict {
    /** 可以自动重来一次 */
    AUTO_RETRY,

    /** 已经产出过内容，重试会重复收费并产生重复答案 */
    ALREADY_STREAMED,

    /** 这个位置的自动重试次数用完了 */
    RETRY_BUDGET_USED,

    /** 配置类错误，重试没有意义 */
    NOT_RETRYABLE,
    ;

    val shouldRetry: Boolean get() = this == AUTO_RETRY
}

internal object RoundTableRetryPolicy {
    /** 每个位置最多自动重来一次 */
    const val MAX_AUTO_RETRIES = 1

    fun evaluate(seat: RoundTableSeat, error: Throwable?): RoundTableRetryVerdict {
        // 已经在屏幕上吐过字：重试会出现重复的半截回答
        if (seat.chars > 0) return RoundTableRetryVerdict.ALREADY_STREAMED
        if (seat.autoRetries >= MAX_AUTO_RETRIES) return RoundTableRetryVerdict.RETRY_BUDGET_USED

        if (isNotRetryable(error)) {
            return RoundTableRetryVerdict.NOT_RETRYABLE
        }
        return RoundTableRetryVerdict.AUTO_RETRY
    }
}

/**
 * v229：某个位置断了 / 被截断 / 卡死之后，要不要**自动断点续跑**。
 *
 * 与 [RoundTableRetryPolicy] 的根本区别：重试是**清空半截内容从头再跑**，
 * 续跑是**保留已产出内容、让模型从中断处接着写**，不丢前面的时间与花费。
 *
 * 用户明确定下的四条规则：
 * 1. 截断、断线、卡死都自动续跑，最多 [MAX_AUTO_CONTINUATIONS] 次，用完就停下等人；
 * 2. **「疑似无效」（太短 / 复述角色说明 / 只说要去做什么）绝不自动续跑** ——
 *    强制续写会让模型在一点点内容上硬编，产生噪音；必须整场停下来等用户看过再决定
 *    算不算进方案；
 * 3. 用户主动按的停止不自动续跑（那是明确的人为意图）；
 * 4. 鉴权 / 余额 / 参数这类「再试也一样」的错误不续跑。
 */
internal enum class RoundTableAutoContinueVerdict {
    /** 可以自动接着写 */
    AUTO_CONTINUE,

    /** 没有半截内容，也不是卡死，没什么可接着写的 */
    NO_PARTIAL,

    /** 自动续跑次数用完了，交给用户决定 */
    BUDGET_USED,

    /** 用户自己按的停止，不能替他自动接着写 */
    USER_STOPPED,

    /** 配置类错误，续跑没有意义 */
    NOT_RETRYABLE,

    /** 疑似无效：按用户要求不自动续跑，整场停下来等他决策 */
    NEEDS_REVIEW,
    ;

    val shouldContinue: Boolean get() = this == AUTO_CONTINUE
}

internal object RoundTableAutoContinuePolicy {
    /** 每个位置最多自动续跑两次，之后停下等用户 */
    const val MAX_AUTO_CONTINUATIONS = 2

    /**
     * @param partialText 这个位置目前留下的半截内容（可能为空）
     * @param timedOut 是否为看门狗判定的「长时间没动静」。用户要求卡死也自动救两次，
     *   因此即使一个字都没吐出来也允许再来一次（此时没有前缀，等于重跑）。
     */
    fun evaluate(
        seat: RoundTableSeat,
        partialText: String,
        error: Throwable?,
        timedOut: Boolean,
    ): RoundTableAutoContinueVerdict {
        if (seat.status == RoundTableSeatStatus.STOPPED ||
            seat.status == RoundTableSeatStatus.SKIPPED
        ) {
            return RoundTableAutoContinueVerdict.USER_STOPPED
        }
        if (seat.status == RoundTableSeatStatus.SUSPECT || seat.suspectReason != null) {
            return RoundTableAutoContinueVerdict.NEEDS_REVIEW
        }
        if (seat.autoContinuations >= MAX_AUTO_CONTINUATIONS) {
            return RoundTableAutoContinueVerdict.BUDGET_USED
        }
        if (isNotRetryable(error)) {
            return RoundTableAutoContinueVerdict.NOT_RETRYABLE
        }
        if (partialText.isBlank() && !timedOut) {
            return RoundTableAutoContinueVerdict.NO_PARTIAL
        }
        return RoundTableAutoContinueVerdict.AUTO_CONTINUE
    }
}

/**
 * v213：什么时候认为一个位置"卡住了"。
 *
 * 只看"有没有动静"，不看字数：MAX 档模型可能长时间只在思考、不输出正文，
 * 因此调用方在收到任何流式更新（正文、思考、工具调用）时都要刷新活动时间。
 */
internal object RoundTableStallPolicy {
    /** 超过这个时间没动静就在界面上提示"等待较久"，但不动它 */
    const val SOFT_IDLE_MILLIS = 2 * 60 * 1000L

    /** 超过这个时间没动静就自动停止这个位置，并停在缺席检查点等用户 */
    const val HARD_IDLE_MILLIS = 20 * 60 * 1000L

    /** 开了最高思考档时放宽 */
    const val HARD_IDLE_MILLIS_MAX_EFFORT = 30 * 60 * 1000L

    /** 单个位置一次尝试的总时长上限 */
    const val HARD_TOTAL_MILLIS = 60 * 60 * 1000L

    const val HARD_TOTAL_MILLIS_MAX_EFFORT = 90 * 60 * 1000L

    /** 看门狗多久检查一次 */
    const val CHECK_INTERVAL_MILLIS = 15 * 1000L

    fun hardIdleLimit(maxEffort: Boolean): Long =
        if (maxEffort) HARD_IDLE_MILLIS_MAX_EFFORT else HARD_IDLE_MILLIS

    fun hardTotalLimit(maxEffort: Boolean): Long =
        if (maxEffort) HARD_TOTAL_MILLIS_MAX_EFFORT else HARD_TOTAL_MILLIS

    /** 只是"久了"，界面提示用 */
    fun isSoftStalled(seat: RoundTableSeat, nowMillis: Long): Boolean =
        seat.status == RoundTableSeatStatus.RUNNING && seat.idleMillis(nowMillis) >= SOFT_IDLE_MILLIS

    /** 该自动停手了 */
    fun shouldForceStop(seat: RoundTableSeat, nowMillis: Long, maxEffort: Boolean): Boolean {
        if (seat.status != RoundTableSeatStatus.RUNNING) return false
        return seat.idleMillis(nowMillis) >= hardIdleLimit(maxEffort) ||
            seat.elapsedMillis(nowMillis) >= hardTotalLimit(maxEffort)
    }
}
