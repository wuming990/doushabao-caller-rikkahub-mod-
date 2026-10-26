package me.rerere.ai.provider.providers.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.core.sum
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.runQuotaQuery
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KEY_RETRYABLE_CODES
import me.rerere.ai.util.KeyRoulette
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
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.sse.newEventSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import kotlin.time.Clock

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_PAUSE_TURN = "pause_turn"
private const val MAX_PAUSE_TURN_CONTINUATIONS = 5

internal suspend fun generateClaudeWithPauseTurn(
    messages: List<UIMessage>,
    model: Model,
    maxContinuations: Int = MAX_PAUSE_TURN_CONTINUATIONS,
    request: suspend (List<UIMessage>) -> TextGenerationResult,
): TextGenerationResult {
    require(maxContinuations >= 0) { "maxContinuations must be non-negative" }

    var requestMessages = messages
    var combinedMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    var combinedUsage: TokenUsage? = null
    var nextServerToolBlockIndex = 0

    repeat(maxContinuations + 1) { continuationCount ->
        val rawResult = request(requestMessages)
        val result = rawResult.copy(
            message = rawResult.message.rebaseClaudeServerToolIndexes(nextServerToolBlockIndex),
        )
        result.message.maxClaudeServerToolIndex()?.let { maxIndex ->
            nextServerToolBlockIndex = maxOf(nextServerToolBlockIndex, maxIndex + 1)
        }
        combinedMessage = listOf(combinedMessage)
            .handleTextGenerationResult(result, model)
            .last()
        combinedUsage = combinedUsage.sum(result.usage)

        if (result.finishReason != CLAUDE_PAUSE_TURN || continuationCount == maxContinuations) {
            return result.copy(
                message = combinedMessage.copy(usage = combinedUsage),
                usage = combinedUsage,
            )
        }

        // pause_turn 要求原样回放当前 assistant 响应，不能额外插入 "Continue" user 消息。
        requestMessages = requestMessages.handleTextGenerationResult(result, model)
    }

    error("unreachable")
}

internal fun streamClaudeWithPauseTurn(
    messages: List<UIMessage>,
    model: Model,
    maxContinuations: Int = MAX_PAUSE_TURN_CONTINUATIONS,
    request: (List<UIMessage>) -> Flow<StreamChunk>,
): Flow<StreamChunk> = flow {
    require(maxContinuations >= 0) { "maxContinuations must be non-negative" }

    var requestMessages = messages
    var completedUsage: TokenUsage? = null
    var nextServerToolBlockIndex = 0

    repeat(maxContinuations + 1) { continuationCount ->
        val handler = StreamChunkHandler(model)
        var responseMessages = requestMessages
        var passUsage: TokenUsage? = null
        var finish: StreamChunk.Finish? = null
        val responseIndexOffset = nextServerToolBlockIndex

        request(requestMessages).collect { rawChunk ->
            val chunk = rawChunk.rebaseClaudeServerToolIndexes(responseIndexOffset)
            chunk.maxClaudeServerToolIndex()?.let { maxIndex ->
                nextServerToolBlockIndex = maxOf(nextServerToolBlockIndex, maxIndex + 1)
            }
            responseMessages = handler.handle(responseMessages, chunk)
            when (chunk) {
                is StreamChunk.Usage -> {
                    passUsage = passUsage.merge(chunk.usage)
                    completedUsage.sum(passUsage)?.let { emit(StreamChunk.Usage(it)) }
                }

                is StreamChunk.Finish -> {
                    finish = chunk
                    if (chunk.finishReason != CLAUDE_PAUSE_TURN ||
                        continuationCount == maxContinuations
                    ) {
                        emit(chunk)
                    }
                }

                else -> emit(chunk)
            }
        }

        if (finish?.finishReason != CLAUDE_PAUSE_TURN || continuationCount == maxContinuations) {
            return@flow
        }

        completedUsage = completedUsage.sum(passUsage)
        requestMessages = responseMessages
    }
}

/**
 * Claude content block index 只在单次响应内有效。pause_turn 会把多次响应合并为一个逻辑
 * assistant turn，因此在合并前将后续响应的 server tool index 偏移到同一序号空间。
 */
private fun UIMessage.rebaseClaudeServerToolIndexes(offset: Int): UIMessage = copy(
    parts = parts.map { part ->
        if (part !is UIMessagePart.ServerTool) return@map part
        part.copy(metadata = part.metadata.rebaseClaudeServerToolIndexes(offset))
    },
)

private fun StreamChunk.rebaseClaudeServerToolIndexes(offset: Int): StreamChunk = when (this) {
    is StreamChunk.ServerToolStart -> copy(metadata = metadata.rebaseClaudeServerToolIndexes(offset))
    is StreamChunk.ServerToolEnd -> copy(metadata = metadata.rebaseClaudeServerToolIndexes(offset))
    else -> this
}

private fun JsonObject?.rebaseClaudeServerToolIndexes(offset: Int): JsonObject? {
    if (this == null) return null
    val metadata = runCatching {
        json.decodeFromJsonElement<ServerToolMetadata>(this)
    }.getOrNull()?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
        ?: return this
    if (metadata.callIndex == null && metadata.resultIndex == null) return this
    return JsonObject(this + metadata.rebaseIndexes(offset).toMetadata())
}

private fun ServerToolMetadata.rebaseIndexes(offset: Int): ServerToolMetadata = copy(
    callIndex = callIndex?.plus(offset),
    resultIndex = resultIndex?.plus(offset),
)

private fun UIMessage.maxClaudeServerToolIndex(): Int? = parts
    .filterIsInstance<UIMessagePart.ServerTool>()
    .mapNotNull { part ->
        part.metadataAs<ServerToolMetadata>()
            ?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            ?.maxIndex()
    }
    .maxOrNull()

private fun StreamChunk.maxClaudeServerToolIndex(): Int? {
    val metadata = when (this) {
        is StreamChunk.ServerToolStart -> metadata
        is StreamChunk.ServerToolEnd -> metadata
        else -> null
    } ?: return null
    return runCatching {
        json.decodeFromJsonElement<ServerToolMetadata>(metadata)
    }.getOrNull()?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }?.maxIndex()
}

