package me.rerere.rikkahub.data.gemini

/**
 * v1016：Gemini 网页版「一键登录」里**完全不依赖 Android API** 的那部分判定。
 *
 * 为什么单独拆一个文件出来：登录页本身（WebView、CookieManager、Activity 回传）没法在 JVM 上跑，
 * 但「这条 Cookie 算不算登录成功」「浏览器标识该怎么改」「页面是不是被 Google 拦了」这三件事
 * 是这个功能真正的成败判据。放在可单测的纯函数里，才能保证它们不是靠人肉看一眼就交付的。
 *
 * 判据来源（探测位取证，2026-10-24）：
 * - Google 明确封杀「内嵌浏览器」登录 OAuth 端点（2021-09-30 起），普通登录页会显示
 *   "This browser or app may not be secure / 此浏览器或应用可能不安全" 的拦截页 —— 所以必须
 *   能把这个拦截态识别出来，不能让用户对着白屏干等。
 * - 检测点是 UA 里的 `wv` 标记；改掉 UA 后仍有走通的公开案例，但**不保证长期有效**，
 *   因此手动粘贴 Cookie 的路径必须原样保留作兜底。
 * - `__Secure-1PSID` / `__Secure-1PSIDTS` 是 HttpOnly，JS 的 document.cookie 读不到，
 *   只能走原生 Cookie 池（所以抓的动作在 Activity 里，判定在这里）。
 * - Cookie 的 Domain 是 `.google.com`，但 `__Host-` 前缀的键不跨域，所以三个域都要读一遍再合并。
 */
object GeminiWebLogin {

    /** 登录入口：直接进 Gemini，未登录时 Google 自己会把用户送到登录页。 */
    const val START_URL_GEMINI = "https://gemini.google.com/app"

    /**
     * 备用入口：先走 YouTube 的登录页，登录后回到 Gemini 复用 `.google.com` 的会话。
     *
     * 这不是我发明的路：Android 开源项目 YTPro 就是这么做到 Gemini 网页调用的
     * （它的判据同样是 `__Secure-1PSID` 出现在 Cookie 池里）。之所以值得多留一条，
     * 是因为 Google 对不同 Google 属性的内嵌浏览器风控强度不一样，Gemini 登录页被拦时
     * YouTube 未必被拦。用户只需要多点一下按钮，不需要重做任何东西。
     */
    const val START_URL_YOUTUBE_BRIDGE =
        "https://accounts.google.com/ServiceLogin" +
            "?service=youtube&uilel=0&hl=zh-CN" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2F" +
            "&flowName=GlifWebSignIn&flowEntry=ServiceLogin"

    /**
     * 抓 Cookie 的域，**顺序有意义**：先读的域优先。
     *
     * gemini 域排第一，因为它返回的是 Google 当下真正给这个站用的那组值；www / accounts
     * 只是补齐 `.google.com` 上的公共键和 host-only 的漏网键。
     */
    val COOKIE_SOURCES = listOf(
        "https://gemini.google.com/app",
        "https://www.google.com/",
        "https://accounts.google.com/",
    )

    /** 登录真正需要的键 —— 没有它等于没登录。 */
    const val KEY_PSID = "__Secure-1PSID"
    const val KEY_PSIDTS = "__Secure-1PSIDTS"
    const val KEY_SAPISID = "SAPISID"
    val REQUIRED_KEYS = listOf(KEY_PSID)

    /** 界面要展示的「有用键」；缺 SAPISID 不影响调用，只是少一个认证头，所以不算必需。 */
    val USEFUL_KEYS = listOf(KEY_PSID, KEY_PSIDTS, KEY_SAPISID)

    /**
     * Google 拦截内嵌浏览器时页面上会出现的文案。
     *
     * 中英都列了：登录页语言跟着 `hl` 与账号设置走，我们没法假定是哪一种。
     */
    private val BLOCK_MARKERS = listOf(
        "This browser or app may not be secure",
        "may not be secure",
        "disallowed_useragent",
        "此浏览器或应用可能不安全",
        "浏览器或应用可能不安全",
        "使用其他浏览器",
    )

    /** 页面正文是不是「被 Google 判定为不安全浏览器」的拦截页。 */
    fun isBlockedPageText(bodyText: String?): Boolean {
        if (bodyText.isNullOrBlank()) return false
        val lower = bodyText.lowercase()
        return BLOCK_MARKERS.any { it.lowercase() in lower }
    }

