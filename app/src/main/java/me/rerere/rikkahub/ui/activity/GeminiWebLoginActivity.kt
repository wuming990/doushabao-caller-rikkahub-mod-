package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.gemini.GeminiWebLogin
import me.rerere.rikkahub.data.gemini.normalizeGeminiWebCookie
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/**
 * v1016：Gemini 网页版「一键登录」。
 *
 * ## 为什么要有这个页面
 * 之前 Gemini 网页版只能"在浏览器里登录 → 手动复制 Cookie → 回来粘贴"，而且界面里写死了一句
 * "手机上没法在应用内登录 Google"。那句结论没实测过（详见 PROJECT-MANUAL §10 本轮条目），
 * 实际做法就是本页面：应用内开一个网页窗口 → 用户登录 → 从原生 Cookie 池里把
 * `__Secure-1PSID` / `__Secure-1PSIDTS` / `SAPISID` 抓出来存进供应商设置。
 *
 * ## 三条设计约束（都是取证换来的，不是随手写的）
 * 1. **全程只用这一个 WebView 实例**，且不开多窗口。Google 登录要从 accounts.google.com
 *    回到 gemini.google.com；一旦弹新窗口，新窗口不会跟着我们的伪装 UA，跨域 Cookie 链路
 *    也会断（社区已实测 Chromium 134+ 的第三方 Cookie 策略会打断这条链）。
 * 2. **UA 与 Cookie 同源**。抓完把 WebView 当时用的 UA 一起回传，调用侧改用它发请求；
 *    否则"手机登录的会话 + 写死的桌面标识去调用"会触发 Google 的会话不匹配判定。
 * 3. **失败必须看得见**。轮询判定登录态，并在页面上直说缺哪个键、是不是被 Google 认出
 *    是内置浏览器 —— 不静默白屏，也不无限转圈。Google 对嵌入式浏览器的风控随时可能收紧，
 *    这条路不保证长期有效，所以手动粘贴的备用路径同时保留。
 */
class GeminiWebLoginActivity : ComponentActivity() {

    companion object {
        /** 登录态轮询间隔。太快没意义（Cookie 是页面加载时才发的），太慢用户会觉得卡住。 */
        private const val POLL_INTERVAL_MS = 1_000L

        /** 清除登录态是异步的；回调万一没来，这个兜底时间到就直接加载，不能把页面卡成空白。 */
        private const val LOAD_FALLBACK_MS = 1_500L

        /** 抓页面正文判断"是不是被 Google 拦了"。只取前 4000 字，正文判定够用。 */
        private const val BODY_TEXT_JS =
            "(function(){try{return (document.body&&document.body.innerText)" +
                "?document.body.innerText.substring(0,4000):'';}catch(e){return '';}})()"

        fun createIntent(
            context: Context,
            startUrl: String = GeminiWebLogin.START_URL_GEMINI,
            requireFreshLogin: Boolean = false,
        ): Intent = Intent(context, GeminiWebLoginActivity::class.java).apply {
            putExtra(GeminiWebLogin.EXTRA_START_URL, startUrl)
            putExtra(GeminiWebLogin.EXTRA_REQUIRE_FRESH_LOGIN, requireFreshLogin)
        }
    }

    private val statusText: MutableState<String> = mutableStateOf("正在打开登录页…")
    private val detailText: MutableState<String> = mutableStateOf("")
    private val progress: MutableState<Int> = mutableStateOf(0)
    private val loading: MutableState<Boolean> = mutableStateOf(true)
    private val blockedByGoogle: MutableState<Boolean> = mutableStateOf(false)

    /** 整个登录过程唯一的一个 WebView；在 setContent 之前建好，之后绝不重建。 */
    private var webView: WebView? = null

    /** 本次登录实际用的浏览器标识，随结果一起回传（见类注释第 2 条约束）。 */
    private var usedUserAgent: String = ""

    private var captured = false
    private var startUrl = GeminiWebLogin.START_URL_GEMINI

    /** 这次是要重新登录/换账号（true）还是续期抓新值（false）。决定进来要不要先清登录态。 */
    private var requireFreshLogin = false

    /** 自动轮询要连续命中两次才认成功，防止把过期 Cookie 当登录态抓走（判据见 CaptureReadiness）。 */
    private val readiness = GeminiWebLogin.CaptureReadiness()

