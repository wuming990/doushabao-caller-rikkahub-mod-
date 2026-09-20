package me.rerere.ai.ui

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v303：流处理器的时间切分单测。
 *
 * 时间源用假时钟喂进去，才能验「首字之后到写完」这一段。口径见 RoundTiming 的注释：
 * generationMs 不含首字等待，responseMs 含；思考分片必须算进「首字」。
 */
class StreamChunkHandlerTimingTest {
    private val model = Model(modelId = "test-model")

    /**
     * v303：生产代码的时间源是**纳秒**（构造器参数 nowNanos），本测试里 now 一律按毫秒读数，
     * 注入时换算成纳秒。
     *
     * 踩坑记录（审查位抓到）：第一版测试直接把毫秒数喂进去，而 getter 内部要除以 1_000_000，
     * 于是 3500ns/1e6 = 0，四条用例全挂。测试读数与生产单位必须对齐。
     */
    private fun handlerAt(clock: () -> Long) = StreamChunkHandler(model) { clock() * 1_000_000L }

    @Test
    fun `生成时间不含首字等待 响应时间含首字等待`() {
        var now = 0L
        val handler = handlerAt { now }
        var messages = listOf(UIMessage.user("hi"))

        now = 1_000L
        messages = handler.handle(messages, StreamChunk.TextDelta("t1", "你"))
        now = 3_000L
        messages = handler.handle(messages, StreamChunk.TextDelta("t1", "好"))
        now = 3_500L
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        val timing = handler.roundTiming
        assertEquals(3_500L, timing.responseMs)
        assertEquals(2_500L, timing.generationMs)
    }

    @Test
    fun `思考分片也算首字 否则生成速度会虚高`() {
        var now = 0L
        val handler = handlerAt { now }
        var messages = listOf(UIMessage.user("hi"))

        now = 200L
        messages = handler.handle(messages, StreamChunk.ReasoningDelta("r1", "想"))
        now = 1_200L
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        assertEquals(1_000L, handler.roundTiming.generationMs)
    }

    @Test
    fun `只有用量和收尾分片时 没有生成时间`() {
        var now = 0L
        val handler = handlerAt { now }
        var messages = listOf(UIMessage.user("hi"))

        now = 100L
        messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(completionTokens = 5)))
        now = 200L
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        assertNull(handler.roundTiming.generationMs)
        assertEquals(200L, handler.roundTiming.responseMs)
    }

    @Test
    fun `没有 Finish 时在第一次读取时冻结 不再继续涨`() {
        var now = 0L
        val handler = handlerAt { now }
        var messages = listOf(UIMessage.user("hi"))

        now = 500L
        messages = handler.handle(messages, StreamChunk.TextDelta("t1", "半截"))
        now = 800L
        val first = handler.roundTiming
        now = 9_999L
        val second = handler.roundTiming

        assertEquals(first, second)
        assertEquals(300L, first.generationMs)
    }
}
