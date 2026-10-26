package me.rerere.rikkahub.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1016：Gemini 网页版「一键登录」的**判据单测**。
 *
 * 这些是行为断言，不是 grep 源码的守卫：登录页能不能真的成，最终只能靠真机；但
 * 「什么算登录成功」「浏览器标识怎么伪装」「Cookie 该怎么合」「被拦截了认不认得出来」
 * 这四件事一旦写错，功能会以"登录成功但一发就 403"这种最难查的形态坏掉。
 * 所以把判据全部拆成纯函数并钉在这里。
 */
class GeminiWebLoginLogicTest {

    /** 真机 Android WebView 的默认 UA 形态（带 wv 标记与 Version/4.0 伪装前缀）。 */
    private val androidWebViewUa =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"

    private val longPsid = "ApsidvalueA" + "0".repeat(40)

    private fun cookieWithPsid() = "__Secure-1PSID=$longPsid; __Secure-1PSIDTS=tsvalue; SAPISID=sapisidvalue"

    @Test
    fun `伪装浏览器标识时去掉 wv 标记和 WebView 前缀`() {
        val patched = GeminiWebLogin.webViewUserAgent(androidWebViewUa)
        assertFalse("wv 标记没去掉，Google 仍会认出内置浏览器", patched.contains("; wv"))
        assertFalse("Version/4.0 前缀留着等于自报 WebView", patched.contains("Version/4.0"))
        assertTrue("Chrome 真实版本号必须原样保留", patched.contains("Chrome/120.0.6099.230"))
        assertTrue(patched.startsWith("Mozilla/5.0 (Linux; Android 13; Pixel 7)"))
    }

    @Test
    fun `UA 里没有 Chrome 段时整条换成现代 Chrome 移动标识`() {
        val patched = GeminiWebLogin.webViewUserAgent(
            "Mozilla/5.0 (Linux; U; Android 4.4; zh-cn; MI 3 Build/KOT49H) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"
        )
        assertTrue("补不出 Chrome 段就没法伪装", patched.contains("Chrome/"))
        assertFalse(patched.contains("Version/4.0"))
    }

    @Test
    fun `空 UA 也不能返回空`() {
        val patched = GeminiWebLogin.webViewUserAgent(null)
        assertTrue(patched.contains("Chrome/"))
    }

    @Test
    fun `能解析出 Chrome 主版本号`() {
        assertEquals("120", GeminiWebLogin.chromeMajorVersion(androidWebViewUa))
        assertNull(GeminiWebLogin.chromeMajorVersion("Mozilla/5.0 (Macintosh) Safari/605.1.15"))
        assertNull(GeminiWebLogin.chromeMajorVersion(null))
    }

    @Test
    fun `Client-Hints 脚本与 UA 同源且不是 javascript 前缀`() {
        val patched = GeminiWebLogin.webViewUserAgent(androidWebViewUa)
        val js = GeminiWebLogin.clientHintsShimJs(patched)
        check(js != null) { "拿不到 Chrome 版本，说明注入脚本这条路整条断了" }
        // evaluateJavascript 不接受 javascript: 前缀，带了就是整句静默失效。
        assertFalse("注入脚本不能带 javascript: 前缀", js.startsWith("javascript:"))
        assertTrue("必须覆写 userAgentData，否则 JS 侧仍暴露 Android WebView", js.contains("userAgentData"))
        assertTrue("版本号要和 UA 一致", js.contains("120.0.6099.230"))
        assertFalse("脚本里不能反过来出现 Android WebView", js.contains("Android WebView"))
    }

    @Test
    fun `拿不到 Chrome 版本时宁可不注入`() {
        assertNull(GeminiWebLogin.clientHintsShimJs("Mozilla/5.0 (Macintosh) Safari/605.1.15"))
    }

    @Test
    fun `取 Cookie 键不会把 PSIDTS 的值当成 PSID`() {
        // 这条是本族最阴的坑：__Secure-1PSIDTS 字面上就**包含** __Secure-1PSID。
        val header = "__Secure-1PSID=$longPsid; __Secure-1PSIDTS=tsvalue"
        assertEquals(longPsid, GeminiWebLogin.cookieValue(header, GeminiWebLogin.KEY_PSID))
        assertEquals("tsvalue", GeminiWebLogin.cookieValue(header, GeminiWebLogin.KEY_PSIDTS))
        assertNull(GeminiWebLogin.cookieValue(header, "NO_SUCH_KEY"))
    }

