package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.COMPRESS_SUMMARY_PREFIX
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.isLegacyCompressPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCompactionTest {

    @Test
    fun `approx token count matches codex four bytes per token`() {
        assertEquals(0, CodexCompaction.approxTokenCount(""))
        assertEquals(1, CodexCompaction.approxTokenCount("abcd"))
        assertEquals(2, CodexCompaction.approxTokenCount("abcde"))
        // 中文按 UTF-8 3 字节计, 2 个汉字 = 6 字节 = 2 token
        assertEquals(2, CodexCompaction.approxTokenCount("你好"))
    }

    @Test
    fun `auto compact limit is ninety percent of context window`() {
        assertEquals(28_800L, CodexCompaction.autoCompactTokenLimit(32_000))
        assertEquals(225_000L, CodexCompaction.autoCompactTokenLimit(250_000))
        assertEquals(900_000L, CodexCompaction.autoCompactTokenLimit(1_000_000))
    }

    @Test
    fun `truncate keeps head and tail with token marker`() {
        val text = "a".repeat(400)
        val truncated = CodexCompaction.truncateMiddleByTokenBudget(text, maxTokens = 10)
        assertTrue(truncated.contains("tokens truncated"))
        assertTrue(truncated.length < text.length)
        assertTrue(truncated.startsWith("a"))
        assertTrue(truncated.endsWith("a"))
    }

    @Test
    fun `truncate returns original text when inside budget`() {
        val text = "short text"
        assertEquals(text, CodexCompaction.truncateMiddleByTokenBudget(text, maxTokens = 100))
    }

    @Test
    fun `retained user messages keep newest first within budget`() {
        val messages = listOf(
            UIMessage.user("oldest"),
            UIMessage.assistant("assistant reply should be dropped"),
            UIMessage.user("newest"),
        )

        val retained = CodexCompaction.retainedUserMessages(messages, maxTokens = 100)

        assertEquals(listOf("oldest", "newest"), retained)
    }

    @Test
    fun `retained user messages drop older entries when budget is spent`() {
        val big = "x".repeat(400) // 100 tokens
        val messages = listOf(
            UIMessage.user("dropped"),
            UIMessage.user(big),
        )

        val retained = CodexCompaction.retainedUserMessages(messages, maxTokens = 100)

        assertEquals(listOf(big), retained)
    }

    @Test
    fun `retained user messages exclude previous summaries`() {
        val summary = CodexCompaction.withSummaryPrefix("previous summary")
        val messages = listOf(
            UIMessage.user(summary),
            UIMessage.user("[Context checkpoint]\nlegacy summary"),
            UIMessage.user("real message"),
        )

        val retained = CodexCompaction.retainedUserMessages(messages, maxTokens = 1_000)

        assertEquals(listOf("real message"), retained)
    }

    @Test
    fun `summary prefix is added once`() {
        val once = CodexCompaction.withSummaryPrefix("summary body")
        val twice = CodexCompaction.withSummaryPrefix(once)

        assertTrue(once.startsWith(COMPRESS_SUMMARY_PREFIX))
        assertEquals(once, twice)
        assertTrue(CodexCompaction.isSummaryMessage(once))
    }

    @Test
    fun `context window errors are detected from nested causes`() {
        val nested = IllegalStateException(
            "request failed",
            IllegalArgumentException("This model's maximum context length is 128000 tokens"),
        )

        assertTrue(CodexCompaction.isContextWindowExceeded(nested))
        assertFalse(CodexCompaction.isContextWindowExceeded(IllegalStateException("invalid api key")))
        assertFalse(CodexCompaction.isContextWindowExceeded(IllegalStateException(null as String?)))
    }

    @Test
    fun `legacy compress prompts with placeholders are detected`() {
        assertTrue(isLegacyCompressPrompt("Write in {locale}. Target {target_tokens} tokens. {content}"))
        assertTrue(isLegacyCompressPrompt("Start exactly with: [Context checkpoint]"))
        assertFalse(isLegacyCompressPrompt(DEFAULT_COMPRESS_PROMPT))
        assertFalse(isLegacyCompressPrompt("Custom prompt without old markers"))
    }
}
