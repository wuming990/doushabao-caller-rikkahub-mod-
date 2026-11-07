package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/** Real context measurements after a classic checkpoint; no network or fake model responses. */
class V1019ClassicContextTokensTest {
    private val before = LocalDateTime(2026, 10, 27, 10, 0)
    private val checkpointTime = LocalDateTime(2026, 10, 27, 11, 0)
    private val after = LocalDateTime(2026, 10, 27, 12, 0)

    private fun oldReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("a")),
        createdAt = before,
        usage = TokenUsage(promptTokens = 899_000, completionTokens = 1_000),
    )

    private fun checkpoint() = UIMessage.user("s").copy(
        createdAt = checkpointTime,
        isContextCheckpoint = true,
    )

    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `old usage is invalid after compressing all history`() {
        assertNull(reportedConversationTokens(conversation(oldReply(), checkpoint())))
    }

    @Test
    fun `retained old replies do not bring pre-compression usage back`() {
        assertNull(reportedConversationTokens(conversation(oldReply(), checkpoint(), oldReply())))
    }

    @Test
    fun `first request made after checkpoint supplies fresh reported usage`() {
        val fresh = oldReply().copy(
            createdAt = after,
            usage = TokenUsage(promptTokens = 123, completionTokens = 4),
        )
        assertEquals(127L, reportedConversationTokens(conversation(oldReply(), checkpoint(), fresh)))
    }

    @Test
    fun `estimate after full compression counts only the summary`() {
        // "[USER]: s" is 9 UTF-8 bytes: ceil(9 / 4) = 3.
        assertEquals(3L, estimateConversationTokensOf(conversation(oldReply(), checkpoint())))
    }

    @Test
    fun `local estimate excludes archived history but includes retained tail`() {
        // Summary is 3 tokens; "[ASSISTANT]: a" is 14 bytes, rounded up to 4.
        assertEquals(7L, approxConversationTokens(conversation(oldReply(), checkpoint(), oldReply())))
    }

    @Test
    fun `only latest checkpoint contributes to local estimate`() {
        val older = checkpoint().copy(parts = listOf(UIMessagePart.Text("x".repeat(1000))), createdAt = before)
        assertEquals(3L, approxConversationTokens(conversation(oldReply(), older, oldReply(), checkpoint())))
    }

    @Test
    fun `smart history without official checkpoint keeps reported usage unchanged`() {
        assertEquals(900_000L, reportedConversationTokens(conversation(oldReply())))
    }

    @Test
    fun `smart history without checkpoint keeps existing local estimate`() {
        assertEquals(3L, approxConversationTokens(conversation(UIMessage.user("s"))))
    }
}