    /** 备用入口（先登 YouTube）用过一次以后，才允许自动跳 Gemini 领 PSID，避免来回跳。 */
    private var hoppedToGemini = false

    private val cookieManager: CookieManager by lazy { CookieManager.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startUrl = intent?.getStringExtra(GeminiWebLogin.EXTRA_START_URL)
            ?: GeminiWebLogin.START_URL_GEMINI
        requireFreshLogin = intent?.getBooleanExtra(GeminiWebLogin.EXTRA_REQUIRE_FRESH_LOGIN, false) ?: false

        val view = WebView(this)
        webView = view
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        usedUserAgent = GeminiWebLogin.webViewUserAgent(view.settings.userAgentString)
        // 登录要从 accounts.google.com 落回 gemini.google.com，第三方 Cookie 必须开
        // （targetSdk 21+ 默认是关的，关了就会卡在"登录成功但没回到应用"）。
        cookieManager.setAcceptCookie(true)
        // API 37 的 android.jar 里 setAcceptThirdPartyCookies 是**实例方法**（历史上曾是静态），
        // 写成 CookieManager.xxx 会直接编译不过 —— 已用 javap 核对过本机 SDK。
        cookieManager.setAcceptThirdPartyCookies(view, true)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = usedUserAgent
            // 不开多窗口：window.open / target=_blank 直接在当前窗口打开，
            // UA、Cookie 池、Client-Hints 伪装才不会跑偏。
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val scheme = request.url.scheme?.lowercase()
                // 登录中途跳出 http(s)（跳应用商店、跳别的 App）一律按住：
                // 一旦离开这个窗口，伪装 UA 和 Cookie 池全都接不上，用户看到的就是"点了没反应"。
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading.value = true
                injectClientHints(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading.value = false
                injectClientHints(view)
                detectBlockedPage(view)
                tryCapture(auto = true)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                // 这台机器现在连不上 Google 时是最常见的一种"卡住"，必须把原因写出来，
                // 不能让用户对着一片白自己猜。
                detailText.value = "页面加载失败：${error.description}（code=${error.errorCode}）" +
                    "。请确认当前网络能访问 google.com。"
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.value = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                // 已经抓到 Cookie 之后别再把标题写上去，否则"成功"提示会被页面标题顶掉。
                if (captured || title.isNullOrBlank()) return
                statusText.value = title
            }
        }

        // 重新登录 / 换账号：先把内置浏览器里的 Google 登录态清掉，页面才会真的把登录表单给你。
        // 不清的后果是审查位抓到的那条：上次那颗 Cookie 还在池子里，页面一加载完轮询就当成功
        // 抓走并关页面，第二个账号根本没机会登。清除是异步的，回调里再加载；
        // postDelayed 是兜底 —— 回调万一不来也不能把页面永远卡成空白。
        if (requireFreshLogin) {
            statusText.value = "正在清除上次的登录态…"
            var started = false
            // 明确写成 Runnable：postDelayed 收的是 Java 的 Runnable，
            // 拿一个函数类型的变量过去属于"要靠编译器开恩"的写法，这里不留悬念。
            val go = Runnable {
                if (!started) {
                    started = true
                    view.loadUrl(startUrl)
                }
            }
            runCatching { cookieManager.removeAllCookies { go.run() } }.onFailure { go.run() }
            view.postDelayed(go, LOAD_FALLBACK_MS)
        } else {
            view.loadUrl(startUrl)
        }

