package me.rerere.ai.provider.providers.google

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KEY_RETRYABLE_CODES
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.withKeyRetry
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.sanitizeForGeminiSchema
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"

/**
 * v274：generative-language API 在 safetySettings 上接受的全部类别（照搬自 ExTV rikkahub-agent）。
 */
val GOOGLE_SAFETY_CATEGORIES = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
    "HARM_CATEGORY_CIVIC_INTEGRITY",
)

/**
 * v274：Cloud Code Assist（Gemini 官方账号 OAuth 通道）接受的类别 —— 与上面相同但去掉
 * civic integrity：多发那一个会让整个请求 400，且报错文本还把它列为「允许」，
 * 极具误导性（照搬自 ExTV rikkahub-agent 原注释）。
 */
val CODE_ASSIST_SAFETY_CATEGORIES =
    GOOGLE_SAFETY_CATEGORIES - "HARM_CATEGORY_CIVIC_INTEGRITY"

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    /**
     * v255：把 API key 贴到请求上（**非挂起**）。
     *
     * vertexAI 走 url 的 key 查询参数，其余走 x-goog-api-key 头。
     * 单独抽出来的原因：流式重试发生在 onFailure 里，那是个普通回调、不能挂起，
     * 而 [transformRequest] 因为要为 serviceAccount 取 access token 必须是 suspend。
     */
    private fun applyApiKey(
        providerSetting: ProviderSetting.Google,
        request: Request,
        key: String,
    ): Request = if (providerSetting.vertexAI) {
        request.newBuilder()
            .url(request.url.newBuilder().addQueryParameter("key", key).build())
            .build()
    } else {
        request.newBuilder()
            .addHeader("x-goog-api-key", key)
            .build()
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request,
        // v255：重试时指定要用的 key；不传则照旧由轮询器选
        apiKeyOverride: String? = null,
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = apiKeyOverride
                ?: keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            applyApiKey(providerSetting = providerSetting, request = request, key = key)
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                Log.d(TAG, "listModels: $body")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]!!.jsonArray
                            .map { method -> method.jsonPrimitive.content }
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    Model(
                        modelId = modelObject["name"]!!.jsonPrimitive.content.substringAfter("/"),
                        displayName = modelObject["displayName"]!!.jsonPrimitive.content,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                    )
                }
            } else {
                emptyList()
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        // v255：密钥类错误（余额不足/鉴权失败/限速）自动换下一个 key 重试一次
        keyRoulette.withKeyRetry(
            providerSetting.apiKey,
            providerSetting.id.toString(),
        ) { key ->
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(params.customHeaders.toHeaders())
                    .post(
                        json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                    )
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build(),
                apiKeyOverride = key,
            )

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

            val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("No candidates in response")
            TextGenerationResult(
                id = Uuid.random().toString(),
                model = params.model.modelId,
                message = parseMessage(candidate),
                finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
                usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject),
            )
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        // v255：不带认证的原始请求 —— 首轮由 transformRequest 贴认证（serviceAccount 要取 token），
        // 换 key 重试时在 onFailure 里用非挂起的 applyApiKey 直接贴新 key。
        val baseRequest = Request.Builder()
            .url(url)
            .headers(params.customHeaders.toHeaders())
            .post(
                json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        // v255：serviceAccount 模式用的是临时 access token，没有 apiKey 可换 → 不参与 key 轮换
        val keyRotatable = !(providerSetting.vertexAI && providerSetting.useServiceAccount)
        val providerId = providerSetting.id.toString()
        var currentKey = if (keyRotatable) {
            keyRoulette.next(providerSetting.apiKey, providerId)
        } else {
            ""
        }
        var keyRetried = false

        val request = transformRequest(
            providerSetting = providerSetting,
            request = baseRequest,
            apiKeyOverride = currentKey.takeIf { keyRotatable },
        )

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val responseId = Uuid.random().toString()
        val decoder = GoogleStreamDecoder(responseId, params.model.modelId)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        // v255：当前生效的事件源。必须在 listener 之前声明 —— 换 key 重试时 onFailure 会重新
        // 赋值，awaitClose 取消的永远是最新那一个（否则取消的是已经失败的旧连接）。
        var activeEventSource: EventSource? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.i(TAG, "onEvent: $data")

                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to parse stream event: $data", e)
                    close(e)
                }
            }

            override fun onFailure(
                source: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                // v255：密钥类错误（401/402/403/429）→ 标记失败并换下一个 key 重试一次
                val code = response?.code
                if (keyRotatable && !keyRetried && code != null && code in KEY_RETRYABLE_CODES) {
                    keyRetried = true
                    keyRoulette.markFailed(providerId, currentKey)
                    currentKey = keyRoulette.next(providerSetting.apiKey, providerId)
                    Log.e(TAG, "onFailure: key 错误(code=$code)，已切换 key 重试")
                    activeEventSource = EventSources.createFactory(client)
                        .newEventSource(
                            applyApiKey(
                                providerSetting = providerSetting,
                                request = baseRequest,
                                key = currentKey,
                            ),
                            this,
                        )
                    return
                }

                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            println(bodyElement)
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                println("[onClosed] 连接已关闭")
                sendChunks(decoder.onClosed())
                close()
            }
        }

        activeEventSource = EventSources.createFactory(client)
                .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource")
            activeEventSource?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /**
     * v274：把一条解码后的 streamGenerateContent 载荷映射为 [MessageChunk]，无候选内容时返回 null。
     *
     * 与 [buildCompletionRequestBody] 一起公开，供 Cloud Code Assist 传输层（app 模块的
     * GeminiProvider）复用它包裹的 Gemini 线格式：那个 API 在 `request` 键下收同样的请求体、
     * 在 `response` 键下回同样的候选内容，再抄一份解码逻辑只会让两个传输实现各自漂移。
     * （照搬自 ExTV rikkahub-agent）
     */
    fun parseStreamCandidates(jsonData: JsonObject, model: Model): MessageChunk? {
        val candidates = jsonData["candidates"]?.jsonArray ?: return null
        if (candidates.isEmpty()) return null
        return MessageChunk(
            id = Uuid.random().toString(),
            model = model.modelId,
            choices = candidates.mapIndexed { index, candidate ->
                val candidateObj = candidate.jsonObject
                val content = candidateObj["content"]?.jsonObject
                val groundingMetadata = candidateObj["groundingMetadata"]?.jsonObject
                val finishReason = candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull

                val message = content?.let {
                    parseMessage(buildJsonObject {
                        put("role", JsonPrimitive("model"))
                        put("content", it)
                        groundingMetadata?.let { grounding ->
                            put("groundingMetadata", grounding)
                        }
                    })
                }

                UIMessageChoice(
                    index = index,
                    delta = message,
                    message = null,
                    finishReason = finishReason
                )
            },
            usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
        )
    }

    /**
     * v274：由 private 改为 public，并新增 safetyCategories 参数 —— Gemini 官方账号通道
     * （Cloud Code Assist）要传 [CODE_ASSIST_SAFETY_CATEGORIES]（少一个类别，见其注释）。
     * 默认值等于原有硬编码的 5 类，现有 API key 通道行为零变化。
     * @JvmOverloads：官方 2.4.16 的 GoogleToolCombinationTest 以两参反射调用本函数，
     * 加默认参数会改变 JVM 方法签名，需生成兼容重载保持其可运行。
     */
    @JvmOverloads
    fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        safetyCategories: List<String> = GOOGLE_SAFETY_CATEGORIES
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                // 不发送 thinkingLevel：部分模型/中转站不支持 "minimal"，
                                // 硬编码会导致 400（"Thinking level MINIMAL is not supported"）。
                                // 交给服务端默认级别，保证模型可用。
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages)
        )

        // Client function tools and model built-in tools share the same array.
        val useFunctionTools =
            params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val useBuiltInTools = params.model.tools.any {
            it == BuiltInTools.Search || it == BuiltInTools.UrlContext
        }
        if (useFunctionTools || useBuiltInTools) {
            putJsonArray("tools") {
                if (useFunctionTools) {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    // v274：黑名单剔除改为白名单清洗（照搬 ExTV rikkahub-agent
                                    // 的 sanitizeForGeminiSchema）：enum/format 同样排除（行为对等），
                                    // 额外解析 $ref、折叠 oneOf/allOf、归一化 type 数组 ——
                                    // 这些形状原先原样发给 Google 会直接 400。
                                    put(
                                        key = "parameters",
                                        element = json.encodeToJsonElement(tool.parameters())
                                            .sanitizeForGeminiSchema()
                                    )
                                })
                            }
                        }
                    })
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
        if (useFunctionTools && useBuiltInTools) {
            put("toolConfig", buildJsonObject {
                put("includeServerSideToolInvocations", true)
            })
        }

        // Safety Settings
        // v274：改为按 safetyCategories 参数遍历生成（Cloud Code Assist 通道传少一个类别的
        // CODE_ASSIST_SAFETY_CATEGORIES；默认值与原先硬编码的 5 类完全一致）。
        putJsonArray("safetySettings") {
            safetyCategories.forEach { category ->
                add(buildJsonObject {
                    put("category", category)
                    put("threshold", "OFF")
                })
            }
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = parseMessageParts(content["parts"]?.jsonArray)

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessageParts(parts: JsonArray?): List<UIMessagePart> = buildList {
        parts.orEmpty().forEachIndexed { index, element ->
            val part = parseMessagePart(element.jsonObject, index)
            if (part !is UIMessagePart.ServerTool) {
                add(part)
                return@forEachIndexed
            }

            val existingIndex = indexOfFirst {
                it is UIMessagePart.ServerTool && it.toolCallId == part.toolCallId
            }
            if (existingIndex < 0) {
                add(part)
            } else {
                val existing = get(existingIndex) as UIMessagePart.ServerTool
                set(
                    existingIndex, existing.copy(
                        toolName = part.toolName.ifBlank { existing.toolName },
                        input = part.input ?: existing.input,
                        output = part.output ?: existing.output,
                        status = if (part.isFinished) part.status else existing.status,
                        metadata = mergeGoogleMetadata(existing.metadata, part.metadata),
                    )
                )
            }
        }
    }

    private fun parseMessagePart(jsonObject: JsonObject, index: Int): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = jsonObject.toGoogleThoughtMetadata(),
                ) else UIMessagePart.Text(
                    text = text,
                    metadata = jsonObject.toGoogleThoughtMetadata(),
                )
            }

            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"]!!.jsonObject
                val toolCallId = functionCall["id"]?.jsonPrimitive?.contentOrNull
                    ?: Uuid.random().toString()
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = functionCall["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(functionCall["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("toolCall") -> {
                val toolCall = jsonObject["toolCall"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolCall["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = toolCall["args"],
                    status = ServerToolStatus.IN_PROGRESS,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        call = jsonObject,
                        callIndex = index,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("toolResponse") -> {
                val toolResponse = jsonObject["toolResponse"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolResponse["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolResponse["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    output = toolResponse["response"],
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        result = jsonObject,
                        resultIndex = index,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = "data:$mime;base64,$data",
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature)
                        .takeIf { thoughtSignature != null }
                        ?.toMetadata()
                )
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message)
                    } else {
                        addUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()
        // v274：把前一段 Reasoning part 的 thoughtSignature 转发给下一个不带签名的 Tool part
        //（照搬 ExTV rikkahub-agent）。Gemini 把签名放在思考片段上，但续跑/工具循环的请求里
        // 下一个 functionCall 若不带签名，Gemini 会报
        // "Function call is missing a thought_signature in functionCall parts"。
        // 签名沿消息 parts 列表传递，跨 chunk 流式（思考在块 N、functionCall 在块 N+1）也覆盖。
        var carriedSig: String? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 跟踪最近的思考签名，留给下一个工具组
                    group.parts.forEach { part ->
                        if (part is UIMessagePart.Reasoning) {
                            part.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
                                ?.takeIf { it.isNotBlank() }
                                ?.let { carriedSig = it }
                        }
                    }
                    group.parts.flatMap { it.toGoogleParts() }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲（缺签名的用 carriedSig 补上）
                    group.tools.forEach { tool ->
                        val effective = if (
                            tool.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature.isNullOrBlank()
                            && carriedSig != null
                        ) {
                            tool.copy(
                                metadata = GoogleThoughtMetadata(thoughtSignature = carriedSig)
                                    .toMetadata()
                            )
                        } else tool
                        partsBuffer.add(effective.toFunctionCallPart())
                    }
                    carriedSig = null // 已被本工具组消费

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.flatMap { it.toGoogleParts() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGoogleParts(): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> toGoogleServerToolParts()
        else -> listOfNotNull(toGooglePart())
    }

    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> {
            val thoughtSignature = metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            buildJsonObject {
                put("text", text)
                thoughtSignature?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Reasoning -> {
            val thoughtSignature = metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            buildJsonObject {
                put("text", reasoning)
                put("thought", true)
                thoughtSignature?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Image -> {
            encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
            put("id", toolCallId)
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)
                put("id", toolCallId)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart() }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun UIMessagePart.ServerTool.toGoogleServerToolParts(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.GOOGLE_GENERATE_CONTENT) {
            return emptyList()
        }

        return buildList {
            metadata?.call?.let(::add)
            metadata?.result?.let(::add)
        }
    }

    private fun JsonObject.toGoogleThoughtMetadata() =
        this["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let {
            GoogleThoughtMetadata(thoughtSignature = it).toMetadata()
        }

    private fun mergeGoogleMetadata(first: JsonObject?, second: JsonObject?): JsonObject? = when {
        first == null -> second
        second == null -> first
        else -> JsonObject(first + second)
    }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens,
            // v297：思考 token 单列。Gemini 的 candidatesTokenCount 不含思考、totalTokenCount 含，
            // 上面 completion 仍照旧把思考并入（改了会动到上下文与压缩判定），这里只做展示分解。
            reasoningTokens = thoughtTokens,
        )
    }
}
