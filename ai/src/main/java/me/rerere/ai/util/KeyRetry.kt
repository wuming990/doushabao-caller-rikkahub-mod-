package me.rerere.ai.util

import kotlinx.coroutines.CancellationException

/** v255：密钥类错误的 HTTP 状态码（余额不足 402 / 鉴权失败 401 / 无权限 403 / 限速 429） */
internal val KEY_RETRYABLE_CODES = setOf(401, 402, 403, 429)

private val KEY_ERROR_CODE_REGEX = Regex("\\b(4\\d\\d)\\b")

/**
 * v255：判断一个异常是不是「换一个 key 重试有意义」的密钥类错误。
 * 余额不足 / 鉴权失败 / 限速都算；网络中断、5xx、模型不存在等换 key 没用，不算。
 */
fun isKeyRetryableError(e: Throwable): Boolean {
    val msg = e.message?.lowercase().orEmpty()
    if (msg.isBlank()) return false
    // 非流式路径的错误信息带状态码，如 "Failed to get response: 402 ..."
    val code = KEY_ERROR_CODE_REGEX.find(msg)?.groupValues?.get(1)?.toIntOrNull()
    if (code != null && code in KEY_RETRYABLE_CODES) return true
    val keywords = listOf(
        "insufficient", "quota", "balance", "余额", "配额", "欠费",
        "unauthorized", "invalid api key", "invalid_api_key", "authentication",
        "rate limit", "too many requests",
        // v290：Kimi 等服务商的按模型限速写法是「RPM (Requests Per Minute) limit ... is exceeded」，
        // 既不含 "rate limit" 也没有独立的 429 数字（Request id 是连续串，词边界正则提不出状态码），
        // 真机实锤：多把密钥一次都没换、续跑也不触发，直接弹「消息生成失败」。
        "requests per minute",
        // v291：TPM/天配额/节流的其他常见写法（真机实锤："inference tpm exhausted" 三道关卡全漏）。
        // 全是带语义的短语，不用裸缩写（避免 "rpm" 这类词出现在别处误伤）。
        "tpm exhausted", "rpm exhausted", "qpm exhausted",
        "tokens per minute", "requests per day", "tokens per day", "throttled",
        // v292：中转站的 key 状态文案（真机实锤：三道关卡全漏，零输出直接报错）
        "权重不可用",
    )
    return keywords.any { msg.contains(it) }
}

/**
 * v290：流式路径的换 key 决策（纯函数，可单测）。
 *
 * 三个条件同时满足才允许换下一把 key 重发：
 * - [emittedAny] 为 false —— 只要已经对用户吐出过内容，换 key 重发必然前半段重复
 *   （v285 定下的规矩：一个字没吐才重试，吐了以后交给上层续跑机制）；
 * - [keyCount] 大于 1 —— 只有一把 key 时换无可换；
 * - [switchCount] 未达 [keyCount] - 1 —— **把整个密钥池轮完为止**（每把都试一遍，
 *   用户拍板：10 把就轮 10 把，不做保守截断）。池子轮完后这一趟放弃，错误交给上层
 *   续跑 / 网络重试 —— 新的一趟请求会重新从轮询器取 key 开始轮，5 分钟冷却期满的
 *   key 自动恢复参与选择（用户原话：「后面的也用完了但是前面的又能用了……重新轮询
 *   一遍，如果全部用不了了续跑次数到底自然会停下来」）。
 */
internal fun canRetryWithNextKey(switchCount: Int, keyCount: Int, emittedAny: Boolean): Boolean {
    if (emittedAny) return false
    if (keyCount <= 1) return false
    return switchCount < keyCount - 1
}

/**
 * v255：用轮询器取 key 执行 [attempt]；遇到密钥类错误自动标记失败并换下一个 key 重试一次。
 * 最多尝试 2 个不同的 key；只有 1 个 key 时等价于「失败后重试一次」。
 * 协程取消原样上抛，绝不吞掉。
 */
suspend fun <T> KeyRoulette.withKeyRetry(
    keys: String,
    providerId: String,
    attempt: suspend (key: String) -> T,
): T {
    var lastError: Throwable? = null
    repeat(2) {
        val key = next(keys, providerId)
        try {
            return attempt(key)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            lastError = e
            if (!isKeyRetryableError(e)) throw e
            markFailed(providerId, key)
        }
    }
    throw lastError ?: IllegalStateException("unreachable")
}
