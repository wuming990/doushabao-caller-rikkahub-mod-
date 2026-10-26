package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KEY_RETRYABLE_CODES
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.canRetryWithNextKey
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.withKeyRetry
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.isKeyRetryableError
import me.rerere.ai.util.streamFailureFallback
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.sse.newEventSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

/**
 * Responses API 请求的目标：发往哪里、怎么鉴权，以及该网关对请求体的特殊要求（[capabilities]）。
 *
 * OpenAI 兼容提供商由 [ProviderSetting.OpenAI] 生成；地址写死的账号型提供商各自构造。
 */
internal class ResponsesEndpoint(
    val url: String,
    val capabilities: ResponseProviderCapabilities = ResponseProviderCapabilities(),
    // 构建请求时调用，负责请求头和鉴权；可挂起，以便先刷新 token
    val configureRequest: suspend Request.Builder.() -> Unit,
    /**
     * v323 合并 3.1.0：官方把「怎么发请求」抽成了 endpoint，我们原来的多密钥轮询是直接写在
     * 请求构造里的。这里把轮询状态挂到 endpoint 上，两边的能力都不丢：
     * - 官方：账号型 provider 自己构造 endpoint，不传这个字段（null），行为与官方一字不差；
     * - 二改：API Key 型 provider 传进来，换 key / 标记失败 / 重发都还能用。
     */
    val keyRotation: KeyRotation? = null,
)

/**
 * v323：一次生成过程中的密钥轮询状态。
 *
 * 为什么要有它：官方的 configureRequest 每次被调用都会重新取一次 key（keyRoulette.next），
 * 但**失败标记**和「这一趟已经用过哪把」需要跨请求保存，否则限流 key 会被反复选中。
 */
