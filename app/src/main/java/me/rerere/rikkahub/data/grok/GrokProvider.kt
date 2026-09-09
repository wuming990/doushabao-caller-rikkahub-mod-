package me.rerere.rikkahub.data.grok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.asResponseBody
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class GrokProvider(
    private val client: OkHttpClient,
    private val repository: GrokAccountRepository,
    private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.Grok> {

    override suspend fun listModels(providerSetting: ProviderSetting.Grok): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val request = Request.Builder()
                .url("$API_BASE/models")
                .grokHeaders(account)
                .get()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Grok models: ${response.code} ${response.body.string()}")
            }
            val models = json.parseToJsonElement(response.body.string())
                .jsonObject["data"]?.jsonArray
                ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // xAI's /models already lists the Grok Imagine image models next to the chat
                // models. Tag the image-generation ones as ModelType.IMAGE so they appear in the
                // image-generation picker (which filters strictly by ModelType.IMAGE), the same
                // way an image-capable OpenRouter model does. Classified by name off the live
                // list, so new Imagine image releases surface automatically with no pinned list.
                if (isGrokImageModel(id)) {
                    Model(
                        modelId = id,
                        displayName = id,
                        type = ModelType.IMAGE,
                        inputModalities = listOf(Modality.TEXT),
                        outputModalities = listOf(Modality.IMAGE),
                    )
                } else {
                    Model(
                        modelId = id,
                        displayName = id,
                        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                    )
                }
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val account = repository.acquireAccount()
        return responseApiFor(account).generateText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = messages,
            params = withGrokParams(params, account),
        )
    }

    /**
     * v285：一次请求内「零输出失败就换账号重试」。
     *
     * ## 为什么要有这一层
     *
     * v284 的真机故障：某个账号额度用尽时，**这一次请求直接失败**，冷却标记只影响下一次请求。
     * 用户的感受就是「轮询根本没跳过没额度的账号」，还得手动去设置页点一次刷新。
     *
     * ## 为什么这样重试是安全的
     *
     * 只在**一个字都还没吐出来**时才换号重发。已经吐了半截再换账号，模型会从头生成，
     * 用户会看到前半段重复 —— 那个取舍用户明确否掉过。零输出时不存在这个问题。
     * `emitted = true` 必须写在 `emit` **之前**：这样连「emit 本身失败」也算「已经开始输出」，
     * 绝不会重试。同时这也守住了 Flow 的异常透明性（下游抛的错原样上抛，不吞不重试）。
     *
     * ## 冷却标记为什么在这里再写一次
     *
     * 拦截器那条路只在 HTTP 层看得见。而 xAI 还有「HTTP 200 + 流内 error 帧」的形态，
     * 那种情况下拦截器什么都看不到，只有这里能从异常文字里认出来。
     * 而且这里是挂起上下文，可以**同步等标记落库**再 `acquireAccount()` ——
     * 拦截器里用的是 `scope.launch` 异步写，抢不过紧接着的挑号，会又挑到同一个账号。
     */
    override suspend fun streamText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val maxAttempts = repository.accounts.value.size.coerceIn(1, MAX_ACCOUNT_ATTEMPTS)
        var attempt = 0
        while (true) {
            attempt++
            val account = repository.acquireAccount()
            var emitted = false
            try {
                responseApiFor(account).streamText(
                    providerSetting = syntheticSetting(providerSetting, account),
                    messages = messages,
                    params = withGrokParams(params, account),
                ).collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
                return@flow
            } catch (cancel: CancellationException) {
                // 用户点了停止 —— 原样上抛，绝不当成「换个账号再试」
                throw cancel
            } catch (e: Throwable) {
                val text = e.message.orEmpty()
                val quotaExhausted = isGrokQuotaExhausted(text)
                if (quotaExhausted) {
                    repository.markQuotaExhausted(account.id, parseGrokModelUsageLimit(text))
                }
                if (!shouldRetryWithNextGrokAccount(text, emitted, attempt, maxAttempts)) throw e
            }
        }
    }

    // apiKey = the account's own OAuth token, so ResponseAPI's normal "Authorization: Bearer
    // <apiKey>" header lands on exactly the token grokHeaders used to set by hand.
    private fun syntheticSetting(providerSetting: ProviderSetting.Grok, account: GrokAccount) =
        ProviderSetting.OpenAI(
            id = providerSetting.id,
            enabled = providerSetting.enabled,
            name = providerSetting.name,
            models = providerSetting.models,
            baseUrl = API_BASE,
            apiKey = account.accessToken,
            useResponseApi = true,
        )

    private fun withGrokParams(params: TextGenerationParams, account: GrokAccount): TextGenerationParams {
        val reasoningEffort = params.model.abilities
            .takeIf { it.contains(ModelAbility.REASONING) }
            ?.let { grokReasoningEffort(params.reasoningLevel) }
        return params.copy(
            customHeaders = params.customHeaders + CLI_CLIENT_HEADERS,
            customBody = params.customBody + listOfNotNull(
                reasoningEffort?.let { effort ->
                    CustomBody(
                        key = "reasoning",
                        value = buildJsonObject { put("effort", effort) },
                    )
                },
            ),
        )
    }

    /**
     * Wraps [client] with an account-scoped interceptor so a 401 (invalidated token) on this
     * account is detected from the same response that carries the model reply, without a second
     * network round-trip. Also patches a missing Content-Type so OkHttp's SSE factory recognizes
     * the stream - some xAI backend responses omit it.
     */
    private fun responseApiFor(account: GrokAccount): ResponseAPI {
        val accountAwareClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 401) {
                    scope.launch { repository.markInvalid(account.id) }
                }
                // v285：额度类失败 → 给这个账号上冷却，并把报错里的用量记下来给面板显示。
                //
                // 两个关键取舍，都是 v284 的真机故障换来的：
                //
                // 1. **不看状态码，只看报错文字。** v284 只认 429，而「余额用尽」实际走 402/403，
                //    于是账号永远不上冷却、轮询永远不跳过它，用户必须手动去设置页点「刷新」
                //    才能继续用别的账号。判定见 [isGrokQuotaExhausted]。
                // 2. **用 peekBody 偷看一份副本，绝不重建响应体。** v284 用 `body.string()` 读完
                //    再 `toResponseBody()` 塞回去，而下游 ResponseAPI 读错误正文用的
                //    `stringSafe()` 只认 OkHttp 原装的 `RealResponseBody`，拿到重建过的 body
                //    一律返回 null —— 错误内容就此丢失，`onFailure` 里 exception 保持为 null，
                //    `close(null)` 等于「正常结束」，界面上就是「空回复、零报错」。
                //    peekBody 不消费原流，下游照样能读到完整错误。
                //
                // peekBody 只对**失败**响应用：成功的 SSE 是长连接，peek 会一直等着填满缓冲区。
                if (!response.isSuccessful) {
                    val raw = runCatching { response.peekBody(ERROR_PEEK_BYTES).string() }
                        .getOrNull().orEmpty()
                    if (isGrokQuotaExhausted(raw)) {
                        val limit = parseGrokModelUsageLimit(raw)
                        scope.launch { repository.markQuotaExhausted(account.id, limit) }
                    }
                    return@addNetworkInterceptor response
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
        return ResponseAPI(accountAwareClient)
    }

    // xAI's Grok Imagine image generation is OpenAI-compatible: a single POST to
    // /v1/images/generations, authenticated with the same subscription OAuth token used for chat.
    // Unlike OpenAI it takes aspect_ratio + resolution rather than a pixel size string.
    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val account = repository.acquireAccount()
        val body = buildJsonObject {
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("aspect_ratio", grokImageAspectRatio(params.size))
            put("resolution", GROK_IMAGE_RESOLUTION)
            put("n", params.numOfImages.coerceIn(1, 10))
            // Ask for base64 directly; grok-imagine's default URLs are short-lived (imgen.x.ai
            // temp URLs that 404 within minutes). parseGrokImageResponse still falls back to
            // downloading a url if a model ignores this.
            put("response_format", "b64_json")
        }
        val request = Request.Builder()
            .url("$API_BASE/images/generations")
            .grokHeaders(account)
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to generate image: ${response.code} $bodyStr")
            }
            parseGrokImageResponse(bodyStr)
        }
        items.forEach { emit(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun parseGrokImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val data = json.parseToJsonElement(bodyStr).jsonObject["data"]?.jsonArray
            ?: error("No data in Grok image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64 = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64 != null) {
                ImageGenerationItem(data = b64, mimeType = "image/png")
            } else {
                // grok-imagine returns short-lived imgen.x.ai URLs that 404 within minutes, so
                // materialise the bytes immediately rather than handing the URL downstream.
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("Grok image response had neither b64_json nor url")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem =
        withContext(Dispatchers.IO) {
            val response = client.newCall(Request.Builder().url(url).get().build()).await()
            if (!response.isSuccessful) {
                error("Failed to download generated image: ${response.code}")
            }
            val respBody = response.body
            val mimeType = respBody.contentType()?.toString() ?: "image/png"
            ImageGenerationItem(data = Base64.encode(respBody.bytes()), mimeType = mimeType)
        }

    private fun Request.Builder.grokHeaders(account: GrokAccount): Request.Builder {
        return header("Authorization", "Bearer ${account.accessToken}")
            .grokCliClientHeaders()
    }

    private companion object {
        // v283：三条请求（模型列表 / 聊天 / 图片）都必须打官方 CLI 代理。
        //
        // v274~v282 打的是公开开发者接口 https://api.x.ai/v1，真机结果是
        // 「You have run out of credits or need a Grok subscription」——那不是账号没订阅，
        // 而是公开接口花的是按量付费额度，SuperGrok 订阅带的日常额度只在 CLI 代理上生效。
        // 完整证据链见 [GrokCliClient] 的注释。
        const val API_BASE = GrokCliClient.BASE_URL

        /**
         * v283：聊天走 [ResponseAPI]，请求头只能通过 [TextGenerationParams.customHeaders] 带进去，
         * 所以这里要把同一组客户端标识重新表达一遍（[grokCliClientHeaders] 作用于 OkHttp 的
         * Request.Builder，那条路用不上）。两处的值必须一致 —— 有测试按原文钉住。
         */
        val CLI_CLIENT_HEADERS = listOf(
            CustomHeader("User-Agent", GrokCliClient.USER_AGENT),
            CustomHeader("X-XAI-Token-Auth", GrokCliClient.TOKEN_AUTH),
            CustomHeader("x-grok-client-identifier", GrokCliClient.CLIENT_IDENTIFIER),
            CustomHeader("x-grok-client-version", GrokCliClient.CLIENT_VERSION),
            CustomHeader("x-authenticateresponse", GrokCliClient.AUTHENTICATE_RESPONSE),
        )

        /**
         * v285：一次请求内最多试几个账号。取账号数与这个上限的较小值 ——
         * 账号很多时不该为一条消息把所有号都撞一遍，那样用户要干等很久。
         */
        const val MAX_ACCOUNT_ATTEMPTS = 4

        /**
         * v285：失败响应最多偷看多少字节。xAI 的错误正文是一小段 JSON，64 KB 绰绰有余；
         * 设上限是为了万一后端把 HTML 错误页塞回来时不会把整页读进内存。
         */
        const val ERROR_PEEK_BYTES = 64L * 1024

        // Default output resolution for Grok Imagine image generation ("1k" or "2k").
        const val GROK_IMAGE_RESOLUTION = "1k"
    }
}

/**
 * v285：这次流式失败之后，该不该换下一个账号重发？
 *
 * 抽成顶层纯函数是为了能真单测 —— 放在 [GrokProvider] 里就只能靠读源码做字符串断言了。
 *
 * 三个条件缺一不可：
 * - **一个字都还没吐出来。** 已经吐了半截再换账号，模型会从头生成，用户看到前半段重复；
 *   那个取舍用户明确否掉过。
 * - **还有账号可试。** 上限见 [GrokProvider.MAX_ACCOUNT_ATTEMPTS]。
 * - **确实是额度/余额没了。** 网络抖动、模型不存在、客户端版本过期换号都没用。
 */
internal fun shouldRetryWithNextGrokAccount(
    errorText: String,
    alreadyEmitted: Boolean,
    attempt: Int,
    maxAttempts: Int,
): Boolean {
    if (alreadyEmitted) return false
    if (attempt >= maxAttempts) return false
    return isGrokQuotaExhausted(errorText)
}

// The Grok Imagine image-generation models are listed by /models under the "*image*" family
// (e.g. grok-imagine-image, grok-imagine-image-quality). "grok-imagine-video" has no "image"
// substring, so the video-generation models are naturally excluded.
internal fun isGrokImageModel(id: String): Boolean = id.contains("image", ignoreCase = true)

// v274：适配我们树的 ImageGenSize（ExTV 原版用 ImageAspectRatio 枚举，我们 2.4.16 的
// ImageGenerationParams 用 size 字符串）——按尺寸字符串的横竖映射到 Grok 的宽高比。
internal fun grokImageAspectRatio(size: String): String = when (size) {
    "1536x1024", "1792x1024" -> "16:9"
    "1024x1536", "1024x1792" -> "9:16"
    else -> "1:1" // auto / 方形尺寸 / 未知值一律方形
}

internal fun grokReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "high"
    }
}