    @Test
    fun `多域 Cookie 合并时先读的域优先并丢掉空值`() {
        val merged = GeminiWebLogin.mergeCookieHeaders(
            listOf(
                "__Secure-1PSID=$longPsid; NID=abc",
                "NID=should-lose; SAPISID=sapisidvalue",
                null,
                "",
                "brokenline",
            ),
        )
        assertTrue(merged!!.startsWith("__Secure-1PSID=$longPsid"))
        assertTrue("gemini 域先读，NID 必须用第一个域的值", merged.contains("NID=abc"))
        assertFalse("后到域的同名键不能覆盖先到域", merged.contains("should-lose"))
        assertTrue(merged.contains("SAPISID=sapisidvalue"))
    }

    @Test
    fun `全是空值时合并返回空`() {
        assertNull(GeminiWebLogin.mergeCookieHeaders(listOf(null, "", "  ")))
    }

    @Test
    fun `过短的 PSID 不算登录态`() {
        assertFalse(GeminiWebLogin.hasUsableSessionCookie("__Secure-1PSID=x"))
        assertFalse(GeminiWebLogin.hasUsableSessionCookie("NID=abc"))
        assertFalse(GeminiWebLogin.hasUsableSessionCookie(null))
        assertTrue(GeminiWebLogin.hasUsableSessionCookie(cookieWithPsid()))
    }

    @Test
    fun `认出仍在登录流程里的地址`() {
        assertTrue(GeminiWebLogin.isSignInPage("https://accounts.google.com/ServiceLogin?service=gemini"))
        assertTrue("地址拿不到时必须当作还没登录，不能凭空判定成功", GeminiWebLogin.isSignInPage(null))
        assertFalse(GeminiWebLogin.isSignInPage("https://gemini.google.com/app"))
        assertFalse(GeminiWebLogin.isSignInPage("https://www.youtube.com/"))
    }

    @Test
    fun `登录态判定要求已经离开登录页`() {
        // 关键反例：Cookie 池里躺着上次过期登录留下的 PSID，但用户这次没登进去。
        // 只看"有没有 PSID"会把这颗死 Cookie 抓回去存好，表现为"登录成功、一发就 403"。
        assertFalse(
            GeminiWebLogin.isReadyToCapture(
                "https://accounts.google.com/ServiceLogin?service=gemini",
                cookieWithPsid(),
            ),
        )
        assertTrue(GeminiWebLogin.isReadyToCapture("https://gemini.google.com/app", cookieWithPsid()))
        assertFalse(GeminiWebLogin.isReadyToCapture("https://gemini.google.com/app", "NID=abc"))
    }

    @Test
    fun `认得出 Google 的内置浏览器拦截页`() {
        assertTrue(
            GeminiWebLogin.isBlockedPageText(
                "This browser or app may not be secure. Try using a different browser."
            ),
        )
        assertTrue(GeminiWebLogin.isBlockedPageText("此浏览器或应用可能不安全。请改用其他浏览器。"))
        assertTrue(GeminiWebLogin.isBlockedPageText("error: disallowed_useragent"))
        assertFalse("正常登录页不能误判成拦截页", GeminiWebLogin.isBlockedPageText("Choose an account"))
        assertFalse(GeminiWebLogin.isBlockedPageText(null))
    }

    @Test
    fun `解开 evaluateJavascript 回传的字符串字面量`() {
        assertEquals("abc", GeminiWebLogin.decodeJsStringLiteral("\"abc\""))
        assertEquals("两行\n文字", GeminiWebLogin.decodeJsStringLiteral("\"两行\\n文字\""))
        assertEquals("带引号\"", GeminiWebLogin.decodeJsStringLiteral("\"带引号\\\"\""))
        assertEquals("此", GeminiWebLogin.decodeJsStringLiteral("\"\\u6b64\""))
        assertNull(GeminiWebLogin.decodeJsStringLiteral("null"))
        assertNull(GeminiWebLogin.decodeJsStringLiteral(null))
        assertEquals("原样", GeminiWebLogin.decodeJsStringLiteral("原样"))
    }

    @Test
    fun `报告缺了哪些键`() {
        val missing = GeminiWebLogin.missingKeys("__Secure-1PSID=$longPsid")
        assertTrue(missing.contains(GeminiWebLogin.KEY_PSIDTS))
        assertTrue(missing.contains(GeminiWebLogin.KEY_SAPISID))
        assertFalse("已经有的键不能报成缺", missing.contains(GeminiWebLogin.KEY_PSID))
        assertEquals(
            listOf(GeminiWebLogin.KEY_PSID, GeminiWebLogin.KEY_PSIDTS, GeminiWebLogin.KEY_SAPISID),
            GeminiWebLogin.presentKeys(cookieWithPsid()),
        )
        assertTrue(GeminiWebLogin.presentKeys(null).isEmpty())
    }

