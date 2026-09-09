package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.COMPRESS_APPROX_BYTES_PER_TOKEN
import me.rerere.rikkahub.data.ai.prompts.COMPRESS_RETAINED_USER_MESSAGE_MAX_TOKENS
import me.rerere.rikkahub.data.ai.prompts.COMPRESS_SUMMARY_PREFIX
import java.util.Locale

/**
 * 照抄 Codex 的 compaction 辅助逻辑, 与 UI/网络无关, 便于单元测试。
 *
 * 参考: codex-rs/core/src/compact.rs, codex-rs/utils/string/src/truncate.rs
 */
object CodexCompaction {

    /** Codex approx_token_count: UTF-8 字节数 / 4, 向上取整。 */
    fun approxTokenCount(text: String): Int {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return (bytes + COMPRESS_APPROX_BYTES_PER_TOKEN - 1) / COMPRESS_APPROX_BYTES_PER_TOKEN
    }

    /** Codex model_auto_compact_token_limit: contextWindow * 9 / 10。 */
    fun autoCompactTokenLimit(contextWindow: Int): Long =
        contextWindow.toLong() * 9 / 10

    /** Codex truncate_middle_with_token_budget: 保留首尾, 中间替换为 token 截断标记。 */
    fun truncateMiddleByTokenBudget(text: String, maxTokens: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (maxTokens <= 0) {
            return truncationMarker(bytes.size)
        }
        val maxBytes = maxTokens * COMPRESS_APPROX_BYTES_PER_TOKEN
        if (bytes.size <= maxBytes) return text
        val leftBytes = maxBytes / 2
        val rightBytes = maxBytes - leftBytes
        val head = decodeWithoutPartialChars(bytes, 0, leftBytes)
        val tailStart = (bytes.size - rightBytes).coerceAtLeast(0)
        val tail = decodeWithoutPartialChars(bytes, tailStart, bytes.size - tailStart)
        return head + truncationMarker(bytes.size - maxBytes) + tail
    }

    /**
     * Codex build_compacted_history: 压缩后只保留真实用户消息文本,
     * 从最新往前累计, 总预算 20k token, 超出预算的那条做中间截断。
     */
    fun retainedUserMessages(
        messages: List<UIMessage>,
        maxTokens: Int = COMPRESS_RETAINED_USER_MESSAGE_MAX_TOKENS,
    ): List<String> {
        val userTexts = messages
            .filter { it.role == MessageRole.USER }
            .map { it.toText().trim() }
            .filter { it.isNotBlank() && !isSummaryMessage(it) }
        val selected = mutableListOf<String>()
        var remaining = maxTokens
        for (text in userTexts.asReversed()) {
            if (remaining <= 0) break
            val tokens = approxTokenCount(text)
            if (tokens <= remaining) {
                selected += text
                remaining -= tokens
            } else {
                selected += truncateMiddleByTokenBudget(text, remaining)
                break
            }
        }
        return selected.asReversed()
    }

    /** Codex is_summary_message: 摘要以固定前缀开头。旧版二改用过 [Context checkpoint]，一并识别。 */
    fun isSummaryMessage(text: String): Boolean =
        text.startsWith(COMPRESS_SUMMARY_PREFIX) || text.startsWith("[Context checkpoint]")

    /** Codex SUMMARY_PREFIX: 摘要保存为 user message 时统一加前缀。 */
    fun withSummaryPrefix(summary: String): String {
        val trimmed = summary.trim()
        return if (trimmed.startsWith(COMPRESS_SUMMARY_PREFIX)) {
            trimmed
        } else {
            "$COMPRESS_SUMMARY_PREFIX\n$trimmed"
        }
    }

    /**
     * 各 Provider 的上下文超限报错文案不统一, 按常见关键字判定;
     * 命中后按 Codex 的做法丢弃最旧历史重试, 而不是直接失败。
     */
    fun isContextWindowExceeded(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (message.isBlank()) return false
        return CONTEXT_WINDOW_ERROR_KEYWORDS.any { message.contains(it) }
    }

    private val CONTEXT_WINDOW_ERROR_KEYWORDS = listOf(
        "context length",
        "context_length",
        "context window",
        "context_window",
        "maximum context",
        "too many tokens",
        "reduce the length",
        "prompt is too long",
        "input length",
        "string too long",
        "request too large",
    )

    private fun truncationMarker(removedBytes: Int): String {
        val removedTokens =
            (removedBytes + COMPRESS_APPROX_BYTES_PER_TOKEN - 1) / COMPRESS_APPROX_BYTES_PER_TOKEN
        return "…$removedTokens tokens truncated…"
    }

    /** 按字节切分可能切断多字节字符, 解码后去掉替换字符, 避免产生乱码。 */
    private fun decodeWithoutPartialChars(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.UTF_8).trim('\uFFFD')
}
