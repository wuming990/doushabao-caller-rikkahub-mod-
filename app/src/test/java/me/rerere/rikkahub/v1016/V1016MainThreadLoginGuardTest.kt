package me.rerere.rikkahub.v1016

import me.rerere.rikkahub.v1015.V1015SourceGuard
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1016 门禁：账号登录不能再把界面线程按住。
 *
 * 用户报的是"点登录直接卡死，再点越点越死"。取证过程（都留在手册 §10 本轮条目里）：
 *   1. 三个账号登录按钮的写法都是 `onClick = oauthManager::startLogin` —— 手指那一下就是界面线程；
 *   2. `startLogin()` 第一件事是 `ensureCallbackServer()`，里面是
 *      `embeddedServer(CIO, ...).start(wait = false)`；
 *   3. 用 javap 反编译打包实际用的 ktor-server-cio 3.5.2，
 *      `CIOApplicationEngine.start(Z)` 调 `CoroutineUtilsJvmKt.runBlockingBridge` —— 阻塞调用线程。
 *      官方自己的 OAuth 回调服务用的是 `startSuspend`，正好是同一件事的非阻塞写法。
 *   4. `ensureCallbackServer()` 带 `@Synchronized`，所以连点几次全部排在同一把锁后面。
 * 另一条独立缺陷：`awaitNetworkUnblocked()` 只能由 `onBlockedStatusChanged` 唤醒，
 * 而这个回调只在"阻塞状态发生变化"时来；网络本来就是通的时它可能一次都不来，
 * 于是浏览器里已经授权成功、应用却永远停在"等待授权"。
 *
 * 这两条都是**静默失效**（不报错、不崩溃，只卡），没有守卫的话下次重构很容易又搬回主线程。
 */
class V1016MainThreadLoginGuardTest {

    private val gemini = "app/src/main/java/me/rerere/rikkahub/data/gemini/GeminiOAuthManager.kt"
    private val codex = "app/src/main/java/me/rerere/rikkahub/data/codex/CodexOAuthManager.kt"

    @Test
    fun `两个账号登录的起服务动作都在后台线程里做`() {
        listOf(gemini to "Gemini", codex to "Codex").forEach { (file, label) ->
            val body = V1015SourceGuard.funBody(file, "fun startLogin() {")
            assertTrue(
                "$label 的 startLogin 没有切到 Dispatchers.IO —— 起本地服务会把界面线程按住",
                body.contains("scope.launch(Dispatchers.IO) {"),
            )
        }
    }

    @Test
    fun `按下去先有反馈再起服务`() {
        val geminiBody = V1015SourceGuard.funBody(gemini, "fun startLogin() {")
        val feedback = geminiBody.indexOf("_status.value = GeminiOAuthStatus.Waiting")
        val blocking = geminiBody.indexOf("val port = ensureCallbackServer()")
        assertTrue("Gemini 登录没有立刻置 Waiting 状态", feedback >= 0)
        assertTrue(
            "Gemini 登录的状态反馈排在了起服务之后，点下去到服务起来之间界面没有任何回应",
            feedback in 0 until blocking,
        )

        val codexBody = V1015SourceGuard.funBody(codex, "fun startLogin() {")
        val codexFeedback = codexBody.indexOf("_status.value = CodexOAuthStatus.Waiting")
        val codexBlocking = codexBody.indexOf("val port = ensureCallbackServer()")
        assertTrue("Codex 登录没有立刻置 Waiting 状态", codexFeedback >= 0)
        assertTrue(
            "Codex 登录的状态反馈排在了起服务之后",
            codexFeedback in 0 until codexBlocking,
        )
    }

    @Test
    fun `等网络解除阻塞有超时上限不会永久挂起`() {
        listOf(gemini to "Gemini", codex to "Codex").forEach { (file, label) ->
            val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(file))
            assertTrue(
                "$label 的 awaitNetworkUnblocked 又变成无限挂起了",
                code.contains("withTimeoutOrNull(NETWORK_UNBLOCK_WAIT_MS) { waitForNetworkUnblocked() }"),
            )
            assertTrue("$label 缺超时上限常量", code.contains("NETWORK_UNBLOCK_WAIT_MS = 15_000L"))
            // 回调本身必须仍然注册与注销成对，否则等不到时会漏一个 NetworkCallback。
            assertTrue("$label 缺 registerDefaultNetworkCallback", code.contains("registerDefaultNetworkCallback(callback)"))
            assertTrue("$label 缺 unregisterNetworkCallback", code.contains("unregisterNetworkCallback(callback)"))
        }
    }

    @Test
    fun `两个管理器都导入了线程切换与超时所需的符号`() {
        listOf(gemini to "Gemini", codex to "Codex").forEach { (file, label) ->
            val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(file))
            assertTrue("$label 没导入 Dispatchers", code.contains("import kotlinx.coroutines.Dispatchers"))
            assertTrue("$label 没导入 withTimeoutOrNull", code.contains("import kotlinx.coroutines.withTimeoutOrNull"))
        }
    }

    @Test
    fun `调用点仍然直接把 startLogin 挂在点击上`() {
        // 这条断言记录的是**外部事实**：调用点在界面线程上，所以线程切换只能做在管理器内部。
        // 哪天有人把 onClick 换成别的形式（比如自己再套一层 launch），这条会变红，
        // 提醒他回去看 startLogin 内部那层 IO 是否还需要，别两边都切或者两边都不切。
        val codexPage = "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/CodexProviderConfigure.kt"
        val geminiPage = "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/GeminiProviderConfigure.kt"
        assertTrue(
            "Codex 登录入口写法变了，请重新确认线程切换还在不在",
            V1015SourceGuard.codeHas(codexPage, "onClick = oauthManager::startLogin"),
        )
        assertTrue(
            "Gemini 登录入口写法变了，请重新确认线程切换还在不在",
            V1015SourceGuard.codeHas(geminiPage, "onClick = oauthManager::startLogin"),
        )
    }

    @Test
    fun `起服务这件事仍然是阻塞实现所以不能回到界面线程`() {
        // ensureCallbackServer 还是 @Synchronized + 阻塞 start：这正是它必须待在 IO 线程上的原因。
        listOf(gemini to "Gemini", codex to "Codex").forEach { (file, label) ->
            val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(file))
            assertTrue("$label 的 ensureCallbackServer 缺 @Synchronized", code.contains("@Synchronized"))
            assertTrue(
                "$label 又改用阻塞版 start(wait = false) 却不在 IO 线程里 —— 见 javap 取证：它会 runBlocking",
                code.contains(".start(wait = false)"),
            )
        }
    }
}