    @Test
    fun `备用入口登录后先认账号会话再去领 PSID`() {
        // YouTube 入口登录完只有账号侧的键，PSID 要跳回 Gemini 才会发。
        // 没有这条判据，备用入口的表现就是"明明登录成功却迟迟抓不到 Cookie"。
        assertTrue(GeminiWebLogin.hasGoogleSessionCookie("HSID=xyz; SSID=abc"))
        assertTrue(GeminiWebLogin.hasGoogleSessionCookie(cookieWithPsid()))
        assertFalse(GeminiWebLogin.hasGoogleSessionCookie("NID=abc; AEC=def"))
        assertFalse(GeminiWebLogin.hasGoogleSessionCookie(null))
    }

    @Test
    fun `抓 Cookie 的域名顺序是 gemini 优先且三个域都在`() {
        assertEquals(3, GeminiWebLogin.COOKIE_SOURCES.size)
        assertTrue(
            "gemini 域必须排第一，它才是这套 Cookie 真正的使用方",
            GeminiWebLogin.COOKIE_SOURCES.first().startsWith("https://gemini.google.com"),
        )
        assertTrue(GeminiWebLogin.COOKIE_SOURCES.any { it.startsWith("https://www.google.com") })
        assertTrue(GeminiWebLogin.COOKIE_SOURCES.any { it.startsWith("https://accounts.google.com") })
    }

    @Test
    fun `连续两次命中才允许自动抓取`() {
        val ready = GeminiWebLogin.CaptureReadiness()
        assertFalse("第一次命中就抓走，会撞上过期 Cookie 还没跳转的那个窗口", ready.hit(true))
        assertTrue("连续第二次命中必须放行，否则永远抓不到", ready.hit(true))
        assertEquals(2, ready.hitsSoFar)
    }

    @Test
    fun `判定中断一次就重新计数`() {
        val ready = GeminiWebLogin.CaptureReadiness()
        // 先把计数器喂到放行（需要连续两次），再验证中断会清零。
        assertFalse(ready.hit(true))
        assertTrue(ready.hit(true))
        assertFalse("中断这一次必须返回不放行", ready.hit(false))
        assertEquals("断掉必须清零", 0, ready.hitsSoFar)
        assertFalse("清零后要重新连续命中两次", ready.hit(true))
        assertTrue(ready.hit(true))
    }

    @Test
    fun `计数器可以配置需要连续命中的次数`() {
        val thrice = GeminiWebLogin.CaptureReadiness(neededHits = 3)
        assertFalse(thrice.hit(true))
        assertFalse(thrice.hit(true))
        assertTrue(thrice.hit(true))
        val once = GeminiWebLogin.CaptureReadiness(neededHits = 1)
        assertTrue(once.hit(true))
    }

    @Test
    fun `两个登录入口的地址没写错`() {
        assertEquals("https://gemini.google.com/app", GeminiWebLogin.START_URL_GEMINI)
        assertTrue(GeminiWebLogin.START_URL_YOUTUBE_BRIDGE.startsWith("https://accounts.google.com/ServiceLogin"))
        assertTrue("登录完要落回 YouTube 才会发出 .google.com 的账号会话", GeminiWebLogin.START_URL_YOUTUBE_BRIDGE.contains("youtube.com"))
    }

    @Test
    fun `粘贴内容仍能规范化成三个键`() {
        // 一键登录抓到的原始 Cookie 头要能喂给既有的规范化函数，产出供应商真正使用的形态；
        // 这条断言把两个组件的接缝钉住，防止哪天只改一边。
        val harvested = GeminiWebLogin.mergeCookieHeaders(
            listOf("__Secure-1PSID=$longPsid; __Secure-1PSIDTS=tsvalue; SAPISID=sapisidvalue; NID=noise"),
        )
        val normalized = normalizeGeminiWebCookie(harvested ?: "无")
        check(normalized != null) { "规范化失败，一键登录抓到的 Cookie 会直接丢掉" }
        assertEquals(3, normalized.split("; ").size)
        assertFalse("无关键必须继续被丢掉，少一点指纹", normalized.contains("NID"))
    }
}