        setContent {
            RikkahubTheme { LoginScreen(view) }
        }
        startPolling()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LoginScreen(view: WebView) {
        BackHandler(enabled = true) {
            if (view.canGoBack()) view.goBack() else finishWithoutResult()
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "登录 Google（Gemini 网页版）",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { finishWithoutResult() }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = { view.reload() }) {
                            Icon(HugeIcons.Refresh01, contentDescription = "重新加载")
                        }
                    },
                )
            },
            bottomBar = { BottomBar() },
        ) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            ) {
                if (loading.value) {
                    LinearProgressIndicator(
                        progress = { progress.value / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        statusText.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (blockedByGoogle.value) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (detailText.value.isNotBlank()) {
                        Text(
                            detailText.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // factory 里返回的是 onCreate 就建好的那一个实例：recomposition 绝不会重建网页，
                // 否则用户输到一半密码，页面一刷新就得从头来。
                AndroidView(
                    factory = { view },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun BottomBar() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { tryCapture(auto = false) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("我登录好了，立即抓取")
            }
            OutlinedButton(
                onClick = { switchToBridgeEntry() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (blockedByGoogle.value) {
                        "被拦了：改走 YouTube 登录入口（登录后自动跳回 Gemini）"
                    } else {
                        "备用登录入口：先登 YouTube，再自动跳回 Gemini"
                    },
                )
            }
        }
    }

    /**
     * 备用入口：Google 对 Gemini 登录页和 YouTube 登录页的风控强度不一样，
     * 前者拦得住内置浏览器时后者未必拦得住（Android 上的开源项目 YTPro 就是走这条路复用
     * `.google.com` 的会话来调 Gemini）。登录完由轮询认出"账号已登进来但没有 PSID"，
     * 再自动跳一次 Gemini 领取那颗 PSID。
     */
    private fun switchToBridgeEntry() {
        blockedByGoogle.value = false
        hoppedToGemini = false
        detailText.value = "已改用 YouTube 登录入口，登录完成后会自动跳回 Gemini 抓 Cookie。"
        webView?.loadUrl(GeminiWebLogin.START_URL_YOUTUBE_BRIDGE)
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive && !captured) {
                tryCapture(auto = true)
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 读三个域的 Cookie 池并合并（`getCookie` 是原生接口，HttpOnly 的 PSID 也能拿到；
     * 换成页面里 `document.cookie` 就一定读不到 —— 这是取证里最容易被搞错的一条）。
     */
    private fun harvest(): String? = GeminiWebLogin.mergeCookieHeaders(
        GeminiWebLogin.COOKIE_SOURCES.map { cookieManager.getCookie(it) },
    )

    private fun tryCapture(auto: Boolean) {
        if (captured) return
        val merged = harvest()
        val currentUrl = webView?.url
        // merged 可能是 null（页面还没发出任何 Cookie），而规范化函数收的是非空 String，
        // 这里必须先挡一层；直接传进去是编译期就挂的类型错误。
        val normalized = merged?.let { normalizeGeminiWebCookie(it) }
        val ready = GeminiWebLogin.isReadyToCapture(currentUrl, merged) && normalized != null
        if (ready && normalized != null) {
            // 人为点的按钮直接认（用户已经看见登录完成了）；自动轮询必须连续命中两次，
            // 避开"页面先渲染、随后跳回登录页"那一两秒窗口里躺着的那颗过期 Cookie。
            if (auto && !readiness.hit(true)) {
                maybeHopToGemini(currentUrl, merged)
                return
            }
            finishWithCookie(normalized)
            return
        }
        readiness.hit(false)
        if (auto) {
            maybeHopToGemini(currentUrl, merged)
            return
        }
        // 手动按钮：把"到底差在哪一步"直接写出来。用户看不见原因就只能反复点。
        statusText.value = when {
            GeminiWebLogin.isSignInPage(currentUrl) ->
                "还在登录页里 —— 先把账号密码（或验证码）走完，再按这个按钮"

            merged.isNullOrBlank() ->
                "内置浏览器里一个 Google Cookie 都没有 —— 页面多半没加载出来，检查网络或按右上角重新加载"

            else ->
                "页面已登录，但没拿到 ${GeminiWebLogin.KEY_PSID}。已看到的键：" +
                    cookieNames(merged)
        }
        detailText.value = "缺少的键：${GeminiWebLogin.missingKeys(merged).joinToString("、")}"
    }

    /**
     * 备用入口登录后，账号会话已经在了但 Gemini 那颗 PSID 还没发 —— 主动跳一次去领。
     *
     * 只在自动轮询里做，且只做一次（hoppedToGemini）。不做这件事的话，YouTube 入口的
     * 表现就是"明明登录成功了，却迟迟抓不到 Cookie"。
     */
    private fun maybeHopToGemini(currentUrl: String?, merged: String?) {
        if (hoppedToGemini) return
        if (GeminiWebLogin.isSignInPage(currentUrl)) return
        if (GeminiWebLogin.hasUsableSessionCookie(merged)) return
        if (!GeminiWebLogin.hasGoogleSessionCookie(merged)) return
        hoppedToGemini = true
        detailText.value = "账号已登录，正在打开 Gemini 领取会话 Cookie…"
        webView?.loadUrl(GeminiWebLogin.START_URL_GEMINI)
    }

    /** 只列键名，不列值 —— 键名对排查够用，值等于账号凭据，不能显示。 */
    private fun cookieNames(cookieHeader: String?): String =
        cookieHeader?.split(';')
            ?.map { it.trim().substringBefore('=') }
            ?.filter { it.isNotBlank() }
            ?.take(12)
            ?.joinToString("、")
            .orEmpty()

    private fun finishWithCookie(cookie: String) {
        if (captured) return
        captured = true
        // 落盘：登录结果要是进程被系统杀掉就白登了。flush 只负责持久化，不影响已读到的值。
        runCatching { cookieManager.flush() }
        statusText.value = "已抓到登录态：" + GeminiWebLogin.presentKeys(cookie).joinToString("、")
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(GeminiWebLogin.EXTRA_COOKIE, cookie)
                putExtra(GeminiWebLogin.EXTRA_USER_AGENT, usedUserAgent)
            },
        )
        finish()
    }

    private fun finishWithoutResult() {
        if (captured) return
        // v1016：离开页面时把屏幕上那份诊断一起带回设置页。
        // 审查位抓到 EXTRA_ERROR 只有定义和读取、没有任何写入方 —— 那条"失败要看得见"
        // 的承诺在离开登录页的那一刻就断了，用户回到设置页只会看到什么都没发生。
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(GeminiWebLogin.EXTRA_ERROR, exitReason()),
        )
        finish()
    }

    /** 用户为什么没带着 Cookie 离开 —— 按"还差哪一步"分三档说清楚，不要只说"失败"。 */
    private fun exitReason(): String {
        val merged = harvest()
        val url = webView?.url
        return when {
            GeminiWebLogin.isSignInPage(url) ->
                "还没登录完成（停在登录页）：把账号密码/验证码走完，再按「我登录好了，立即抓取」"
            merged.isNullOrBlank() ->
                "内置浏览器里一个 Google Cookie 都没有：多半是这台机器现在连不上 google.com，" +
                    "或页面没加载出来 —— 用设置页里的备用方式手动粘贴 Cookie"
            else ->
                "页面已登录但没拿到 ${GeminiWebLogin.KEY_PSID}" +
                    "（缺少的键：${GeminiWebLogin.missingKeys(merged).joinToString("、")}）"
        }
    }

    /**
     * 认出 Google 的「此浏览器或应用可能不安全」拦截页。
     *
     * 为什么要专门检测这一种失败：整条路是靠绕过 Google 对内置浏览器的风控成立的，
     * 而它随时可能收紧。被拦时服务器返回的是**一个正常的 200 页面**——不报错、不转圈、
     * 也不会有任何异常。不主动把这张脸认出来，用户就只看到一句"卡住了"，
     * 不知道该改点下面的备用入口。
     */
    private fun detectBlockedPage(view: WebView) {
        runCatching {
            view.evaluateJavascript(BODY_TEXT_JS) { raw ->
                val text = GeminiWebLogin.decodeJsStringLiteral(raw)
                if (!GeminiWebLogin.isBlockedPageText(text)) return@evaluateJavascript
                blockedByGoogle.value = true
                statusText.value = "Google 认出了内置浏览器，拒绝在这个窗口里登录"
                detailText.value = "改点下面「备用登录入口」；再不行就回供应商设置用「浏览器登录 + 粘贴 Cookie」。"
            }
        }
    }

    /**
     * 把 `navigator.userAgentData` 也伪装成 Chrome。
     *
     * 只改 UA 字符串是不够的：HTTP 头说自己是 Chrome，页面里 JS 读到的 brands 还写着
     * Android WebView，这种自相矛盾本身就是风控信号。主文档与子帧都在页面开始加载时注入，
     * 完成时再补一次（Google 的登录页脚本比我们早跑的情况确实存在）。
     */
    private fun injectClientHints(view: WebView) {
        val js = GeminiWebLogin.clientHintsShimJs(usedUserAgent) ?: return
        runCatching { view.evaluateJavascript(js, null) }
    }

    override fun onDestroy() {
        // 顺序有讲究：先把 WebView 从父视图里摘掉再 destroy()。
        // 直接从组合里销毁一个仍挂在视图树上的 WebView，会在退出这个页面的那一刻崩在
        // 系统视图层（用户看到的就是"登录完回到设置页闪退"）。
        val view = webView
        webView = null
        runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
        runCatching { view?.destroy() }
        super.onDestroy()
    }
}