private fun ServerToolMetadata.maxIndex(): Int? = listOfNotNull(callIndex, resultIndex).maxOrNull()

private fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
        // v297：pause_turn 跨请求累加时也要带上思考分解，否则合并后思考那一行会凭空消失
        reasoningTokens = reasoningTokens + other.reasoningTokens,
    )
}

class ClaudeProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    /**
     * v1012：思考参数降档记忆（同一个供应商 + 同一个型号，撞过 400 就记住要退到哪一档）。
     */
    private val thinkingCompat =
        if (context != null) ClaudeCompatMemory.of(context) else ClaudeCompatMemory.default()

    override suspend fun getQuota(providerSetting: ProviderSetting.Claude): ProviderQuota? =
        client.runQuotaQuery(providerSetting) {
            keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        }

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .headers(providerSetting.mergeCustomHeaders())
                .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        generateClaudeWithPauseTurn(messages, params.model) { requestMessages ->
            generateTextOnce(providerSetting, requestMessages, params)
        }
    }

    /**
     * v1012：降档记忆的键。带上 modelId —— 同一个中转站常常同时挂新旧两代 Claude，
     * 一个型号要降档不代表另一个也要。
     */
    /**
     * 从记忆里那套字段组合起步。
     *
     * 存进来的组合在当前阶梯里找不到时（用户改了设置、或该组合本来就没带那些字段）
     * 一律回到第 0 档重学 —— 宁可多试一次，也不要按旧含义猜一个位置。
     */
    private fun variantStartIndex(
        variants: List<ClaudeRequestFlags>,
        stored: ClaudeRequestFlags,
    ): Int = variants.indexOf(stored).let { if (it >= 0) it else 0 }

    private fun claudeCompatKey(
        providerSetting: ProviderSetting.Claude,
        params: TextGenerationParams,
    ): String = "${providerSetting.id}|${params.model.modelId}"

    /**
     * 这条异常是不是**我们自己**把一帧 SSE 解坏了，而不是上游嫌我们参数不合法。
     *
     * 必须按类型排除而不是按文案：kotlinx 的解码异常原文就是
     * "Unexpected JSON token at offset …"，天然命中判据里的 "unexpected"，而且它还会把
     * 整段原始 data 当 `Source:` 片段拼进 message —— 万一那一帧恰好是残缺的错误帧，
     * 文案里真会有 "request parameters" 之类字样，光靠措辞挡不住。
     * 误判的代价不是零：阶梯会重烧最多 7 次真实请求（产出前被拒如今也计费），
     * 最后一张卡还指着参数说话，把人往错方向带。
     *
     * 真错误帧走的是 ErrorParser.parseErrorDetail → HttpException，不受这里影响。
     */
    private fun isOurOwnDecodeFailure(e: Throwable): Boolean =
        e is kotlinx.serialization.SerializationException ||
            (e is IllegalArgumentException && e.message?.contains("is not a JsonObject") == true)

    /**
     * 这份请求体里实际带着哪些可选字段 —— 报错卡「本次带了…」那一项的唯一来源。
     *
     * v1013 把它从流式闭包里的局部函数搬到类级：非流式路径以前硬填 emptyList()，
     * 卡片少一项关键读数；更要紧的是缺了 "max_tokens=…" 那一项之后，
     * ReasoningFallbackProvider 的「这看着像思考档位被拒」判定会失去它的无关词否决，
     * 有可能把整条请求按另一套思考档位再放一遍。
     */
    fun claudeOptionalFieldsIn(body: JsonObject): List<String> = buildList {
        val thinking = body["thinking"] as? JsonObject
        when (thinking?.get("type")?.jsonPrimitive?.contentOrNull) {
            "disabled" -> add("思考=关闭")
            "enabled" -> add("思考=老式预算")
            "adaptive" -> add(
                if (thinking?.get("display")?.jsonPrimitive?.contentOrNull == "summarized") {
                    "思考=索要摘要"
                } else {
                    "思考=新式不索要摘要"
                },
            )
        }
        // 只在这一处加，别在上面的 else 分支里重复一遍（会把同一项列两次）
        if (body["output_config"] != null) add("思考强度")
        if (body["cache_control"] != null) add("提示词缓存")
        if (body["top_p"] != null) add("top_p")
        if (body["temperature"] != null) add("temperature")
        if (body["tools"].toString().contains("web_search_")) add("内置联网工具")
        // 这里以前写的是 "${'$'}{it.jsonPrimitive.content}"，Kotlin 会把 ${'$'} 当成一个
        // 真美元符号、后面的 {…} 当普通文字 —— 卡片上印的是字面量，值根本没插进去。
        // 审查位用字节级证据确认过源码里确实是那个写法。读数说谎 = 本轮验收判据失败。
        body["max_tokens"]?.let { add("max_tokens=${it.jsonPrimitive.content}") }
    }

    /**
     * v1012：非流式路径的剥档外壳。
     *
     * 与流式同一口径：请求内的逐档试探只改局部变量，**只有真的拿到内容的组合**才落盘。
     */
    private suspend fun generateTextOnce(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val compatKey = claudeCompatKey(providerSetting, params)
        // 先按「完整形态」造一份，再从这份里看哪些字段真的在，据此决定还有几档值得试。
        // 不能反过来造 7 份去重：请求体含全部历史消息与 base64 图片，那是自己找罪受。
        var variants = listOf(ClaudeRequestFlags())
        var index = 0
        var requestBody = buildMessageRequest(
            providerSetting, messages, params, flags = ClaudeRequestFlags(),
        )
        fun adoptStart() {
            // 这里必须真的算一次 variants。上一版删 rebuildVariants 时把这个赋值一起带走了，
            // 后果是**非流式路径永远只有一档可试**（lastIndex=0，一次都剥不动）——
            // 编译不报错、测试也照绿，属于最危险的那类"静默少做"。
            variants = claudeVariantsFor(
                requestBody,
                providerSetting.thinkingCompat,
                supportsExtended = claudeThinkingSupport(params.model.modelId).extended,
            )
            index = variantStartIndex(variants, thinkingCompat.flagsFor(compatKey))
            // 本会话内已经撞到底的话，从那里起步 —— 否则生成层按用户滑条重发 99 趟、
            // 每趟又从第 0 档重撞，最坏 99 × 8 ≈ 800 次请求。次数仍然由用户的滑条决定，
            // 这里只是不让他花的每一次都重复前一次的白撞。
            val floor = thinkingCompat.exhaustedFloor(compatKey)
            if (index < floor) index = floor.coerceAtMost(variants.lastIndex)
            if (index != 0) {
                requestBody = buildMessageRequest(
                    providerSetting, messages, params, flags = variants[index],
                )
            }
        }
        adoptStart()
        while (true) {
            try {
                val result = generateTextAtTier(providerSetting, messages, params, variants[index])
                if (result.message.parts.isNotEmpty()) {
                    thinkingCompat.remember(compatKey, variants[index])
                }
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val isRefusal = e is ClaudeRefusalException
                val refusalCategory = (e as? ClaudeRefusalException)?.category
                // 自家把响应体解坏了（网关回非 JSON body）不是「上游嫌参数」。以前非流式这条
                // 漏了这个判据，一条坏帧就会逐档重烧最多 7 次真实请求（二审 P1）。
                val looksRejected = !isOurOwnDecodeFailure(e) && isClaudeParameterRejection(
                    if (isRefusal) null else claudeStatusCodeFromMessage(e.message),
                    e.message,
                )
                val worth = !isRefusal || claudeRefusalWorthStripping(refusalCategory)
                // 三审 P1：以前这条闸门只看「分类值不值得剥 + 还有没有下一档」，于是 429 / 500 /
                // 断网 / 自家解坏响应体**全都会逐档重发一遍阶梯**（looksRejected 算出来了却没用上）。
                // 拒答按分类判，其它错误必须真的像「参数被拒」才值得剥字段。
                val shouldDegrade = claudeShouldDegradeForError(isRefusal, worth, looksRejected)
                if (!shouldDegrade || index >= variants.lastIndex) {
                    // 剥到底还是被拒 → 换成专用异常交上去。裸 Exception 会被生成层
                    // 当成临时故障按用户的滑条一路重发，而每一趟又从第 0 档重撞。
                    if (isRefusal) {
                        // 三审 P0：读数在这一层补 —— 只有这里能拿到当前档与当前请求体。
                        val refusal = e as ClaudeRefusalException
                        throw ClaudeRefusalException(
                            category = refusal.category,
                            detail = refusal.detail,
                            diagnostics = ClaudeRejectDiagnostics(
                                httpStatus = null,
                                variantsTried = index + 1,
                                variantTotal = variants.size,
                                lastVariantLabel = variants[index].label(),
                                sentOptionalFields = claudeOptionalFieldsIn(requestBody),
                                upstreamExcerpt = null,
                            ),
                            keyPoolSize = keyRoulette.keyCount(providerSetting.apiKey),
                        )
                    }
                    if (!isRefusal && looksRejected && index >= variants.lastIndex) {
                        thinkingCompat.rememberExhausted(compatKey, index)
                        val diag = ClaudeRejectDiagnostics(
                            httpStatus = claudeStatusCodeFromMessage(e.message),
                            variantsTried = index + 1,
                            variantTotal = variants.size,
                            lastVariantLabel = variants[index].label(),
                            // 与流式同一口径；以前这里硬填 emptyList()、并把未脱敏的
                            // e.message 整段塞进卡片文案，两条都是审查位判 fail 的点。
                            sentOptionalFields = claudeOptionalFieldsIn(requestBody),
                            upstreamExcerpt = sanitizeClaudeUpstreamExcerpt(
                                requestBody, e.message?.take(160),
                            ),
                        )
                        throw ClaudeDegradeExhaustedException(diag, diag.upstreamExcerpt)
                    }
                    throw e
                }
                index += 1
                requestBody = buildMessageRequest(
                    providerSetting, messages, params, flags = variants[index],
                )
                Log.w(
                    TAG,
                    "Claude 拒收本档请求，改试第 $index/${variants.lastIndex} 档" +
                        "（${variants[index].label()}）；上游原文：${e.message?.take(300)}",
                )
            }
        }
    }

    private suspend fun generateTextAtTier(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        flags: ClaudeRequestFlags,
    ): TextGenerationResult {
        val requestBody = buildMessageRequest(providerSetting, messages, params, flags = flags)

        // v255：密钥类错误（余额不足/鉴权失败/限速）自动换下一个 key 重试一次
        return keyRoulette.withKeyRetry(
            providerSetting.apiKey,
            providerSetting.id.toString(),
        ) { key ->
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/messages")
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .configureReferHeaders(providerSetting.baseUrl)
                .build()

            Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

            // 从 JsonObject 中提取必要的信息
            val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
            val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            // v1012：非流式路径的 refusal 口径与流式一致 —— 变成可重试的类型化异常，
            // 而不是「成功返回一条空消息」让上层静默定格。
            if (stopReason == CLAUDE_STOP_REFUSAL && content.isEmpty()) {
                // 同上：显式 null 要用 as? 取，?.jsonObject 会对 JsonNull 抛异常
                val details = bodyJson["stop_details"] as? JsonObject
                // 三审 P0：这一层**不能**带 diagnostics —— index / variants 是 generateTextOnce
                // 的局部变量，在本函数里根本不存在（上一版我把它写在这儿，编译必挂）。
                // 本层只负责「带上脱敏过的现场」，读数由外层 catch 补齐，那里才知道真的走到第几档。
                throw ClaudeRefusalException(
                    category = details?.get("category")?.jsonPrimitive?.contentOrNull,
                    detail = sanitizeClaudeUpstreamExcerpt(
                        requestBody, claudeRefusalDetail(details),
                    ),
                )
            }
            val usage = parseTokenUsage(bodyJson)

            TextGenerationResult(
                id = id,
                model = model,
                message = parseMessage(content),
                finishReason = stopReason,
                usage = usage,
            )
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = streamClaudeWithPauseTurn(messages, params.model) { requestMessages ->
        streamTextOnce(providerSetting, requestMessages, params)
    }

    private fun streamTextOnce(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        // v1012：逐档试探。variants 是按**真实请求体去重**后的阶梯（本次没带缓存、
        // 没设 top_p 时，那几档算出来的请求体逐字节相同，留着就是白撞）。
        // 试探途中只改内存里的 index；**只有真的吐出内容的那一档才落盘**。
        val compatKey = claudeCompatKey(providerSetting, params)
        // 先造一份完整的，再从里面探测哪些字段真的在 —— 见 claudeVariantsFor 的说明。
        var variants = listOf(ClaudeRequestFlags())
        var index = 0
        var requestBody = buildMessageRequest(
            providerSetting, messages, params, stream = true, flags = ClaudeRequestFlags(),
        )
        fun adoptStart() {
            variants = claudeVariantsFor(
                requestBody,
                providerSetting.thinkingCompat,
                supportsExtended = claudeThinkingSupport(params.model.modelId).extended,
            )
            index = variantStartIndex(variants, thinkingCompat.flagsFor(compatKey))
            val floor = thinkingCompat.exhaustedFloor(compatKey)
            if (index < floor) index = floor.coerceAtMost(variants.lastIndex)
            if (index != 0) {
                requestBody = buildMessageRequest(
                    providerSetting, messages, params, stream = true, flags = variants[index],
                )
            }
        }
        adoptStart()
        // 诊断读数用：实际试过几档
        // 从实际起步的那一档算起：记忆里学到过、或本会话撞过底时起点不是 0，
        // 写死 1 会让卡片说「已试 1/8 档」，把没试过的档位冒充成试过的起点。
        var variantsTried = index + 1

        // v255：当前使用的 key；遇到密钥类错误时换 key 重试。
        // v290：从「同一流只换一次」放宽为「把整个密钥池轮完为止」（用户拍板：10 把轮 10 把）；
        // emittedAny 记录本流是否已对用户吐出过内容 —— 吐过之后绝不换 key 重发（重发必然
        // 前半段重复，v285 定下的规矩），那种情况交给上层续跑机制处理。
        val providerId = providerSetting.id.toString()
        val keyPoolSize = keyRoulette.keyCount(providerSetting.apiKey)
        var currentKey = keyRoulette.next(providerSetting.apiKey, providerId)
        var keySwitchCount = 0
        var emittedAny = false

        fun buildRequest(key: String): Request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        requestBody["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }

        var decoder = ClaudeStreamDecoder()

        // v1011（审查位 P1-2）：放宽的代价必须看得见。旧写法是「对不上号就抛异常打死整条流」，
        // 响亮但会连累已有正文；新写法是「跳过这一帧」，温和但**有可能变成安静地丢**。
        // 所以解码器维护跳帧计数，由这条流的使用方负责说出口。
        // （计数放在解码器里、日志放在这里：ai 模块的单测没开 returnDefaultValues，
        //  解码器里直接调 android.util.Log 会让单测崩。）
        //
        // v1011 二审修正（审查位 P2 抓到）：**只挂在 onClosed 上等于永远不打** ——
        // 正常收尾是 message_stop 让解码器返回 completed、我们自己调 close()，
        // 那条路不会回来回调 onClosed。所以两个收尾点都要报，并用 once 标记防止重复刷屏。
        var skippedFramesReported = false

        /**
         * v1012（两位独立审查位都据此判 fail 的那条 P1）：**每一次重发都必须换一个新解码器**。
         *
         * [ClaudeStreamDecoder] 收到 message_stop 就把自己置成 finished，之后每帧都只返回
         * 「已完成 + 空 chunks」（见其 accept 开头那行）。而换密钥 / 降档 / refusal 重发都是
         * 在**同一条 callbackFlow 里再开一路连接**、复用一个 listener 的 —— 旧写法沿用同一个
         * decoder，于是重发出去的第二路连接一个字都读不出来：
         * · 换密钥白花一次真实计费请求（v255/v290 起就潜伏，只是以前只在 message_stop 之前
         *   的 HTTP 失败上触发，那时 decoder 还没被写过，所以没暴露）；
         * · 我这版 refusal 是在 message_stop **之后**才换密钥，正好撞进这个洞 —— 换密钥
         *   完全无效，而且残留的 finishReason=refusal 还会把新流一帧帧误判成又被拒。
         *
         * 顺带把跳帧日志的 once 标记复位：那是「每条连接」的诊断计数，不该跨连接累积。
         */
        fun resetDecoderForReconnect() {
            decoder = ClaudeStreamDecoder()
            skippedFramesReported = false
        }

        fun reportSkippedFrames(where: String) {
            if (skippedFramesReported) return
            val skipped = decoder.skippedFrameCount
            if (skipped > 0) {
                skippedFramesReported = true
                Log.w(
                    TAG,
                    "$where: 本条 Claude 流有 $skipped 帧没被识别 / 被跳过，" +
                        "最后一条原因：${decoder.lastSkipReason}" +
                        "（网关报文形态与官方不一致时才会走到这里，内容可能有缺失）",
                )
            }
        }

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            // v290：只要对用户发出去过内容就标记 —— 换 key 重发的双保险之一（见 trySwitchKey）
            // v1012：判定从 chunks.any() 收紧为「有可见内容」。Finish / Usage 只是协议收尾帧
            // 与计量帧，屏幕上不会出现任何东西 —— 旧写法下「一个字没吐但收了尾」的流
            // （refusal、上游空回）会被误判成「已经吐过内容」，于是永远不许换密钥：
            // 用户明明有多把 key，refusal 时却一次都不换。
            if (chunks.any { it.isClaudeVisibleContent() }) {
                // v1012：这一档真的把内容吐出来了 —— 只有此刻才值得写进配置。
                // 试探途中的每一档都不算数（否则一次被误判的 400 就会永久改掉用户的思考设置）。
                if (!emittedAny) thinkingCompat.remember(compatKey, variants[index])
                emittedAny = true
            }
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        // v255：当前生效的事件源。必须在 listener 之前声明 —— 换 key 重试时 onFailure 会重新
        // 赋值，awaitClose 取消的永远是最新那一个（否则取消的是已经失败的旧连接）。
        var activeEventSource: EventSource? = null

        /**
         * v1012：**已经被取代的旧连接**。旧连接被 cancel 之后仍可能迟到回调 onClosed /
         * onFailure，那些回调会把刚建立的新流 close 掉（表现为「换了 key 之后一个字都没有」）。
         *
         * 为什么用「已废弃清单」而不是「与当前连接比引用」：newEventSource 内部已经把请求
         * enqueue 出去了，首路连接的回调有可能早于 `activeEventSource = ...` 这次赋值落地 ——
         * 那种写法会把正常的首包整批丢掉。清单只在**确实换过连接**时才拦东西，没有这个竞态。
         *
         * 用 identity 语义：EventSource 没有实现 equals，按引用比才是这里要的意思。
         */
        val obsoleteSources: MutableSet<EventSource> = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EventSource, Boolean>()),
        )

        /**
         * v1012：换密钥与降档共用的重连动作。
         *
         * 顺序很重要：先登记为已废弃、再 cancel。反过来做的话，旧连接在「cancel 之后、
         * 新连接赋值之前」这个窗口里回调时还不认识自己已被取代，就会再开一路连接（双发）。
         *
         * 声明位置必须在 trySwitchKey 之前 —— 局部函数只能看见先声明的东西。
         */
        fun reconnect(source: EventSourceListener) {
            val previous = activeEventSource
            activeEventSource = null
            if (previous != null) obsoleteSources.add(previous)
            previous?.cancel()
            activeEventSource = client.newEventSource(buildRequest(currentKey), source)
        }

        // v290：换下一把 key 并重建连接的统一入口。返回 true 表示已切换，调用方直接 return。
        // canRetryWithNextKey 内含双保险：已吐过内容不重发；单趟最多把密钥池轮完（每把一遍）。
        // 池子轮完后错误上抛，由上层续跑 / 网络重试开新一趟 —— 冷却满 5 分钟的 key 会自动
        // 恢复参与选择，形成「反复轮询直到续跑次数耗尽」的完整闭环。
        fun trySwitchKey(
            reason: String,
            retryable: Boolean,
            source: EventSourceListener,
            // v1012：refusal 换密钥时**不要**给这把 key 上冷却 —— 它没坏（余额、鉴权都好），
            // 只是这一轮被上游安全分类器拦下。冷却它等于让用户的可用密钥池凭空少一把，
            // 而下一轮很可能就轮到它正常干活。
            cooldown: Boolean = true,
        ): Boolean {
            if (!retryable) return false
            if (!canRetryWithNextKey(keySwitchCount, keyPoolSize, emittedAny)) return false
            keySwitchCount++
            if (cooldown) keyRoulette.markFailed(providerId, currentKey)
            currentKey = keyRoulette.next(providerSetting.apiKey, providerId)
            Log.e(TAG, "$reason，已切换 key 重试（第 $keySwitchCount 把 / 池共 $keyPoolSize 把）")
            resetDecoderForReconnect()
            reconnect(source)
            return true
        }

        /**
         * v1012：把「换到下一档 + 重建请求体 + 换新解码器 + 重连」收成一个动作。
         *
         * 撞 400 与撞拒答两条路共用它。各写一份迟早会漏一步 —— v1012 二审抓到的正是
         * 漏掉「换解码器」那一步（复用已 finished 的实例，第二路连接一个字都读不出来）。
         *
         * 声明顺序有讲究：Kotlin 的局部函数只能看见**先声明**的东西，所以这个被两处
         * 调用的动作必须排在 tryDegradeThinkingTier / exhaustedOrNull 之前
         * （上一版就是这么编不过的）。
         */
        fun degradeToNextVariant(why: String, source: EventSourceListener) {
            index += 1
            variantsTried += 1
            requestBody = buildMessageRequest(
                providerSetting, messages, params, stream = true, flags = variants[index],
            )
            resetDecoderForReconnect()
            reconnect(source)
            Log.w(TAG, "Claude 请求降档（$why）→ 第 $index/${variants.lastIndex} 档：${variants[index].label()}")
        }

        /**
         * v1012：本次请求实际带了哪些可选字段 —— 只读诊断。
         * 用户要的是「能用」，所以该剥就剥；这里只负责把剥完还失败时的现场留下来。
         */
        fun optionalFieldsSent(): List<String> = claudeOptionalFieldsIn(requestBody)

        /**
         * v1012：拼诊断读数。**只放我们自己的元信息与上游原文，绝不放用户内容** ——
         * 报错卡片自带复制按钮，用户一复制就可能贴到群里或 GitHub，而助手提示词、
         * 记忆正文、工作区路径都是隐私。所以上游原文进卡片前先查一次它有没有回显
         * 本次请求内容（有些网关会回显），命中就整段换成占位符。
         */
        fun diagnostics(code: Int?, raw: String?): ClaudeRejectDiagnostics {
            // 脱敏统一走 sanitizeClaudeUpstreamExcerpt：它查 system / messages / tools 三处，
            // 而旧写法只查 messages —— 助手提示词在 system、工具说明书在 tools，
            // 等于两处最主要的隐私面没设防（审查位 P1）。
            val excerpt = sanitizeClaudeUpstreamExcerpt(requestBody, raw?.take(160))
            return ClaudeRejectDiagnostics(
                httpStatus = code,
                variantsTried = variantsTried,
                variantTotal = variants.size,
                lastVariantLabel = variants[index].label(),
                sentOptionalFields = optionalFieldsSent(),
                upstreamExcerpt = excerpt,
            )
        }

        /**
         * 阶梯走到尽头、上游还是回「参数不合法」→ 换成专用异常。
         *
         * 这个状态在 v1012 里没有任何载体：降档函数直接 return false，异常原样上抛，
         * 生成层把它当成临时故障、按用户的续写次数一路整请求重发，而每一趟又从第 0 档
         * 重撞（最坏 99 × 7 ≈ 700 次请求）。
         *
         * 顺序也是修法的一部分：**先判该不该报，再记「撞到底」**。
         * 反过来的话，一次 5xx 就会被误记成「这家剥到底也不行」，把整个会话钉在最退化那一档。
         */
        fun exhaustedOrNull(code: Int?, raw: String?, error: Throwable?): Throwable? {
            if (emittedAny) return null
            if (index < variants.lastIndex) return null
            if (error is ClaudeRefusalException) return null
            // 只有 1 档时，一条自家解坏的帧也不许报成「上游拒绝我们的请求参数」（二审 P1）。
            if (error != null && isOurOwnDecodeFailure(error)) return null
            if (!isClaudeParameterRejection(code, raw)) return null
            thinkingCompat.rememberExhausted(compatKey, index)
            val diag = diagnostics(code, raw)
            // 第二个参数会原样进卡片文案，必须用脱敏后的那一份。旧写法把 raw 直接传下去，
            // 等于自己绕过了刚装的隐私闸 —— 卡片自带复制按钮，用户一复制就可能贴到群里。
            return ClaudeDegradeExhaustedException(diag, diag.upstreamExcerpt)
        }

        /**
         * v1012：上游拒收本档请求 → 换下一档、整请求重发。
         *
         * 三道闸门：
         * - 一个字都还没吐给用户（v285 铁律：吐过再重发必然前半段重复）；
         * - 阶梯还有下一档（变体按真实请求体探测过，「还有下一档」就等于「真的有东西可剥」）；
         * - 错误确实是「参数被拒」这一类（401/402/429 该走换密钥，5xx 该走续跑）。
         *
         * 试探只改内存里的 index，**不落盘**；落盘发生在这一档真的吐出内容时（见 sendChunks）。
         * 必须排在 trySwitchKey 之前：400 不在 KEY_RETRYABLE_CODES 里，换密钥那条路本来不会走。
         */
        fun tryDegradeThinkingTier(code: Int?, bodyRaw: String?, source: EventSourceListener): Boolean {
            if (emittedAny) return false
            if (index >= variants.lastIndex) return false
            if (!isClaudeParameterRejection(code, bodyRaw)) return false
            degradeToNextVariant("参数被拒 code=$code", source)
            return true
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // v1012：换密钥 / 降档 / refusal 重发之后，被取代的旧连接还会迟到回调，
                // 整批丢弃（否则旧连接的收尾会把新流 close 掉，新 key 的回答一个字都到不了界面）
                if (eventSource in obsoleteSources) return
                Log.d(TAG, "onEvent: type=$type, data=$data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) {
                        // v1011 二审：正常收尾（收到 message_stop）也要报跳帧 —— 这条路不会回调 onClosed
                        reportSkippedFrames("message_stop")
                        // v1012：上游以 refusal 收尾且一个字都没吐。官方定义这是 HTTP 200 的
                        // 「正常响应」，所以旧代码把它当成功收尾：close() 不带异常 → 上层
                        // 记成 COMPLETED、既不重试也不换密钥，用户只看到一句看不懂的
                        // 「结束原因：refusal」（真机截图实锤）。
                        // 现在改成抛 ClaudeRefusalException：
                        //   · 带上了 stop_details 的原文（分类 + 说明）；
                        //   · 顺序是「先剥字段、再换密钥」—— 这一类判的是请求形状，
                        //     换一把账号看的还是同一份东西，所以剥字段才是有指向性的那一步。
                        //     （v1012 反过来做，而且我当时把官方那句「换个模型通常能答」
                        //     误读成「换个账号」，注释一并纠正。）
                        //   · 两步都做完仍被拒才把异常抛给上层，进用户设置的续跑通道。
                        if (decoder.finishReason == CLAUDE_STOP_REFUSAL && !emittedAny) {
                            val category = decoder.refusalCategory
                            val detail = decoder.refusalDetail
                            // v1012：拒答也剥字段，但**只剥有可能剥对的分类**。
                            // reasoning_extraction 判的是请求形状（我们在要模型内部输出），
                            // 值得从完整形态一路剥到底；cyber / bio 那几类判的是内容，
                            // 剥缓存和采样不会让内容变得不违规，试了就是白烧计费请求。
                            if (!emittedAny &&
                                claudeRefusalWorthStripping(category) &&
                                index < variants.lastIndex
                            ) {
                                degradeToNextVariant("拒答 category=$category", this)
                                return
                            }
                            // 剥到底还是拒 → 才去换密钥（换上游账号对内容判定无效，
                            // 但中转站每把 key 背后可能根本是不同渠道，值得一试）。
                            if (trySwitchKey(
                                    reason = "stream: refusal",
                                    retryable = true,
                                    source = this,
                                    cooldown = false,
                                )
                            ) return
                            close(
                                ClaudeRefusalException(
                                    category = category,
                                    // 上游的 explanation 也过同一道脱敏闸：网关有可能把它拼成请求回显
                                    detail = sanitizeClaudeUpstreamExcerpt(requestBody, detail),
                                    diagnostics = diagnostics(null, detail),
                                    triedOtherKeys = keySwitchCount > 0,
                                    // 三审 P2：不传的话默认 1，「只有一把密钥」这句就变成猜的
                                    keyPoolSize = keyPoolSize,
                                ),
                            )
                            return
                        }
                        close()
                    }
                } catch (e: Throwable) {
                    // v1012：不少中转站把上游的 400 **包进 HTTP 200 的 SSE error 帧**里发回来
                    // （v1011 让解码器见 error 帧就抛，正是为了不再静默）。那种情况下 onFailure
                    // 根本不会被回调，降档必须在这里也接一次；没有状态码可依据，所以判定要求
                    // 原文点出具体字段名（见 isClaudeParameterRejection 的 null 分支）。
                    // 我们**自己**解码一帧失败（kotlinx 的 SerializationException，例如
                    // `data: ping`、keepalive 这种非 JSON 行）不是「上游嫌参数不合法」。
                    // 它的文案天然带 "unexpected"，还会把整段原始 data 当 Source 片段拼进
                    // message —— 光靠文案判据挡不住，所以按类型排除。
                    // 真错误帧走的是 parseErrorDetail → HttpException，不受这里影响。
                    if (!isOurOwnDecodeFailure(e) && tryDegradeThinkingTier(null, e.message, this)) return
                    exhaustedOrNull(null, e.message, e)?.let { close(it); return }
                    // v290：流内 error（HTTP 200 + SSE error 事件）以前直接 close —— 换 key 逻辑
                    // 只挂在 onFailure 的 HTTP 状态码上，流内限流一次 key 都不换就放弃。
                    // 现在按密钥类文本判定补上。
                    if (trySwitchKey("onEvent: ${e.message}", isKeyRetryableError(e), this)) return
                    close(e)
                }
            }

            override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                // v1012：被取代的旧连接的失败不算本次生成的失败
                if (source in obsoleteSources) return
                // v255：密钥类错误（401/402/403/429）→ 标记失败并换下一个 key 重试。
                // v290：换 key 次数与重发保护收敛进 trySwitchKey（把密钥池轮完为止）。
                val code = response?.code
                // v1012：响应体只能读一次，提前取出来给「参数拒绝」判定与后面的错误解析共用。
                // （原来它在换密钥之后才读，而换密钥重发根本用不到它 —— 顺序前移无副作用。）
                val bodyRaw = response?.body?.stringSafe()
                // v1012：上游说「参数不认识」→ 降一档重发。必须排在换密钥之前：400 不在
                // KEY_RETRYABLE_CODES 里，换密钥那条路本来不会走。
                if (tryDegradeThinkingTier(code, bodyRaw, this)) return
                // v1013：阶梯已经走到尽头，上游还是回「参数不合法」。
                // 这个状态在 v1012 里没有任何载体 —— 降档函数直接 return false，
                // 于是它被生成层当成临时故障、按续写次数一路整请求重发，
                // 而每一趟又从第 0 格重撞（最坏 99 × 5 ≈ 495 次请求）。
                // 现在换成专用异常：生成层认得它，直接停下把读数交出去。
                // v1012：阶梯走完还是 400 → 换成专用异常。这个状态在旧写法里没有任何载体，
                // 于是它被生成层当成临时故障、按续写次数一路整请求重发，
                // 而每一趟又从第 0 档重撞。
                exhaustedOrNull(code, bodyRaw, null)?.let { close(it); return }
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
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    // v285：同 ResponseAPI —— exception 为 null 时必须造一个带状态码的异常，
                    // 否则失败会被当成「顺利结束」，表现为空回复、零报错、不触发自动续跑。
                    close(exception ?: streamFailureFallback(response, bodyRaw))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // v1012：旧连接被 cancel 后走到这里时，decoder 已经是新一路的了 ——
                // 不丢弃就会把新流凭空收尾。
                if (eventSource in obsoleteSources) return
                sendChunks(decoder.onClosed())
                reportSkippedFrames("onClosed")
                close()
            }
        }

        activeEventSource = client.newEventSource(buildRequest(currentKey), listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            activeEventSource?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false,
        flags: ClaudeRequestFlags = ClaudeRequestFlags(),
    ): JsonObject {
        // v1012：promptCaching 这一路要同时看「用户开没开」和「这一档剥没剥」。
        // 顶层 cache_control 尤其可疑 —— Anthropic 的 Messages API 没有这个顶层字段
        // （缓存断点是挂在内容块上的），严格上游会当成未知字段直接 400。
        // 这里不替用户下结论，交给剥档去试：剥到哪一档能用，就记那一档。
        val cacheControlOn = providerSetting.promptCaching && !flags.dropCacheControl
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(messages, cacheControlOn, providerSetting.promptCacheTtl)
            )
            put("max_tokens", params.maxTokens ?: 64_000)

            // 顶层 cache_control: 让 Anthropic 自动管理缓存断点
            if (cacheControlOn) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null &&
                !params.reasoningLevel.isEnabled &&
                !flags.dropTemperature
            ) {
                put(
                    "temperature",
                    params.temperature
                )
            }
            if (params.topP != null && !flags.dropTopP) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            if (cacheControlOn && index == systemTextParts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            // 处理 thinking
            // v1012：这里原来是「不分型号永远发最新格式」（adaptive + display +
            // output_config.effort），官方 3.1.0 就是这么写的。后果是三代协议互不兼容：
            // 4.5 系收到 adaptive 必 400、5.x 系收到 disabled 必 400、上游版本偏旧的中转站
            // 收到 display/output_config 也 400 —— 而 400 是「重发多少次都还是 400」的确定性
            // 失败，只会把用户的续跑次数白烧光（真机两张截图即此）。
            // 现在按「用户选项 + 型号能力 + 思考档位 + 降档层级」算字段，判定表与依据
            // 全部写在 ClaudeThinkingCompat.kt。
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val thinkingSupport = claudeThinkingSupport(params.model.modelId)
                val thinkingFields = buildClaudeThinkingFields(
                    style = providerSetting.thinkingCompat,
                    support = thinkingSupport,
                    level = params.reasoningLevel,
                    maxTokens = params.maxTokens,
                    flags = flags,
                )
                thinkingFields.thinking?.let { put("thinking", it) }
                thinkingFields.outputConfig?.let { put("output_config", it) }
            }

            // 处理工具
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            val toolDefinitions = buildList {
                if (useFunctionTools) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.parameters()))
                        })
                    }
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> add(buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                        })
                        BuiltInTools.UrlContext,
                        BuiltInTools.ImageGeneration,
                        -> Unit
                    }
                }
            }
            if (toolDefinitions.isNotEmpty()) {
                putJsonArray("tools") {
                    toolDefinitions.forEachIndexed { index, definition ->
                        if (cacheControlOn && index == toolDefinitions.lastIndex) {
                            add(JsonObject(
                                definition + mapOf(
                                    "cache_control" to cacheControlEphemeral(providerSetting.promptCacheTtl)
                                )
                            ))
                        } else {
                            add(definition)
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message)
                } else {
                    addUserMessage(message)
                }
            }
    }.let { messagesArray ->
        // 二改版: 先消毒再插 cache_control, 否则 cache_control 可能落在随后被删除的 block 上
        sanitizeClaudeMessages(messagesArray)
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
    private fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else {
                null
            }
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else {
            return messages
        }

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else {
                        block
                    }
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else {
                msg
            }
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.toContentBlocks().forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.flatMap { it.toContentBlocks() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlocks(): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> serverToolContentBlocks()
        else -> listOfNotNull(toContentBlock())
    }

    /**
     * 解析后 server tool 的 call/result 会合并到同一个 part。回放连续 server tool 时
     * 按原始 content block index 还原顺序；旧消息没有 index 时回退为先 calls、后 results。
     */
    private fun List<UIMessagePart>.toContentBlocks(): List<JsonObject> = buildList {
        val serverTools = mutableListOf<UIMessagePart.ServerTool>()

        fun flushServerTools() {
            val calls = mutableListOf<Pair<JsonObject, Int?>>()
            val results = mutableListOf<Pair<JsonObject, Int?>>()
            serverTools.forEach { tool ->
                val blocks = tool.serverToolContentBlocks()
                val metadata = tool.metadataAs<ServerToolMetadata>()
                blocks.firstOrNull()?.let { calls.add(it to metadata?.callIndex) }
                blocks.getOrNull(1)?.let { results.add(it to metadata?.resultIndex) }
            }
            val blocks = calls + results
            val orderedBlocks = if (blocks.all { it.second != null }) {
                blocks.sortedBy { it.second }
            } else {
                blocks
            }
            orderedBlocks.forEach { add(it.first) }
            serverTools.clear()
        }

        for (part in this@toContentBlocks) {
            if (part is UIMessagePart.ServerTool) {
                serverTools.add(part)
            } else {
                flushServerTools()
                addAll(part.toContentBlocks())
            }
        }
        flushServerTools()
    }

    private fun UIMessagePart.ServerTool.serverToolContentBlocks(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.ANTHROPIC_MESSAGES) return emptyList()

        return buildList {
            val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            add(rawCall?.let {
                if (input == null) it else JsonObject(it + mapOf("input" to input))
            } ?: buildJsonObject {
                put("type", "server_tool_use")
                put("id", toolCallId)
                put("name", toolName)
                input?.let { put("input", it) }
            })
            val rawResult = metadata?.result.takeIf { protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            if (isFinished && (output != null || rawResult != null)) {
                add(rawResult ?: buildJsonObject {
                    put("type", "${toolName}_tool_result")
                    put("tool_use_id", toolCallId)
                    output?.let { put("content", it) }
                })
            }
        }
    }

    private fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> buildJsonObject {
            encodeBase64(withPrefix = false).onSuccess { encoded ->
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }.onFailure {
                Log.w(TAG, "encode image failed: $url", it)
                put("type", "text")
                put("text", "")
            }
        }

        is UIMessagePart.Reasoning -> buildJsonObject {
            put("type", "thinking")
            put("thinking", reasoning)
            metadataAs<ClaudeReasoningMetadata>()?.signature?.let { put("signature", it) }
        }

        else -> null
    }

