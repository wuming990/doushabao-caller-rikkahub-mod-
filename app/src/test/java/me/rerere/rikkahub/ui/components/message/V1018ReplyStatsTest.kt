package me.rerere.rikkahub.ui.components.message

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1018: exercise the actual read model used by the footer and its usage panel. */
class V1018ReplyStatsTest {
    private fun reply() = UIMessage.assistant("Answer after three model requests").copy(
        createdAt = LocalDateTime(2026, 10, 27, 10, 0, 0),
        finishedAt = LocalDateTime(2026, 10, 27, 10, 3, 0),
        usage = TokenUsage(4000, 300, 400, 4300, 30),
        cumulativeUsage = TokenUsage(7000, 600, 700, 7600, 60),
        cumulativeGenerationMs = 3000L,
        cumulativeResponseMs = 6000L,
        cumulativeRequestCount = 3,
        stepCount = 3,
    )

    // Keep production visibility unchanged: this is the same projection called by Compose.
    private fun stats(message: UIMessage): Any {
        val owner = Class.forName("me.rerere.rikkahub.ui.components.message.ChatMessageNerdLineKt")
        val method = owner.getDeclaredMethod("nerdStats", UIMessage::class.java, TokenUsage::class.java)
        method.isAccessible = true
        return method.invoke(null, message, message.usage ?: message.cumulativeUsage ?: TokenUsage())
    }

    private fun Any.field(name: String): Any? {
        val getter = javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
        getter.isAccessible = true
        return getter.invoke(this)
    }

    @Test
    fun `panel totals include all model requests instead of only the final output`() {
        val total = stats(reply()).field("total") as TokenUsage
        assertEquals(7000, total.promptTokens)
        assertEquals(600, total.completionTokens)
        assertEquals(700, total.cachedTokens)
        assertEquals(60, total.reasoningTokens)
        assertEquals(7600, total.totalTokens)
    }

    @Test
    fun `context occupancy remains the last request instead of the sum of billed inputs`() {
        val stats = stats(reply())
        val last = stats.field("lastUsage") as TokenUsage
        assertEquals(4000, last.promptTokens)
        assertEquals(300, last.completionTokens)
        assertEquals(4300, stats.field("contextTokens"))
        assertEquals(3, stats.field("steps"))
    }

    @Test
    fun `official messages without the mod ledger keep their recorded total and last context`() {
        val message = reply().copy(
            usage = TokenUsage(7000, 600, 700, 7600, 60),
            cumulativeUsage = null,
            lastUsage = TokenUsage(4000, 300, 400, 4300, 30),
        )
        val stats = stats(message)
        assertEquals(7000, (stats.field("total") as TokenUsage).promptTokens)
        assertEquals(600, (stats.field("total") as TokenUsage).completionTokens)
        assertEquals(4300, stats.field("contextTokens"))
    }

    @Test
    fun `an existing cumulative ledger is selected once and never added to usage again`() {
        val message = reply().copy(usage = TokenUsage(7000, 600, 700, 7600, 60))
        val total = stats(message).field("total") as TokenUsage
        assertEquals(7000, total.promptTokens)
        assertEquals(600, total.completionTokens)
    }

    @Test
    fun `legacy replies with no cumulative ledger still show their available usage`() {
        val message = reply().copy(
            usage = TokenUsage(1500, 200, 100, 1700),
            cumulativeUsage = null,
            cumulativeResponseMs = null,
            cumulativeGenerationMs = null,
        )
        val total = stats(message).field("total") as TokenUsage
        assertEquals(1500, total.promptTokens)
        assertEquals(200, total.completionTokens)
    }

    @Test
    fun `footer shows response speed including request startup instead of generation speed`() {
        val stats = stats(reply())
        // All requests: 600 output tokens / 6 response seconds = 100, not 600 / 3 = 200.
        assertEquals(100f, stats.field("tps") as Float, 0.001f)
    }

    @Test
    fun `expanded panel retains both speed measurements without tool waiting in the denominator`() {
        val readout = SpeedReadout.of(reply(), TokenUsage(7000, 600, 700, 7600, 60))!!
        assertEquals(200f, readout.generationTps!!, 0.001f)
        assertEquals(100f, readout.responseTps!!, 0.001f)
        assertEquals(180f, readout.seconds!!, 0.001f)
    }

    @Test
    fun `missing response timing does not relabel generation speed as response speed`() {
        val message = reply().copy(cumulativeResponseMs = null)
        assertNull(stats(message).field("tps"))
        assertEquals(200f, SpeedReadout.of(message, message.cumulativeUsage!!)!!.generationTps!!, 0.001f)
    }

    @Test
    fun `zero response time does not invent a footer speed`() {
        assertNull(stats(reply().copy(cumulativeResponseMs = 0L)).field("tps"))
    }

    @Test
    fun `unfinished replies do not show a completed response speed`() {
        assertNull(stats(reply().copy(finishedAt = null)).field("tps"))
    }
}