internal class KeyRotation(
    private val apiKey: String,
    private val providerId: String,
    private val roulette: KeyRoulette,
) {
    var currentKey: String = roulette.next(apiKey, providerId)
        private set

    val poolSize: Int get() = roulette.keyCount(apiKey)

    fun markFailed() = roulette.markFailed(providerId, currentKey)

    fun rotate() {
        currentKey = roulette.next(apiKey, providerId)
    }
}

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = generateText(providerSetting.toResponsesEndpoint(params), messages, params)

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = streamText(providerSetting.toResponsesEndpoint(params), messages, params)

    private fun ProviderSetting.OpenAI.toResponsesEndpoint(params: TextGenerationParams) = ResponsesEndpoint(
        url = "$baseUrl$responsesPath",
        capabilities = resolveResponseProviderCapabilities(baseUrl.toHttpUrl().host),
        configureRequest = {
            headers(mergeCustomHeaders(params.customHeaders))
            addHeader("Authorization", "Bearer ${keyRoulette.next(apiKey, id.toString())}")
            configureReferHeaders(baseUrl)
            configureSessionHeaders(baseUrl, params.sessionId)
        },
    )

    internal suspend fun generateText(
        endpoint: ResponsesEndpoint,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(
            capabilities = endpoint.capabilities,
            messages = messages,
            params = params,
            stream = false,
        )

        // v255/v290：密钥类错误自动换下一把 key；最多把密钥池轮完一遍。
        // 官方 3.1.0 把请求构造抽成 endpoint.configureRequest，这里复用它的头/鉴权，
        // 只在失败时调用 rotation 换 key 后重建请求。
        val rotation = endpoint.keyRotation
        val maxAttempts = rotation?.poolSize ?: 1
        var lastError: Throwable? = null
        repeat(maxAttempts) {
            val request = Request.Builder()
                .url(endpoint.url)
                .apply { endpoint.configureRequest(this) }
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

            try {
                // await() waits for the response headers; reading the body can still block.
                return@withContext client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
                    }

                    val bodyStr = response.body.string()
                    Log.i(TAG, "generateText: $bodyStr")
                    val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
                    parseResponseOutput(bodyJson)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (!isKeyRetryableError(e)) throw e
                lastError = e
                rotation?.markFailed()
                rotation?.rotate()
            }
        }
        throw lastError ?: IllegalStateException("unreachable")
    }

    internal fun streamText(
        endpoint: ResponsesEndpoint,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            capabilities = endpoint.capabilities,
            messages = messages,
            params = params,
            stream = true,
        )

        // v255/v290：密钥轮询状态（endpoint.keyRotation 为 null 时退化为单 key，与官方一致）。
        // emittedAny 记录本流是否已对用户吐出过内容 —— 吐过之后绝不换 key 重发（v285 规矩）。
        val rotation = endpoint.keyRotation
        val keyPoolSize = rotation?.poolSize ?: 1
        var keySwitchCount = 0
        var emittedAny = false

        val initialRequest = Request.Builder()
            .url(endpoint.url)
            .apply { endpoint.configureRequest(this) }
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        // v323：回调里不能调用 suspend 的 configureRequest，换 key 时只替换鉴权头。
        fun requestWithKey(key: String): Request =
            initialRequest.newBuilder().header("Authorization", "Bearer $key").build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val decoder = ResponseApiStreamDecoder()

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            // v290：只要对用户发出去过内容就标记 —— 换 key 重发的双保险之一（见 trySwitchKey）
            if (chunks.any()) emittedAny = true
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        // v255：当前生效的事件源。必须在 listener 之前声明 —— 换 key 重试时 onFailure 会重新
        // 赋值，awaitClose 取消的永远是最新那一个（否则取消的是已经失败的旧连接）。
        var activeEventSource: EventSource? = null

        // v290：换下一把 key 并重建连接的统一入口。返回 true 表示已切换，调用方直接 return。
        // canRetryWithNextKey 内含双保险：已吐过内容不重发；单趟最多把密钥池轮完（每把一遍）。
        // 池子轮完后错误上抛，由上层续跑 / 网络重试开新一趟 —— 冷却满 5 分钟的 key 会自动
        // 恢复参与选择，形成「反复轮询直到续跑次数耗尽」的完整闭环。
        fun trySwitchKey(reason: String, retryable: Boolean, source: EventSourceListener): Boolean {
            if (!retryable) return false
            if (!canRetryWithNextKey(keySwitchCount, keyPoolSize, emittedAny)) return false
            keySwitchCount++
            rotation?.markFailed()
            rotation?.rotate()
            Log.w(TAG, "$reason，已切换 key 重试（第 $keySwitchCount 把 / 池共 $keyPoolSize 把）")
            activeEventSource =
                client.newEventSource(requestWithKey(rotation?.currentKey.orEmpty()), source)
            return true
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: $id/$type $data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    // v290：流内 error（HTTP 200 + SSE error 事件）以前直接 close —— 换 key 逻辑
                    // 只挂在 onFailure 的 HTTP 状态码上，流内限流一次 key 都不换就放弃。
                    // 现在按密钥类文本判定补上。
                    if (trySwitchKey("onEvent: ${e.message}", isKeyRetryableError(e), this)) return
                    close(e)
                }
            }

            override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                // v255：密钥类错误（401/402/403/429）→ 标记失败并换下一个 key 重试。
                // v290：换 key 次数与重发保护收敛进 trySwitchKey（把密钥池轮完为止）。
                val code = response?.code
                if (trySwitchKey(
                        reason = "onFailure: key 错误(code=$code)",
                        retryable = code != null && code in KEY_RETRYABLE_CODES,
                        source = this,
                    )
                ) {
                    return
                }

                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    // v285：exception 为 null 时**绝不能**直接 close() —— 在 callbackFlow 里那等于
                    // 告诉上层「这次回答顺利结束了」，界面上就是空回复、一个字都没有、零报错，
                    // 自动续跑也因为「看起来正常完成」而不会触发。
                    // OkHttp 在「响应不是 event-stream」这条路上给的 t 就是 null，而错误正文又
                    // 可能读不出来（stringSafe 只认原装 RealResponseBody），两个都空是真实场景：
                    // v284 的 Grok 额度用尽就是这样变成空回复的。
                    close(exception ?: streamFailureFallback(response, bodyRaw))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                sendChunks(decoder.onClosed())
                close()
            }
        }

        activeEventSource = client.newEventSource(requestWithKey(rotation?.currentKey.orEmpty()), listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            activeEventSource?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    internal fun buildRequestBody(
        capabilities: ResponseProviderCapabilities,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (capabilities.supportsSamplingParams) {
                if (isModelAllowTemperature(params.model)) {
                    if (params.temperature != null) put("temperature", params.temperature)
                    if (params.topP != null) put("top_p", params.topP)
                }
                if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)
            }

            // system instructions
            val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }
                ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }
            val systemRole = capabilities.systemMessageRole
            if (systemPrompt != null && systemRole == null) put("instructions", systemPrompt)

            // messages
            putJsonArray("input") {
                if (!systemPrompt.isNullOrEmpty() && systemRole != null) {
                    add(buildJsonObject {
                        put("role", systemRole)
                        put("content", systemPrompt)
                    })
                }
                buildMessages(messages).forEach { add(it) }
            }

            // reasoning
            val reasoningModel = params.model.abilities.contains(ModelAbility.REASONING)
            if (reasoningModel) {
                val reasoning = buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    capabilities.reasoningEffort(params.reasoningLevel, params.model.modelId)?.let { put("effort", it) }
                }
                if (reasoning.isNotEmpty()) put("reasoning", reasoning)
            }
            if (capabilities.supportEncryptedContent &&
                (reasoningModel || capabilities.alwaysIncludeEncryptedContent)) {
                put("include", buildJsonArray {
                    add("reasoning.encrypted_content")
                })
            }
            // 会话 ID 作为 prompt_cache_key，同一会话的请求能命中提示缓存
            params.sessionId?.let { put("prompt_cache_key", it) }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            if (useFunctionTools || params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                                // Response API 不传 strict 时默认按严格模式处理, 会把 schema 里的可选字段全部变成必填,
                                // 模型只能给本应省略的字段硬填默认值 (0 / "" / []), 导致工具参数校验失败
                                put("strict", false)
                            })
                        }
                    }
                    // built-in tools
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2.5-sunburst")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    internal fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        messages
            .filter { message ->
                message.role != MessageRole.SYSTEM && (
                    message.isValidToUpload() || message.parts.any { part ->
                        part is UIMessagePart.Reasoning &&
                            part.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent != null
                    }
                    )
            }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message)
                } else {
                    addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    val emittedReasoningIds = mutableSetOf<String>()
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                val reasoningId = reasoningMetadata?.reasoningId
                                if (reasoningId != null && !emittedReasoningIds.add(reasoningId)) {
                                    return@forEach
                                }
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningParts = if (reasoningId == null) {
                                    listOf(part)
                                } else {
                                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().filter {
                                        it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId == reasoningId
                                    }
                                }
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    reasoningId?.let { put("id", it) }
                                    put("summary", buildJsonArray {
                                        reasoningParts
                                            .filter { it.reasoningType == ReasoningType.SUMMARY_TEXT }
                                            .filter { it.reasoning.isNotEmpty() }
                                            .forEach {
                                                add(buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", it.reasoning)
                                                })
                                            }
                                    })
                                    // 二改版: 不回传 reasoning 的 content。
                                    // Responses API 的 reasoning 输入项只接受 summary(+encrypted_content),
                                    // content 的允许长度是 0; 把思考正文放进去会被拒:
                                    // Invalid 'input[n].content': array too long.
                                    // Expected an array with maximum length 0, but got an array with length 1.
                                    // 思考正文仍完整保留在本地会话里显示, 只是不再随请求上传。
                                    //
                                    // v234 合并上游 2.4.10：上游把这里改成「只有 encryptedContent == null
                                    // 时才发 content」，用来修「连续对话重复发送推理内容」。二改的做法更彻底
                                    // ——无论有没有 encrypted_content 都不发 content，因为实测被拒与
                                    // encrypted_content 是否存在无关。上游那处修复想达到的效果（不重复发推理
                                    // 正文）在这里天然成立，因此只吸收它的局部变量重构，便于将来继续对齐上游。
                                    val encryptedContent = reasoningMetadata?.encryptedContent
                                    encryptedContent?.let {
                                        put("encrypted_content", it)
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part))
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            is UIMessagePart.ServerTool -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addServerToolItem(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 同一批并发工具调用需先输出全部 function_call，再输出对应结果。
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                put(
                                    "output",
                                    tool.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                )
                            }
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addServerToolItem(tool: UIMessagePart.ServerTool) {
        val metadata = tool.metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.OPENAI_RESPONSES) return

        val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.OPENAI_RESPONSES }
        if (rawCall != null) {
            add(rawCall)
            return
        }

        add(buildJsonObject {
            put("type", "${tool.toolName.removeSuffix("_call")}_call")
            put("id", tool.toolCallId)
            put("status", tool.status.toOpenAIStatus())
            tool.input?.let { input ->
                if (tool.toolName.removeSuffix("_call") == "web_search") {
                    put("action", input)
                } else if (input is JsonObject) {
                    input.forEach { (key, value) -> put(key, value) }
                } else {
                    put("input", input)
                }
            }
            tool.output?.let { put("output", it) }
        })
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    internal fun parseResponseOutput(jsonObject: JsonObject): TextGenerationResult {
        println(jsonObject)
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val reasoningMetadata = OpenAIReasoningMetadata(
                        reasoningId = output["id"]?.jsonPrimitive?.contentOrNull,
                        encryptedContent = output["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                    val reasoningPartStart = parts.size
                    output["summary"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now(),
                                        metadata = reasoningMetadata,
                                        reasoningType = ReasoningType.SUMMARY_TEXT,
                                    )
                                )
                            }
                        }
                    }
                    output["content"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        if (part["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                            parts.add(
                                UIMessagePart.Reasoning(
                                    reasoning = part["text"]?.jsonPrimitive?.content ?: error("text not found"),
                                    createdAt = Clock.System.now(),
                                    finishedAt = Clock.System.now(),
                                    metadata = reasoningMetadata,
                                    reasoningType = ReasoningType.REASONING_TEXT,
                                )
                            )
                        }
                    }
                    if (parts.size == reasoningPartStart) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = reasoningMetadata,
                                reasoningType = ReasoningType.REASONING_TEXT,
                            )
                        )
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                // 部分兼容网关会在服务端执行函数工具，并把结果作为 function_call_output 一起返回
                "function_call_output" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val index = parts.indexOfLast { it is UIMessagePart.Tool && it.toolCallId == callId }
                    if (index >= 0) {
                        val tool = parts[index] as UIMessagePart.Tool
                        parts[index] = tool.copy(output = listOf(UIMessagePart.Text(output.functionCallOutputText())))
                    }
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }

                else -> if (isOpenAIServerToolCall(type)) {
                    parts.add(output.toOpenAIServerTool())
                }
            }
        }

        return TextGenerationResult(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
            ),
            finishReason = jsonObject["status"]?.jsonPrimitive?.contentOrNull,
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            // v297：思考 token 单列（Responses API 放在 output_tokens_details 下）
            reasoningTokens = jsonObject["output_tokens_details"]?.jsonObjectOrNull
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}

