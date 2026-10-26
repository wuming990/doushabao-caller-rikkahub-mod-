package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody

/**
 * v1009：Codex 通道的请求构造整体改为复用官方新版的 ChatGPT 通道
 * （ai 模块的 [ChatGPTProvider]），这里只保留「账号池 + 用量/失效记账」这层壳。
 *
 * 为什么改：v274 那套是自己拼 Codex 请求（写死 `codex_cli_rs/0.144.5` 的 UA、
 * 自加 `OpenAI-Beta: responses=experimental`、用通用 Responses 能力集，
 * 没有 `session-id` 头）。2026-10 真机实测：登录成功、模型列表能拉到，
 * 但一发消息就被服务端拒绝，报
 * `401 We got your request, but your ChatGPT login did not make it to this service.`
 * —— 同一条票在 /models 能用、在 /responses 不能用，说明问题出在「发消息这次请求的形状」，
 * 不是账号本身。官方新版的三条通道（ChatGPT / Grok / Gemini）才是随上游维护的那一套，
 * 所以把 Codex 也改成走它，两边的地址、请求头、请求体一字不差。
 *
 * 保留不变：ProviderSetting.Codex 这个入口、CodexAccountRepository 里的登录账号与轮换、
 * 响应头里的额度记账、401 标失效。也就是说老用户升级后**不需要重新登录**。
 *
 * v1009 审查位抓到的三处（已修）：
 * 1. 官方通道把 `ReasoningLevel.MAX` 原样发成 `effort:"max"`，而 Codex 只认到 `xhigh`
 *    → 委托前统一降档（[codexSafeReasoningLevel]）；
 * 2. 只在「刷新后仍 401」（`priorResponse != null`）时才把账号标失效 —— 首次 401 是刷新握手，
 *    标了会把健康账号打出轮换池；
 * 3. 401 重试必须走 [CodexAccountRepository.refreshToken]（强制换票且不掺用量抓取），
 *    否则用量接口一失败就退回旧票，等于白重试。
 */
class CodexProvider(
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    @Suppress("unused") private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.Codex> {

    private val credentials = CodexAccountCredentials(repository)

    private val chatGpt: ChatGPTProvider by lazy {
        ChatGPTProvider(usageAwareClient(), credentials)
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Codex): List<Model> =
        chatGpt.listModels(providerSetting.asChatGpt())

    override suspend fun generateText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult =
        chatGpt.generateText(providerSetting.asChatGpt(), messages, params.codexSafe())

    override suspend fun streamText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> =
        chatGpt.streamText(providerSetting.asChatGpt(), messages, params.codexSafe())

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Codex provider")
    }

    /** Codex 后端只认到 `xhigh`；`max` 原样发过去会被拒。 */
    private fun TextGenerationParams.codexSafe(): TextGenerationParams {
        val safe = codexSafeReasoningLevel(reasoningLevel)
        return if (safe == reasoningLevel) this else copy(reasoningLevel = safe)
    }

    /**
     * 官方 ChatGPT 通道不认 ProviderSetting.Codex，这里做一次等价映射：
     * id / 名称 / 模型列表 / 自定义请求头照搬，凭据不放在 setting 里（由 [credentials] 现取）。
     */
    private fun ProviderSetting.Codex.asChatGpt() = ProviderSetting.ChatGPT(
        id = id,
        enabled = enabled,
        name = name,
        models = models,
        customHeaders = customHeaders,
        builtIn = builtIn,
    )

    /**
     * 在官方通道的 client 上再包一层：记账（`x-codex-*` 响应头）、真正的失效判定，
     * 外加补一个缺失的 Content-Type，避免个别响应被 OkHttp 当成普通文本而不是 SSE。
     */
    private fun usageAwareClient(): OkHttpClient = client.newBuilder()
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            // v1009：按 `ChatGPT-Account-ID` 找账号，比按 token 找稳 —— token 刷新后
            // 请求头里已经是新票，用旧票反查会找不到账号、额度条就不动了（自查发现的退化）。
            val accountId = request.header("ChatGPT-Account-ID")
            val token = request.header("Authorization")?.removePrefix("Bearer ")
            val account = repository.accounts.value.firstOrNull { it.chatgptAccountId == accountId }
                ?: repository.accounts.value.firstOrNull { it.accessToken == token }
            val response = chain.proceed(request)
            if (account != null) {
                parseCodexUsage(response.headers)?.let { usage ->
                    scope.launch { repository.updateUsage(account.id, usage) }
                }
                // v1009：**只有「刷新之后仍然 401」才标失效**。
                // `priorResponse != null` 说明这是 OkHttp 的 authenticator 用新票重试后的第二次响应；
                // 首次 401（priorResponse == null）只是握手，在这一步标失效会把刚刷新成功的
                // 健康账号打出轮换池，下一条消息直接报「没有可用账号」。
                // 反过来，重试后还 401 说明这张票是真的不被接受 —— 该退休，换下一个号。
                if (response.code == 401 && response.priorResponse != null) {
                    scope.launch { repository.markInvalid(account.id) }
                }
            }
            if (response.isSuccessful && response.header("Content-Type") == null) {
                val body = response.body
                response.newBuilder()
                    .header("Content-Type", "text/event-stream")
                    .body(
                        body.source().asResponseBody(
                            contentType = "text/event-stream".toMediaType(),
                            contentLength = body.contentLength(),
                        )
                    )
                    .build()
            } else {
                response
            }
        }
        .build()
}

/**
 * 把 Codex 账号池接到官方通道需要的 [AccountCredentialProvider] 上。
 *
 * - 平时：交给 [CodexAccountRepository.acquireAccount]，它自己会挑可用账号、快过期先刷新；
 * - 401 重试：按被拒的 token 找回账号，**强制换票**再试（官方通道的 authenticator 只重试一次）。
 */
private class CodexAccountCredentials(
    private val repository: CodexAccountRepository,
) : AccountCredentialProvider {
    override suspend fun getCredential(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential = withContext(Dispatchers.IO) {
        val account = if (rejectedAccessToken == null) {
            repository.acquireAccount()
        } else {
            val current = repository.accounts.value
                .firstOrNull { it.accessToken == rejectedAccessToken }
            if (current == null) {
                // 并发请求已经换过票了：直接用新的
                repository.acquireAccount()
            } else {
                try {
                    // 只换票、不抓用量：抓用量失败不该把「已经换到的新票」吞掉。
                    repository.refreshToken(current.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 换票失败（多为 refresh token 也废了，仓库已标 INVALID）：
                    // 把旧票交回去，让 401 的真实报错原样显示给用户。
                    current
                }
            }
        }
        OAuthCredential(
            accessToken = account.accessToken,
            refreshToken = account.refreshToken,
            expiresAt = account.expiresAt,
            accountId = account.chatgptAccountId,
            email = account.email,
        )
    }
}

/**
 * Codex 后端支持的思考档位只到 `xhigh`（`max` 会原样变成 `reasoning.effort = "max"` 被拒）。
 * 抽成纯函数是为了能真单测（接线在 [CodexProvider.codexSafe]）。
 */
internal fun codexSafeReasoningLevel(level: ReasoningLevel): ReasoningLevel =
    if (level == ReasoningLevel.MAX) ReasoningLevel.XHIGH else level
