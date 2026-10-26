package me.rerere.rikkahub.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1007：Gemini 网页版「从剪贴板填入」的解析。
 *
 * 用户从哪里复制都有可能，所以这里锁死三件事：
 * 1. 三种常见形态（请求头 / JSON / 换行）都能挑出 PSID 与 PSIDTS；
 * 2. 无关键（NID、AEC……）会被丢掉，不会原样塞进请求头；
 * 3. 找不到 `__Secure-1PSID` 时必须返回 null —— 界面据此提示用户，
 *    而不是把一段垃圾发出去换一个看不懂的 400。
 */
class GeminiWebCookieNormalizeTest {

    @Test
    fun `完整请求头形态`() {
        val raw = "Cookie: NID=123; __Secure-1PSID=g.a000abc-def_ghi; AEC=xyz; " +
            "__Secure-1PSIDTS=sidts-1749; SAPISID=AbC123"
        assertEquals(
            "__Secure-1PSID=g.a000abc-def_ghi; __Secure-1PSIDTS=sidts-1749; SAPISID=AbC123",
            normalizeGeminiWebCookie(raw),
        )
    }

    @Test
    fun `开发者工具复制的 JSON 形态`() {
        val raw = """{"__Secure-1PSID":"g.a000json","__Secure-1PSIDTS":"sidts-json","NID":"n"}"""
        assertEquals(
            "__Secure-1PSID=g.a000json; __Secure-1PSIDTS=sidts-json",
            normalizeGeminiWebCookie(raw),
        )
    }

    @Test
    fun `换行分隔且键顺序颠倒`() {
        val raw = "__Secure-1PSIDTS=sidts-1\nSAPISID=zzz\n__Secure-1PSID=g.a000x\n"
        assertEquals(
            "__Secure-1PSID=g.a000x; __Secure-1PSIDTS=sidts-1; SAPISID=zzz",
            normalizeGeminiWebCookie(raw),
        )
    }

    @Test
    fun `只有 PSIDTS 不算登录态`() {
        // __Secure-1PSID 是 __Secure-1PSIDTS 的前缀，不能误判成找到了 PSID
        assertNull(normalizeGeminiWebCookie("__Secure-1PSIDTS=sidts-only; SAPISID=q"))
    }

    @Test
    fun `不会被 Secure-3PSAPISID 抢走 SAPISID`() {
        // 真机 Cookie 里 __Secure-1PSAPISID / __Secure-3PSAPISID 是常见项，
        // 它们的名字里字面包含 "SAPISID=" —— 左边界写错就会把值抓错，
        // 界面还提示成功，实际请求 401/403。
        val raw = "__Secure-1PSID=g.a; __Secure-3PSAPISID=WRONG1; " +
            "__Secure-1PSAPISID=WRONG2; SAPISID=RIGHT"
        assertEquals(
            "__Secure-1PSID=g.a; SAPISID=RIGHT",
            normalizeGeminiWebCookie(raw),
        )
    }

    @Test
    fun `只有 PSAPISID 时不要伪造出 SAPISID`() {
        assertEquals(
            "__Secure-1PSID=g.a",
            normalizeGeminiWebCookie("__Secure-1PSID=g.a; __Secure-3PSAPISID=WRONG"),
        )
    }

    @Test
    fun `空内容返回 null`() {
        assertNull(normalizeGeminiWebCookie(""))
        assertNull(normalizeGeminiWebCookie("   "))
    }
}
