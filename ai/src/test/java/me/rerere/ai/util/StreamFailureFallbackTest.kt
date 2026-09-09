package me.rerere.ai.util

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v285：流式请求失败却「既没有异常对象也读不到错误正文」时的兜底。
 *
 * 真机事故：Grok 账号额度用尽 → SSE 建流失败 → `onFailure(source, t = null, response)`，
 * 而错误正文也读不出来（`stringSafe` 只认 OkHttp 原装的 `RealResponseBody`）。
 * 旧代码执行 `close(null)`，在 `callbackFlow` 里等于「这次回答顺利结束了」——
 * 界面上是空回复、一个字都没有、零报错，自动续跑也不会触发。
 */
class StreamFailureFallbackTest {

    private fun response(code: Int, message: String = "err") = Response.Builder()
        .request(Request.Builder().url("https://example.com/v1/responses").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body("".toResponseBody(null))
        .build()

    @Test
    fun `状态码必须同时进 message 和 statusCode`() {
        // 上层的「能不能自动续跑」是按 message 里的数字判的，所以状态码不进 message 等于白做。
        val e = streamFailureFallback(response(402, "Payment Required"))
        assertEquals(402, e.statusCode)
        assertTrue("message 里必须带状态码：${e.message}", e.message!!.contains("402"))
    }

    @Test
    fun `读到了错误正文就原样带上，方便看出到底是什么问题`() {
        val body = """{"code":"personal-team-blocked:spending-limit","error":"You have run out of credits"}"""
        val e = streamFailureFallback(response(403), body)
        assertTrue(e.message!!.contains("403"))
        assertTrue(e.message!!.contains("spending-limit"))
    }

    @Test
    fun `正文是空白就不要塞进消息里，只说没有错误详情`() {
        val e = streamFailureFallback(response(429), "   \n  ")
        assertTrue(e.message!!.contains("429"))
        assertTrue(e.message!!.contains("no error detail"))
    }

    @Test
    fun `超长正文要截断，不能把整个错误页拼进消息`() {
        val e = streamFailureFallback(response(500), "x".repeat(5000))
        // 500 字正文 + 前缀，总长度必须远小于原始 5000
        assertTrue("消息长度失控：${e.message!!.length}", e.message!!.length < 600)
    }

    @Test
    fun `连响应都没有时也要给一个明确异常，绝不能返回 null`() {
        val e = streamFailureFallback(null, null)
        assertNull(e.statusCode)
        assertTrue(e.message!!.isNotBlank())
        assertTrue(e.message!!.contains("before any response"))
    }

    @Test
    fun `只有正文没有响应对象时也要把正文说出来`() {
        val e = streamFailureFallback(null, "upstream closed the stream")
        assertNull(e.statusCode)
        assertTrue(e.message!!.contains("upstream closed the stream"))
    }
}