// function_call_output.output 可以是字符串，也可以是 input_text 等内容数组
internal fun JsonObject.functionCallOutputText(): String = when (val output = get("output")) {
    null, is JsonNull -> ""
    is JsonPrimitive -> output.content
    is JsonArray -> output.mapNotNull { item ->
        item.jsonObjectOrNull?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "input_text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
    }.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: output.toString()
    else -> output.toString()
}

internal fun isOpenAIServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type !in setOf(
        "function_call",
        "custom_tool_call",
        "computer_call",
        "local_shell_call",
        "shell_call",
        "image_generation_call",
    )

internal fun JsonObject.toOpenAIServerTool(): UIMessagePart.ServerTool {
    val type = get("type")?.jsonPrimitive?.contentOrNull ?: "server_tool_call"
    val protocolFields = setOf("type", "id", "status", "result", "output")
    val input = get("action") ?: JsonObject(filterKeys { it !in protocolFields })
        .takeUnless { it.isEmpty() }
    return UIMessagePart.ServerTool(
        toolCallId = get("id")?.jsonPrimitive?.contentOrNull ?: "",
        toolName = type.removeSuffix("_call"),
        input = input,
        output = get("output") ?: get("result"),
        status = get("status")?.jsonPrimitive?.contentOrNull.toServerToolStatus(),
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.OPENAI_RESPONSES,
            call = this,
        ).toMetadata(),
    )
}

internal fun String?.toServerToolStatus(): ServerToolStatus = when (this) {
    "completed" -> ServerToolStatus.COMPLETED
    "failed", "incomplete", "cancelled" -> ServerToolStatus.FAILED
    else -> ServerToolStatus.IN_PROGRESS
}

private fun ServerToolStatus.toOpenAIStatus(): String = when (this) {
    ServerToolStatus.IN_PROGRESS -> "in_progress"
    ServerToolStatus.COMPLETED -> "completed"
    ServerToolStatus.FAILED -> "failed"
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
    // 不为 null 时 system 提示以这个角色的消息放在 input 开头，不用 instructions 字段
    val systemMessageRole: String? = null,
    // 是否接受 temperature、top_p 和 max_output_tokens
    val supportsSamplingParams: Boolean = true,
    // 非推理模型也请求加密推理内容：不保存会话时，自带推理的模型要靠它接上多轮推理
    val alwaysIncludeEncryptedContent: Boolean = false,
    // 推理档位对应的 reasoning.effort（参数依次是档位、模型 id），返回 null 时不带
    val reasoningEffort: (
        ReasoningLevel,
        String,
    ) -> String? = { level, _ -> level.effort.takeIf { level != ReasoningLevel.AUTO } },
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}
