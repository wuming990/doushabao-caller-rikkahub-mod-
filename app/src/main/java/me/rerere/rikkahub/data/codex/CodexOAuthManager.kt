package me.rerere.rikkahub.data.codex

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.common.http.await
import me.rerere.rikkahub.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class CodexOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var callbackPort: Int? = null
    private val sessions = ConcurrentHashMap<String, OAuthSession>()
    private val _status = MutableStateFlow<CodexOAuthStatus>(CodexOAuthStatus.Idle)
    val status: StateFlow<CodexOAuthStatus> = _status.asStateFlow()

    fun startLogin() {
        val state = randomUrlSafe(32)
        // v1016：整段挪出界面线程。调用点是 Compose 的 onClick = oauthManager::startLogin，
        // 而 embeddedServer(CIO).start(wait = false) 内部是 runBlocking（已在打包实际使用的
        // ktor-server-cio 3.5.2 字节码里用 javap 确认：CIOApplicationEngine.start(Z) 调
        // CoroutineUtilsJvmKt.runBlockingBridge）。它把界面线程按住的这段时间里浏览器也不会弹出来，
        // 加上 ensureCallbackServer 的 @Synchronized，用户连点几次全部排在锁后面 ——
        // 就是用户报的"点登录卡死、越点越死"。与 GeminiOAuthManager 同一族，改法保持一致。
        _status.value = CodexOAuthStatus.Waiting
        scope.launch(Dispatchers.IO) {
        try {
            val verifier = randomUrlSafe(64)
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
            )
            val port = ensureCallbackServer()
            val redirect = "http://localhost:$port/auth/callback"
            sessions[state] = OAuthSession(
                verifier = verifier,
                redirectUri = redirect,
            )
            _status.value = CodexOAuthStatus.Waiting

            val authUrl = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", DEFAULT_SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "codex_cli_rs")
                .build()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (error: Throwable) {
            sessions.remove(state)
            _status.value = CodexOAuthStatus.Error(
                if (error.message == CALLBACK_PORTS_UNAVAILABLE) {
                    context.getString(R.string.codex_oauth_ports_unavailable)
                } else {
                    error.message ?: "Unable to open the OpenAI sign-in page"
                }
            )
        }
        }
    }

    fun consumeResult() {
        _status.value = CodexOAuthStatus.Idle
    }

    @Synchronized
    private fun ensureCallbackServer(): Int {
        callbackPort?.let { return it }
        var lastError: Throwable? = null
        for (port in CALLBACK_PORTS) {
            try {
                server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                    routing {
                        get("/auth/callback") {
                            val callbackState = call.request.queryParameters["state"]
                            val code = call.request.queryParameters["code"]
                            val error = call.request.queryParameters["error"]
                            val session = callbackState?.let(sessions::remove)
                            when {
                                session == null -> {
                                    _status.value = CodexOAuthStatus.Error("OAuth state mismatch")
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                !error.isNullOrBlank() -> {
                                    _status.value = CodexOAuthStatus.Error(error)
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                code.isNullOrBlank() -> {
                                    _status.value = CodexOAuthStatus.Error("Missing authorization code")
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                else -> {
                                    call.respondText(callbackPage(true), ContentType.Text.Html)
                                    scope.launch {
                                        try {
                                            awaitNetworkUnblocked()
                                            val account = exchangeCode(code, session)
                                            _status.value = CodexOAuthStatus.Success(account.id)
                                            // v1009：取消原样上抛；刷新失败不影响「登录已成功」这个结果
                                            try {
                                                repository.refreshAccount(account.id)
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (_: Exception) {
                                            }
                                        } catch (cancel: CancellationException) {
                                            // 用户离开 / 作用域被取消：不许当成登录失败写状态
                                            throw cancel
                                        } catch (error: Throwable) {
                                            Log.e(
                                                TAG,
                                                "OAuth token exchange failed: " +
                                                    "${error::class.java.name}: ${error.message}",
                                                error,
                                            )
                                            _status.value = CodexOAuthStatus.Error(
                                                error.message ?: "OAuth token exchange failed"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
                callbackPort = port
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(CALLBACK_PORTS_UNAVAILABLE, lastError)
    }

    private suspend fun awaitNetworkUnblocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // v1016：加超时兜底，不再无限期等。onBlockedStatusChanged 只在「阻塞状态发生变化」时回调，
        // 网络本来就通的时候可能一次都不来；原来的无限挂起会让浏览器已经授权成功、应用却永远停在
        // 「等待授权」。等不到就往下走，最坏是这次换 token 失败并报出原因，比永久卡住好。
        withTimeoutOrNull(NETWORK_UNBLOCK_WAIT_MS) { waitForNetworkUnblocked() }
    }

    private suspend fun waitForNetworkUnblocked() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        suspendCancellableCoroutine { continuation ->
            lateinit var callback: ConnectivityManager.NetworkCallback
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (!blocked && continuation.isActive) {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                        continuation.resume(Unit)
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    private suspend fun exchangeCode(code: String, session: OAuthSession): CodexAccount {
        val response = client.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", CLIENT_ID)
                        .add("code", code)
                        .add("redirect_uri", session.redirectUri)
                        .add("code_verifier", session.verifier)
                        .build()
                )
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Token exchange failed: ${response.code}")
        }
        return repository.saveLogin(body)
    }

    private fun callbackPage(success: Boolean): String {
        val status = if (success) "success" else "error"
        val deepLink = "rikkahub://codex/oauth?status=${URLEncoder.encode(status, Charsets.UTF_8.name())}"
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="refresh" content="0; url=$deepLink">
                <title>RikkaHub Codex OAuth</title>
              </head>
              <body>
                <p>${if (success) "Returning to RikkaHub..." else "Sign-in failed."}</p>
                <p><a href="$deepLink">Return to RikkaHub</a></p>
                <script>
                  window.location.replace("$deepLink");
                  setTimeout(function () { window.location.href = "$deepLink"; }, 500);
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TAG = "CodexOAuthManager"
        private const val CALLBACK_PORTS_UNAVAILABLE = "OAuth callback ports are unavailable"

        /** v1016：等「网络解除阻塞」的最长时间，超时就照样往下走（见 awaitNetworkUnblocked）。 */
        private const val NETWORK_UNBLOCK_WAIT_MS = 15_000L
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val DEFAULT_SCOPES = "openid profile email offline_access"
        const val REFRESH_SCOPES = "openid profile email"
        private val CALLBACK_PORTS = listOf(1455, 1457)
    }
}

private data class OAuthSession(
    val verifier: String,
    val redirectUri: String,
)

sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data object Waiting : CodexOAuthStatus
    data class Success(val accountId: String) : CodexOAuthStatus
    data class Error(val message: String) : CodexOAuthStatus
}
