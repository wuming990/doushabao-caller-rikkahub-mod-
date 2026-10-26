package me.rerere.rikkahub.v1016

import me.rerere.rikkahub.v1015.V1015SourceGuard
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1016 门禁：Gemini 网页版「一键登录」的接线防走丢。
 *
 * 为什么每条都要钉：这个功能由**四个地方合力**才成立 ——
 * 清单注册、登录页实现、设置页入口、请求侧浏览器标识同源。少任何一环都不会报错，
 * 只会"点了没反应"或"登录成功但一发就 403"。v1015 体检已经证明过一整轮：
 * 类文件一直在、注册没了，功能就这么静默消失。
 *
 * 判据一律走 V1015SourceGuard 的"先剥注释再匹配"，注释里写一句功能名不算实现存在。
 */
class V1016GeminiWebLoginGuardTest {

    private val manifest = V1015SourceGuard.source("app/src/main/AndroidManifest.xml")
    private val activity = "app/src/main/java/me/rerere/rikkahub/ui/activity/GeminiWebLoginActivity.kt"
    private val logic = "app/src/main/java/me/rerere/rikkahub/data/gemini/GeminiWebLogin.kt"
    private val configure = "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt"
    private val provider = "app/src/main/java/me/rerere/rikkahub/data/gemini/GeminiWebProvider.kt"
    private val setting = "ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt"

    /** 取出某个 activity 的注册块（XML 注释已剥掉，注释里的名字不能替注册作证）。 */
    private fun activityBlock(name: String): String {
        val code = V1015SourceGuard.xmlCodeOnly(manifest)
        val key = "android:name=\"$name\""
        val at = code.indexOf(key)
        assertTrue("清单里没有 $name 的注册 —— 一键登录一点就报 Unable to find explicit activity class", at >= 0)
        val start = code.lastIndexOf("<activity", at).coerceAtLeast(0)
        val end = code.indexOf("/>", at).let { if (it < 0) code.length else it + 2 }
        return code.substring(start, end.coerceAtMost(code.length))
    }