private fun sanitizeClaudeToolId(id: String): String {
    val sanitized = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return if (sanitized.isBlank()) "tool_${Uuid.random().toString().replace("-", "")}" else sanitized
}

private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
    put("type", "tool_use")
    put("id", sanitizeClaudeToolId(toolCallId))
    put("name", toolName)
    put("input", inputAsJson())
}

private fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
    put("type", "tool_result")
    put("tool_use_id", sanitizeClaudeToolId(toolCallId))
    putJsonArray("content") {
        output.mapNotNull { it.toContentBlock() }.forEach { add(it) }
    }
}

    internal fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()
        val serverToolIndexes = mutableMapOf<String, Int>()

        content.forEachIndexed { blockIndex, contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                        if (signature != null) {
                            reasoning.metadata = ClaudeReasoningMetadata(signature = signature).toMetadata()
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> {
                    val data = block["data"]?.jsonPrimitiveOrNull?.contentOrNull
                    println(data)
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                else -> if (type.isClaudeServerToolUseType()) {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    serverToolIndexes[id] = parts.size
                    parts.add(
                        UIMessagePart.ServerTool(
                            toolCallId = id,
                            toolName = block["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = block["input"],
                            status = ServerToolStatus.IN_PROGRESS,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = block,
                                callIndex = blockIndex,
                            ).toMetadata(),
                        )
                    )
                } else if (type == "input_json_delta") {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList()
                        )
                    )
                } else if (type.isClaudeServerToolResultType()) {
                    val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val output = block["content"]
                    val status = if (output.isClaudeServerToolError()) {
                        ServerToolStatus.FAILED
                    } else {
                        ServerToolStatus.COMPLETED
                    }
                    val index = serverToolIndexes[id]
                    if (index == null || parts.getOrNull(index) !is UIMessagePart.ServerTool) {
                        parts.add(UIMessagePart.ServerTool(
                            toolCallId = id,
                            toolName = type?.removeSuffix("_tool_result") ?: "",
                            output = output,
                            status = status,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = block,
                                resultIndex = blockIndex,
                            ).toMetadata(),
                        ))
                    } else {
                        val tool = parts[index] as UIMessagePart.ServerTool
                        parts[index] = tool.copy(
                            output = output,
                            status = status,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = tool.metadataAs<ServerToolMetadata>()?.call,
                                callIndex = tool.metadataAs<ServerToolMetadata>()?.callIndex,
                                result = block,
                                resultIndex = blockIndex,
                            ).toMetadata(),
                        )
                    }
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseTokenUsage(bodyJson: JsonObject?): TokenUsage? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
            // v297：思考 token 单列。Anthropic 的 output_tokens 是**含 thinking 的计费总量**，
            // thinking_tokens 只是可观测分解 —— 所以绝不能再加到 completion 上，否则一笔钱数两遍。
            reasoningTokens = (usageJson["output_tokens_details"] as? JsonObject)
                ?.get("thinking_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}

internal fun String?.isClaudeServerToolResultType(): Boolean =
    this != null && this != "tool_result" && endsWith("_tool_result")

internal fun String?.isClaudeServerToolUseType(): Boolean =
    this == "server_tool_use" || (this != null && this != "tool_use" && endsWith("_tool_use"))

internal fun JsonElement?.isClaudeServerToolError(): Boolean {
    val content = this as? JsonObject ?: return false
    return content["type"]?.jsonPrimitive?.contentOrNull?.endsWith("_error") == true
}