    /** 还在登录流程里（accounts.google.com / ServiceLogin / 各类 signin 路径）。 */
    fun isSignInPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return "accounts.google.com" in lower ||
            "service login" in lower ||
            "servicelogin" in lower ||
            "accounts.google" in lower ||
            "/signin" in lower ||
            "signin.chromium" in lower
    }

    /**
     * 登录态是否已经落到我们手上：已经离开登录页，并且 Cookie 池里有真正可用的 PSID。
     *
     * 为什么不能只看「有没有 PSID」：内置浏览器的 Cookie 池是会留旧值的。用户上次登录过期后，
     * 池子里那颗 PSID 还在，但 Google 已经不认了 —— 只查键名会把一颗死 Cookie 抓回去存好，
     * 表现为「登录成功，一发请求就 403」，比直接报错更难查。所以必须再要求当前页面已经
     * 落在登录流程之外（Google 认了才会放我们回到 gemini / youtube）。
     */
    fun isReadyToCapture(currentUrl: String?, mergedCookieHeader: String?): Boolean =
        !isSignInPage(currentUrl) && hasUsableSessionCookie(mergedCookieHeader)

    /** PSID 存在且长度可信（空值/占位值不算登录）。 */
    fun hasUsableSessionCookie(cookieHeader: String?): Boolean =
        (cookieValue(cookieHeader, KEY_PSID)?.length ?: 0) >= MIN_COOKIE_VALUE_LENGTH

    /**
     * 从 `a=1; b=2` 形态的 Cookie 头里取一个键。
     *
     * 逐个前缀比对而不是写正则，是有原因的：`__Secure-1PSIDTS` 的字面里**包含**
     * `__Secure-1PSID`，任何"子串匹配"写法都会把 PSIDTS 的值当成 PSID 抓走。
     * 手册 §10 记过同一族的坑（normalizeGeminiWebCookie 的左边界就是为它加的）。
     */
    fun cookieValue(cookieHeader: String?, key: String): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val prefix = key + "="
        cookieHeader.split(';').forEach { rawPart ->
            val part = rawPart.trim()
            if (part.startsWith(prefix)) {
                val value = part.substring(prefix.length).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    /**
     * 合并多个域读到的 Cookie 头：同名键先到先得（所以 COOKIE_SOURCES 的顺序就是优先级）。
     *
     * 返回值就是能直接塞进 HTTP `Cookie:` 头的形态。全部为空返回 null。
     */
    fun mergeCookieHeaders(headers: List<String?>): String? {
        val picked = LinkedHashMap<String, String>()
        headers.forEach { header ->
            if (header.isNullOrBlank()) return@forEach
            header.split(';').forEach { rawPart ->
                val part = rawPart.trim()
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isEmpty() || value.isEmpty()) return@forEach
                if (!picked.containsKey(name)) picked[name] = value
            }
        }
        if (picked.isEmpty()) return null
        return picked.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    /** 有用的键里缺了哪些（用来在界面上说清楚"抓到了什么、还差什么"，不要只说"失败")。 */
    fun missingKeys(cookieHeader: String?): List<String> =
        USEFUL_KEYS.filter { (cookieValue(cookieHeader, it)?.isNotBlank()) != true }

    /** 抓到的是哪些键（界面回执与守卫都要用，别让用户猜）。 */
    fun presentKeys(cookieHeader: String?): List<String> =
        USEFUL_KEYS.filter { (cookieValue(cookieHeader, it)?.length ?: 0) >= 2 }

    /**
     * 解开 `evaluateJavascript` 回传的字符串字面量（它给的是带引号的 JSON 形态）。
     *
     * 不写这个的话，拦截页判定会拿 `"此浏览器或应用可能不安全"` 这种带引号、带 `\n` 转义的原文
     * 去比对 —— 只要页面文案换行或带引号就漏判，而且是**静默漏判**。
     */
    fun decodeJsStringLiteral(raw: String?): String? {
        if (raw == null) return null
        if (raw == "null" || raw == "undefined") return null
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
        val body = raw.substring(1, raw.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val next = body[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    '"' -> out.append('"')
                    '\'' -> out.append('\'')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> {
                        // \uXXXX：不足 4 位或含非法字符就原样保留，不猜。
                        if (i + 5 < body.length) {
                            val hex = body.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(c).append(next)
                    }
                    else -> out.append(c).append(next)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * 把系统 WebView 的默认 UA 改成"看起来就是一个 Chrome"。
     *
     * 两件事，缺一不可：
     * 1. 去掉 `wv` 标记 —— Google 的判定就落在这个子串上（安卓官方开发者博客也承认：
     *    只要应用没有覆写 UA，站点就仍然可以从 UA 里找 `wv`）。
     * 2. 去掉 `Version/4.0 ` —— 这是 WebView 独有的兼容性伪装前缀，留着等于自报身份。
     *
     * **Chrome 版本号一律保留系统真实值，绝不硬编码。** 探测位取证：另一个项目因为把
     * `Chrome/130` 写死，被风控当成"版本固定的自动化浏览器"。真实版本还顺带保证 UA 与
     * TLS/内核行为一致，比我们编一个号更不容易被认出来。
     */
    fun webViewUserAgent(defaultUserAgent: String?): String {
        val stripped = defaultUserAgent
            ?.replace("; wv", "")
            ?.replace(";wv", "")
            ?.replace("Version/4.0 ", "")
            ?.trim()
            .orEmpty()
        // 老 WebView 可能根本没有 Chrome/ 段；没有就直接给一份完整的现代 Chrome 移动 UA。
        if ("Chrome/" !in stripped) return FALLBACK_USER_AGENT
        return stripped
    }

    /** 从 UA 里取 Chrome 主版本号；取不到返回 null（就不注入 Client-Hints，避免自相矛盾）。 */
    fun chromeMajorVersion(userAgent: String?): String? {
        if (userAgent.isNullOrBlank()) return null
        val match = Regex("Chrome/(\\d+)").find(userAgent) ?: return null
        return match.groupValues[1].takeIf { it.isNotEmpty() }
    }

    /**
     * 生成一段 JS，把 `navigator.userAgentData` 也一起改口。
     *
     * 为什么必须做这一步：只改 `WebSettings.userAgentString` 的话，HTTP 头里的 UA 变成
     * Chrome 了，但页面里 JS 读到的 `navigator.userAgentData.brands` 仍然写着
     * "Android WebView" —— Chromium 评审里明说这正是"伪装会被自己出卖"的场景。
     *
     * 为什么不用 `WebSettingsCompat.setUserAgentMetadata`：那个 API 在 androidx.webkit 里，
     * 而本仓没有这条依赖、本地 Gradle 缓存里也没有。为了一个可选增强把整棵树变成
     * "必须联网拉新依赖才能编译"，不值得；JS 注入这条不需要任何新依赖，效果覆盖 JS 侧检测。
     *
     * 返回 null 表示拿不到 Chrome 版本 —— 宁可不注入，也不要注入一份和 UA 对不上的号。
     * 返回的是**纯 JS 表达式**（不带 `javascript:` 前缀），因为调用方是 evaluateJavascript，
     * 加前缀会让它整句变成无效脚本而且是静默无效。
     */
    fun clientHintsShimJs(userAgent: String?): String? {
        // 先收一次非空局部变量：major 判空并不能让编译器把参数也当成非空，
        // 直接把 userAgent 交给 Regex.find 会在三个版本号解析处各报一次类型错误。
        val ua = userAgent ?: return null
        val major = chromeMajorVersion(ua) ?: return null
        val minor = Regex("Chrome/\\d+\\.(\\d+)").find(ua)?.groupValues?.get(1) ?: "0"
        val build = Regex("Chrome/\\d+\\.\\d+\\.(\\d+)").find(ua)?.groupValues?.get(1) ?: "0"
        val patch = Regex("Chrome/\\d+\\.\\d+\\.\\d+\\.(\\d+)").find(ua)?.groupValues?.get(1) ?: "0"
        val fullVersion = "$major.$minor.$build.$patch"
        // 注意：这里刻意用字符串拼接而不是三引号原始串，JS 里也不能出现 Kotlin 模板符号。
        return "(function(){" +
            "try{" +
            "var brands=[" +
            "{brand:'Not(A:Brand',version:'24'}," +
            "{brand:'Chromium',version:'" + major + "'}," +
            "{brand:'Google Chrome',version:'" + major + "'}" +
            "];" +
            "var fullVersion='" + fullVersion + "';" +
            "var data={" +
            "brands:brands," +
            "mobile:true," +
            "platform:'Android'," +
            "getHighEntropyValues:function(){" +
            "return Promise.resolve({" +
            "architecture:'arm'," +
            "bitness:'64'," +
            "model:'Pixel 8'," +
            "platformVersion:'14.0.0'," +
            "uaFullVersion:fullVersion," +
            "fullVersionList:brands.map(function(b){return {brand:b.brand,version:fullVersion};})" +
            "});" +
            "}" +
            "};" +
            "Object.defineProperty(Navigator.prototype,'userAgentData'," +
            "{get:function(){return data;},configurable:true});" +
            "}catch(e){}" +
            "})()"
    }

    /**
     * Google 会话存在但**还没有 Gemini 那颗 PSID** 时用的判据。
     *
     * 备用入口（先登 YouTube 再跳 Gemini）必须靠它：用户登录完 YouTube 时，`.google.com`
     * 上已经有了 SID/HSID/SSID 这套账号凭据，但 `__Secure-1PSID` 是 Gemini 自己发的，
     * 此时还没有 —— 光等 PSID 会永远等不到。认出"账号已经登进来了"，才敢主动跳一次 Gemini。
     */
    val SESSION_MARKER_KEYS = listOf(
        "SID", "HSID", "SSID", "SAPISID", "ACCOUNT",
        "__Secure-1PSID", "__Secure-1PSIDTS", "__Secure-3PSID",
    )

    fun hasGoogleSessionCookie(cookieHeader: String?): Boolean =
        SESSION_MARKER_KEYS.any { cookieValue(cookieHeader, it) != null }

    /** 结果回传的键：规范化后的 Cookie 头。 */
    const val EXTRA_COOKIE = "gemini_web_cookie"

    /** 结果回传的键：抓取时用的浏览器标识（必须与 Cookie 同源，见类注释）。 */
    const val EXTRA_USER_AGENT = "gemini_web_user_agent"

    /** 结果回传的键：失败原因（没有抓到 Cookie 时让用户看得见原因，而不是干等）。 */
    const val EXTRA_ERROR = "gemini_web_error"

    /** 启动参数：从哪个入口进登录页（默认直接进 Gemini，备用入口先走 YouTube）。 */
    const val EXTRA_START_URL = "gemini_web_start_url"

    /**
     * 启动参数：这次进来是要**重新登录/换账号**，不是续期。
     *
     * 为什么必须有这个区分：内置浏览器的 Cookie 池会把上次登录留着。不先清掉它，
     * "重新登录"打开的就是一个已经登录好的页面 —— 页面一加载完，轮询就会把
     * **上一次那颗 Cookie** 当成本次成果抓走并关页面，用户压根没机会输第二个账号。
     * 第一次登录时"进来就抓到"是好事（那才叫一键），重新登录时它是致命 bug。
     */
    const val EXTRA_REQUIRE_FRESH_LOGIN = "gemini_web_require_fresh_login"

    private const val MIN_COOKIE_VALUE_LENGTH = 20

    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * 连续命中才算"可以抓"。
     *
     * 为什么单次判定不够（审查位抓到的竞态）：内置浏览器的 Cookie 池会留着上一次登录的
     * `__Secure-1PSID`，即使它已经过期。Google 收到请求后是**先把 gemini 页面渲染出来、
     * 再跳回 accounts.google.com**，所以有一两秒的窗口里「已经离开登录页」和「池里有 PSID」
     * 两个条件同时成立 —— 单次轮询正好撞进这个窗口，就会把一颗死 Cookie 当成功存好，
     * 表现成"登录成功、一发请求就 403"，比当场报错难查得多。
     *
     * 要求连续 N 次都判 ready，跳转早就发生了，窗口就被避开。
     * 用户自己按「我登录好了，立即抓取」时不走这里 —— 人为确认优先，不该被计数器挡住。
     */
    class CaptureReadiness(private val neededHits: Int = 2) {
        private var hits = 0

        /** 喂进这一次轮询的判定结果；返回 true 才允许真的把 Cookie 抓走。 */
        fun hit(ready: Boolean): Boolean {
            if (!ready) {
                hits = 0
                return false
            }
            if (hits < neededHits) hits++
            return hits >= neededHits
        }

        fun reset() {
            hits = 0
        }

        val hitsSoFar: Int get() = hits
    }
}