    @Test
    fun `登录页注册在清单里且不对外开放`() {
        val block = activityBlock(".ui.activity.GeminiWebLoginActivity")
        assertTrue("登录页不该被别的 App 直接拉起", block.contains("android:exported=\"false\""))
        // 登录页里有输入框与网页，转屏/折叠屏切换时不能重建 Activity，否则用户密码输一半就没了。
        assertTrue("缺 configChanges，转一次屏就把登录页重建", block.contains("android:configChanges"))
        assertTrue(block.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    @Test
    fun `登录页用的是原生 Cookie 池而不是页面脚本`() {
        // document.cookie 读不到 HttpOnly 的 __Secure-1PSID，用 JS 抓会"看起来抓到但少了最关键的键"。
        assertTrue(V1015SourceGuard.codeHas(activity, "CookieManager.getInstance()"))
        assertTrue(V1015SourceGuard.codeHas(activity, "cookieManager.getCookie("))
        assertTrue("JS 侧抓 Cookie 的写法不能回来", !V1015SourceGuard.codeHas(activity, "document.cookie"))
    }

    @Test
    fun `重新登录与续期是两个不同意图`() {
        // 第二票审查位抓到的设计洞：内置浏览器留着上次登录态时，「重新登录」会把上次那颗
        // Cookie 当成本次成果抓走并关掉页面，第二个账号根本没机会登。两个意图必须分开。
        assertTrue(
            "设置页没有把「重新登录」标成要求新登录",
            V1015SourceGuard.codeHas(configure, "requireFreshLogin = true)"),
        )
        assertTrue(V1015SourceGuard.codeHas(configure, "重新登录 / 换账号（先清掉旧登录态）"))
        assertTrue(V1015SourceGuard.codeHas(configure, "一键续期（复用已登录状态，不重登）"))
        // Activity 侧必须真的按意图清一次登录态；清除是异步的，还要有兜底加载。
        assertTrue(V1015SourceGuard.codeHas(activity, "getBooleanExtra(GeminiWebLogin.EXTRA_REQUIRE_FRESH_LOGIN"))
        assertTrue(V1015SourceGuard.codeHas(activity, "cookieManager.removeAllCookies { go.run() }"))
        assertTrue(V1015SourceGuard.codeHas(activity, "view.postDelayed(go, LOAD_FALLBACK_MS)"))
    }

    @Test
    fun `登录页开好 JavaScript 存储与第三方 Cookie`() {
        assertTrue(V1015SourceGuard.codeHas(activity, "javaScriptEnabled = true"))
        assertTrue(V1015SourceGuard.codeHas(activity, "domStorageEnabled = true"))
        assertTrue(V1015SourceGuard.codeHas(activity, "setAcceptCookie(true)"))
        // accounts.google.com 落回 gemini.google.com 靠这条；默认是关的，关了会卡在"登录成功但回不来"。
        assertTrue(V1015SourceGuard.codeHas(activity, "cookieManager.setAcceptThirdPartyCookies(view, true)"))
        // 负向：API 37 上它是**实例方法**，本轮真的一次写成静态调用并被编译器当场打回。
        // 钉住这条，防止下次按"老印象它是静态方法"再改回去 —— 那种错只有编译器看得见。
        assertTrue(
            "又把 setAcceptThirdPartyCookies 写成静态调用了，API 37 上它是实例方法",
            !V1015SourceGuard.codeHas(activity, "CookieManager.setAcceptThirdPartyCookies"),
        )
    }

    @Test
    fun `登录页不开多窗口并且显式伪装浏览器标识`() {
        assertTrue(V1015SourceGuard.codeHas(activity, "setSupportMultipleWindows(false)"))
        assertTrue("开多窗口会让新窗口用回未伪装的 UA", !V1015SourceGuard.codeHas(activity, "setSupportMultipleWindows(true)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.webViewUserAgent("))
        assertTrue(V1015SourceGuard.codeHas(activity, "userAgentString = usedUserAgent"))
        // 只改 UA 不够：JS 侧的 navigator.userAgentData 仍会暴露内置浏览器。
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.clientHintsShimJs("))
        assertTrue(V1015SourceGuard.codeHas(activity, "view.evaluateJavascript(js, null)"))
    }

    @Test
    fun `WebView 在建好之后才进组合且销毁一次`() {
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(activity))
        val created = code.indexOf("val view = WebView(this)")
        val composed = code.indexOf("setContent {")
        assertTrue("找不到 WebView 创建点", created >= 0)
        assertTrue("登录页 WebView 必须在 setContent 之前就建好", created in 0 until composed)
        // 组合里只引用那一个实例：factory 里再 WebView(context) 会在每次重组时把登录页重建。
        assertTrue(V1015SourceGuard.codeHas(activity, "factory = { view }"))
        assertTrue(V1015SourceGuard.codeHas(activity, "view?.destroy()"))
    }

    @Test
    fun `登录页用统一判据而不是自己临时拼一套`() {
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.isReadyToCapture(currentUrl, merged)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.mergeCookieHeaders("))
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.isBlockedPageText(text)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.decodeJsStringLiteral(raw)"))
        // 备用入口（先登 YouTube）登录后要主动跳一次 Gemini 领 PSID。
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.hasGoogleSessionCookie(merged)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "webView?.loadUrl(GeminiWebLogin.START_URL_GEMINI)"))
    }

    @Test
    fun `抓到 Cookie 后落盘并把浏览器标识一起回传`() {
        // Cookie 与标识同源是这次实现的关键；只回传 Cookie 会留下"登录成功、一发就 403"的坑。
        assertTrue(V1015SourceGuard.codeHas(activity, "putExtra(GeminiWebLogin.EXTRA_COOKIE, cookie)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "putExtra(GeminiWebLogin.EXTRA_USER_AGENT, usedUserAgent)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "cookieManager.flush()"))
        assertTrue(V1015SourceGuard.codeHas(activity, "setResult("))
    }

    @Test
    fun `登录判据要求已经离开登录页`() {
        // 光看"池子里有没有 PSID"会把上次过期留下的死 Cookie 当成登录成功。
        val body = V1015SourceGuard.funBody(logic, "fun isReadyToCapture(")
        assertTrue(body.contains("!isSignInPage(currentUrl)"))
        assertTrue(body.contains("hasUsableSessionCookie(mergedCookieHeader)"))
    }

    @Test
    fun `设置页真的会启动登录页并保存两个字段`() {
        assertTrue(V1015SourceGuard.codeHas(configure, "webLoginLauncher.launch(GeminiWebLoginActivity.createIntent(context))"))
        assertTrue(
            "备用登录入口没接到设置页，被拦时用户没有第二条路",
            V1015SourceGuard.codeHas(configure, "GeminiWebLogin.START_URL_YOUTUBE_BRIDGE"),
        )
        assertTrue(V1015SourceGuard.codeHas(configure, "cookie = cookie,"))
        assertTrue(V1015SourceGuard.codeHas(configure, "userAgent = userAgent?.trim()?.ifBlank { null } ?: provider.userAgent,"))
    }

    @Test
    fun `手动粘贴的备用路径还活着`() {
        // 一键登录哪天被 Google 堵死，这两条按钮就是用户唯一的出路 —— 不许在任何重构里被删。
        assertTrue(V1015SourceGuard.codeHas(configure, "Text(\"浏览器登录（备用）\")"))
        assertTrue(V1015SourceGuard.codeHas(configure, "Text(\"粘贴 Cookie（备用）\")"))
        assertTrue(V1015SourceGuard.codeHas(configure, "normalizeGeminiWebCookie(clip)"))
        // 而且必须带 NEW_TASK 并显示真实异常，不能再谎报"没有找到可用的浏览器"。
        assertTrue(V1015SourceGuard.codeHas(configure, ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)"))
        assertTrue(V1015SourceGuard.codeHas(configure, "打不开浏览器："))
        assertTrue(
            "旧的错误结论还留在代码里（那句做不到当初就是把一键登录判死的依据）",
            !V1015SourceGuard.codeHas(configure, "手机上没法在应用内直接登录"),
        )
    }

    @Test
    fun `登录状态可清除并可读`() {
        assertTrue(V1015SourceGuard.codeHas(configure, "CookieManager.getInstance().removeAllCookies(null)"))
        assertTrue(V1015SourceGuard.codeHas(configure, "GeminiWebLogin.presentKeys(provider.cookie)"))
        assertTrue(V1015SourceGuard.codeHas(configure, "value = provider.userAgent"))
    }

    @Test
    fun `请求侧改用登录时同一个浏览器标识`() {
        // 两处请求（取 at 与 StreamGenerate）都必须走 userAgentOf，
        // 留一处写死的常量就会让 at 取不到、正文 403，症状还各不相同。
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(provider))
        assertTrue(
            "还有请求在用写死的桌面 UA：" + code.substringAfter("Request.Builder()").take(400),
            !code.contains(".header(\"user-agent\", USER_AGENT)"),
        )
        val uses = Regex("\\.header\\(\"user-agent\", userAgentOf\\(providerSetting\\)\\)").findAll(code).count()
        assertTrue("两处 Google 请求都要用同源 UA，现在只找到 $uses 处", uses >= 2)
        assertTrue(V1015SourceGuard.codeHas(provider, "providerSetting.userAgent.trim().ifBlank { USER_AGENT }"))
    }

    @Test
    fun `失败原因真的有写入方且设置页不会提前丢弃`() {
        // 审查位抓到 EXTRA_ERROR 只有定义和读取、没有写入方，而设置页又在读取它之前
        // 按 resultCode 提前 return —— 两条合起来让"失败要看得见"整条是死代码。
        assertTrue(
            "登录页没有把失败原因写回结果里",
            V1015SourceGuard.codeHas(activity, "putExtra(GeminiWebLogin.EXTRA_ERROR, exitReason())"),
        )
        assertTrue(V1015SourceGuard.codeHas(activity, "private fun exitReason(): String"))
        val body = V1015SourceGuard.funBody(configure, "webLoginLauncher = rememberLauncherForActivityResult(")
        assertTrue(
            "设置页又回到「先按结果码 return、后面才读原因」的死代码写法",
            !body.contains("return@rememberLauncherForActivityResult"),
        )
        assertTrue(V1015SourceGuard.codeHas(configure, "getStringExtra(GeminiWebLogin.EXTRA_ERROR)"))
    }

    @Test
    fun `自动轮询要连续命中才不会把过期 Cookie 当成功`() {
        assertTrue(V1015SourceGuard.codeHas(activity, "GeminiWebLogin.CaptureReadiness()"))
        assertTrue(V1015SourceGuard.codeHas(activity, "if (auto && !readiness.hit(true))"))
        assertTrue(V1015SourceGuard.codeHas(activity, "readiness.hit(false)"))
        // 人为确认那条路不能被计数器挡掉。
        assertTrue("人为确认那条路必须还在，不该被计数器挡住", V1015SourceGuard.codeHas(activity, "tryCapture(auto = false)"))
        assertTrue(V1015SourceGuard.codeHas(activity, "enableEdgeToEdge()"))
    }

    @Test
    fun `供应商配置里有存浏览器标识的字段`() {
        // 剥注释再匹配：只在注释里提过这个字段不能算存在（V1015SourceGuard 存在的理由）。
        assertTrue("ProviderSetting.GeminiWeb 缺 userAgent 字段，登录页回传的标识会被丢掉", V1015SourceGuard.codeHas(setting, "val userAgent: String = \"\","))
    }
}
