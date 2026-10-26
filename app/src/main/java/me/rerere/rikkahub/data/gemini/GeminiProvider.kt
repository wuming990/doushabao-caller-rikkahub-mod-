package me.rerere.rikkahub.data.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.google.CODE_ASSIST_SAFETY_CATEGORIES
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.android.Logging
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Talks to Google Cloud Code Assist with a signed-in Google account instead of an API key.
 *
 * The wire format is plain Gemini wrapped one level deep: the request body goes under `request`
 * next to the account's project and the model id, and each SSE payload carries the usual
 * candidates under `response`. That lets the whole message conversion be delegated to
 * [GoogleProvider] rather than duplicated here.
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val repository: GeminiAccountRepository,
    private val json: Json,
) : Provider<ProviderSetting.GeminiOAuth> {
    private val wire = GoogleProvider(client)

    override suspend fun listModels(
        providerSetting: ProviderSetting.GeminiOAuth,
    ): List<Model> = withContext(Dispatchers.IO) {
        val account = repository.acquireAccount()
        val response = client.newCall(
            Request.Builder()
                .url("${GeminiAccountRepository.DAILY_CODE_ASSIST_ENDPOINT}/v1internal:fetchAvailableModels")
                .antigravityHeaders(account.accessToken)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) repository.markInvalid(account.id)
            error("Failed to list Gemini models: ${response.code} $body")
        }
        mapAvailableModels(body, json)
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val handler = StreamChunkHandler(params.model)
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = handler.handle(collected, chunk)
        }
        val message = collected.last()
        return TextGenerationResult(
            id = "",
            model = params.model.modelId,
            message = message,
            usage = message.usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val account = repository.acquireAccount()
        val requestBody = buildJsonObject {
            put("project", account.projectId)
            put("model", params.model.modelId)
            // v279：官方 Antigravity 在信封上自报流量类型，缺这三个字段的请求会被 Cloud Code
            // Assist 当成非 agent 流量拒掉。badlogic/pi-mono issue #554 是一模一样的症状
            // （userAgent 不是 "antigravity"、没有 requestType 时全部 429），PR #571 补上
            // 这几个字段后恢复。requestId 每次唯一，前缀固定 "agent-"。
            put("requestType", "agent")
            put("userAgent", "antigravity")
            put("requestId", newAntigravityRequestId())
            put(
                "request",
                withAntigravityRequestShape(
                    raiseMaxTokensAboveThinkingBudget(
                        raiseThinkingBudgetToClaudeFloor(
                            wire.buildCompletionRequestBody(messages, params, CODE_ASSIST_SAFETY_CATEGORIES)
                        )
                    ),
                    messages,
                ).also { logAntigravityToolShape(it, params.model.modelId) }
            )
        }
        val request = Request.Builder()
            .url("${GeminiAccountRepository.DAILY_CODE_ASSIST_ENDPOINT}/v1internal:streamGenerateContent?alt=sse")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))  // v312 合并上游 2.5.6：带上「高级设置」里的自定义请求头
            .antigravityHeaders(account.accessToken)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val adapter = GeminiStreamChunkAdapter()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                try {
                    val payload = json.parseToJsonElement(data).jsonObject
                    payload["error"]?.jsonObject?.let { error ->
                        close(
                            IllegalStateException(
                                "Cloud Code Assist error: " +
                                    (error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown")
                            )
                        )
                        return
                    }
                    // Cloud Code Assist nests the ordinary Gemini payload one level down; a
                    // chunk that carries only bookkeeping has no `response` at all.
                    val inner = payload["response"]?.jsonObject ?: return
                    val reason = inner["promptFeedback"]?.jsonObject
                        ?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    if (reason != null) {
                        close(IllegalStateException("Prompt feedback: $reason"))
                        return
                    }
                    val chunk = wire.parseStreamCandidates(inner, params.model) ?: return
                    adapter.translate(chunk).forEach { streamChunk ->
                        trySend(streamChunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onEvent: failed to parse chunk, payload=${data.take(PAYLOAD_LOG_LIMIT)}", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response?.code == 401) {
                    launch { repository.markInvalid(account.id) }
                }
                close(
                    resolveStreamFailureCause(t, response?.code, json) {
                        response?.takeUnless { it.isSuccessful }?.body?.stringSafe()
                    }
                )
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(adapter.finish())
                close()
            }
        }
        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
        // trySend silently drops a delta when the buffer is full, dropping characters mid-reply
        // (#1295), so the buffer must be unbounded - same as the other providers' streamText.
    }.buffer(Channel.UNLIMITED)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Gemini OAuth provider")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        // Keep the diagnostic log line readable; the payload can be many KB of candidate text.
        const val PAYLOAD_LOG_LIMIT = 500
    }
}

private const val TAG = "GeminiProvider"

/**
 * Adapts [GoogleProvider.parseStreamCandidates]'s legacy [MessageChunk] shape (see that
 * function's doc for why it still exists) onto the app-wide [StreamChunk] event stream, so this
 * provider can keep consuming Cloud Code Assist's per-event candidate parsing while still
 * implementing the current [Provider] interface.
 *
 * Mirrors the merge rules the removed `List<UIMessage>.handleMessageChunk` used to apply: a part
 * joins the previous part of the same kind, or starts a new one - just expressed as
 * Start/Delta/End events instead of an eagerly merged [UIMessage]. One instance is stateful for
 * exactly one stream and must not be reused across calls.
 */
internal class GeminiStreamChunkAdapter {
    private enum class OpenKind { TEXT, REASONING, IMAGE }

    private var openKind: OpenKind? = null
    private var openId: String = ""
    private var nextId = 0
    // v1007：Cloud Code Assist（反重力通道）每一块都带 candidate.finishReason，
    // 但适配器以前把它丢了，收尾永远发 Finish() 不带原因 —— 于是主对话的
    // 「撞输出上限自动续写」在这条通道上等于空头承诺（isTruncated(null) 恒 false）。
    private var lastFinishReason: String? = null

    private fun startNew(kind: OpenKind): String {
        openKind = kind
        openId = "gemini-${nextId++}"
        return openId
    }

    fun translate(chunk: MessageChunk): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        val choice = chunk.choices.getOrNull(0)
        // v1007：把结束原因留下来，交给 finish() 一起发出去（STOP / MAX_TOKENS / SAFETY…）
        choice?.finishReason?.takeIf { it.isNotBlank() }?.let { lastFinishReason = it }
        val delta = choice?.delta ?: choice?.message
        if (delta != null) {
            // Google never sends an explicit "thought finished" signal; infer it the same way
            // the removed merge logic did - a delta with no reasoning part at all closes
            // whatever reasoning run is currently open.
            val hasReasoning = delta.parts.any { it is UIMessagePart.Reasoning }
            if (openKind == OpenKind.REASONING && !hasReasoning && delta.parts.isNotEmpty()) {
                out += StreamChunk.ReasoningEnd(openId)
                openKind = null
            }
            delta.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        if (part.text.isNotEmpty()) {
                            val id = if (openKind == OpenKind.TEXT) openId else startNew(OpenKind.TEXT)
                            out += StreamChunk.TextDelta(id, part.text)
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        if (part.reasoning.isNotEmpty() || part.metadata != null) {
                            val id =
                                if (openKind == OpenKind.REASONING) openId else startNew(OpenKind.REASONING)
                            out += StreamChunk.ReasoningDelta(
                                id = id,
                                text = part.reasoning,
                                metadata = part.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isNew = openKind != OpenKind.IMAGE
                        val id = if (isNew) startNew(OpenKind.IMAGE) else openId
                        // parseMessagePart already builds a full data URL; StreamChunk.ImageSnapshot
                        // wants just the base64 payload and re-attaches its own prefix.
                        val base64 = part.url.substringAfter(',', part.url)
                        out += StreamChunk.ImageSnapshot(id = id, data = base64, metadata = part.metadata)
                    }

                    is UIMessagePart.Tool -> {
                        // GoogleProvider assigns a fresh random id per functionCall part and Gemini
                        // sends each call's name + args in a single event, so every Tool part here
                        // is already complete - no cross-event merge is needed.
                        openKind = null
                        out += StreamChunk.ToolCallStart(
                            id = part.toolCallId,
                            toolName = part.toolName,
                            metadata = part.metadata,
                        )
                        out += StreamChunk.ToolCallDelta(
                            id = part.toolCallId,
                            inputDelta = part.input,
                            metadata = part.metadata,
                        )
                    }

                    is UIMessagePart.ServerTool -> {
                        // v308：开启 includeServerSideToolInvocations 后，谷歌把内置工具
                        // （自家搜索等）的 call/response 直接放进 Content 里回传，GoogleProvider
                        // 会解析成 ServerTool。本适配器原先没有这一分支，它们会掉进 else 被丢弃 ——
                        // 于是「搜索能用但看不到搜索过程」，且多轮时这些片段无法回传给模型。
                        // 形状照 ai 模块的 GoogleStreamDecoder（同一套语义、现成实现）。
                        openKind = null
                        if (part.status == ServerToolStatus.IN_PROGRESS) {
                            out += StreamChunk.ServerToolStart(
                                id = part.toolCallId,
                                toolName = part.toolName,
                                input = part.input,
                                metadata = part.metadata,
                            )
                        } else {
                            out += StreamChunk.ServerToolEnd(
                                id = part.toolCallId,
                                input = part.input,
                                output = part.output,
                                status = part.status,
                                metadata = part.metadata,
                            )
                        }
                    }

                    else -> Log.w(TAG, "translate: unsupported delta part $part")
                }
            }
            if (delta.annotations.isNotEmpty()) {
                out += StreamChunk.Annotations(delta.annotations)
            }
        }
        chunk.usage?.let { out += StreamChunk.Usage(it) }
        return out
    }

    /**
     * Sent once, right before the underlying event source closes normally.
     *
     * v1007：`terminatedWithoutSentinel = true` —— 这条通道的 SSE 没有协议级终止标记，
     * 上游把连接关掉就算收尾。以前一律当作「正常说完」，于是「没报错、没结束原因、
     * 内容却是半截」这种无声截断永远不会触发续写（用户反复报过这类）。
     * 上层的判定是「terminatedWithoutSentinel && finishReason 为空」两个条件同时成立，
     * 正常回答带 STOP 时不会被误判成截断，所以这里置 true 是安全的。
     */
    fun finish(): StreamChunk = StreamChunk.Finish(
        finishReason = lastFinishReason,
        terminatedWithoutSentinel = true,
    )
}

// Errors are small; this only bounds a pathological body, unlike PAYLOAD_LOG_LIMIT above which
// bounds streamed candidate text. Caps the rendered error detail appended to the thrown message.
private const val ERROR_DETAIL_LOG_LIMIT = 2000

private fun parseErrorMessage(body: String?, json: Json): String? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: return null
        val code = error["code"]?.jsonPrimitive?.intOrNull
        val prefix = if (code != null) "Cloud Code Assist error ($code): $message" else message
        prefix + formatErrorDetail(error).take(ERROR_DETAIL_LOG_LIMIT) + quotaHint(code, error)
    }.getOrNull()
}

// gemini-3.1-pro-high is advertised by fetchAvailableModels but Antigravity's
// v1internal streamGenerateContent endpoint rejects it as a model name at every
// reasoning level (400, no fieldViolations to name the field). Confirmed on-device
// 2026-08-12 and independently by five other projects; gemini-3.1-pro-low works fine on
// the same endpoint, so this is scoped to the exact id rather than a `-high` suffix rule
// (other suffixed models work). Re-test and drop this once upstream fixes it.
//
// internal rather than private: PreferencesStore's GeminiOAuth normalization branch also
// filters on this set, to evict copies already persisted from before this filter existed.
internal val DENIED_MODEL_IDS = setOf("gemini-3.1-pro-high")

/**
 * Parses the `fetchAvailableModels` response body's `models` object into the list of [Model]s the
 * provider offers, filtering out internal-only entries and [DENIED_MODEL_IDS]. An absent or empty
 * `models` object yields an empty list.
 */
private fun mapAvailableModels(body: String, json: Json): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonObject
        ?: return emptyList()
    return models.mapNotNull { (modelId, element) ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item["isInternal"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        if (modelId in DENIED_MODEL_IDS) return@mapNotNull null
        val supportsImages = item["supportsImages"]?.jsonPrimitive?.booleanOrNull == true
        Model(
            modelId = modelId,
            displayName = item["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId,
            inputModalities = if (supportsImages) {
                listOf(Modality.TEXT, Modality.IMAGE)
            } else {
                listOf(Modality.TEXT)
            },
            abilities = buildList {
                add(ModelAbility.TOOL)
                if (item["supportsThinking"]?.jsonPrimitive?.booleanOrNull == true) {
                    add(ModelAbility.REASONING)
                }
            },
        )
    }
}

/**
 * Renders `error.status` and a flattened `error.details[]` for appending after the existing
 * `Cloud Code Assist error (code): message` prefix. `BadRequest` detail entries surface each
 * `fieldViolations[]` entry's `field` and `description` - that is the whole point, since that is
 * where Google names the invalid argument. Other detail entries render as their `@type` plus
 * whatever scalar fields they carry. Any unexpected shape (wrong types, missing keys) is skipped
 * rather than thrown, so a malformed body degrades to just the prefix.
 */
private fun formatErrorDetail(error: JsonObject): String {
    val status = (error["status"] as? JsonPrimitive)?.contentOrNull
    val details = (error["details"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull(::formatDetailEntry)
        .orEmpty()
    val parts = buildList {
        status?.let { add("status=$it") }
        addAll(details)
    }
    if (parts.isEmpty()) return ""
    return " (" + parts.joinToString("; ") + ")"
}

private fun formatDetailEntry(entry: JsonObject): String? {
    val violations = (entry["fieldViolations"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { violation ->
            val field = (violation["field"] as? JsonPrimitive)?.contentOrNull
            val description = (violation["description"] as? JsonPrimitive)?.contentOrNull
            if (field == null && description == null) {
                null
            } else {
                "field=${field ?: "unknown"}, description=${description ?: "unknown"}"
            }
        }
    if (!violations.isNullOrEmpty()) {
        return violations.joinToString("; ")
    }
    val type = (entry["@type"] as? JsonPrimitive)?.contentOrNull
    val scalars = entry.entries
        .filter { it.key != "@type" && it.key != "fieldViolations" }
        .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { "$key=$it" } }
    if (type == null && scalars.isEmpty()) return null
    return buildString {
        append(type ?: "unknown detail type")
        if (scalars.isNotEmpty()) {
            append(": ")
            append(scalars.joinToString(", "))
        }
    }
}

/**
 * v280：429 的成因分档。
 * v281：加上「Google 给的倒计时不可信」这一档。
 *
 * v279 只判断「响应里有没有给重试时间」，于是把 `retryDelay=590255s`（163 小时 57 分）也说成
 * 「这是短时速率限制，按上面给出的等待时间稍后重试即可」—— 真机截图证实这条文案把用户往完全
 * 错误的方向带。判别顺序改为照抄 gemini-cli 的 googleQuotaErrors：**先读 `ErrorInfo.reason`，
 * 再看 `RetryInfo.retryDelay` 的量级**，5 分钟是「等一下就好」与「等到重置」的分界
 * （gemini-cli 的 MAX_RETRYABLE_DELAY_SECONDS 用的也是 300 秒）。
 *
 * v280 把那条 163 小时的倒计时解释成「周额度真的耗尽」，仍然是错的：同一个账号第二天早上
 * （约 10 小时后）就恢复了，配额面板全程显示 100% 余量。真因见 [UNTRUSTED_DELAY_SECONDS]
 * 的注释 —— 拦人的是另一层没公开的短时限制，返回的却是周桶的重置时间。**教训：retryDelay
 * 的绝对值不能当真，只能当量级参考。**
 *
 * 五种归类：模型容量不足（与额度无关）、倒计时长到不可信（几小时后自己会好）、账号配额确实
 * 耗尽（倒计时在可信量级内，重试无用但可换独立额度池的模型）、真正的短时限流（等一会儿就行）、
 * 以及什么结构化字段都没有的裸 429（Google 侧配额同步问题或软限流，客户端无解）。
 */
private fun quotaHint(code: Int?, error: JsonObject): String {
    if (code != 429) return ""
    val reason = quotaReasonOf(error)
    val retryAfterSeconds = retryDelaySecondsOf(error)
    val resetsIn = retryAfterSeconds?.let { "，约 ${formatQuotaWait(it)}后重置" }.orEmpty()
    return when {
        // 容量不足是最明确的信号，而且跟额度无关，先认它。
        reason == REASON_MODEL_CAPACITY ->
            " —— 这个模型当前容量不足，不是你的额度问题。稍后再试，或换一个模型。"

        // v281：倒计时长到一天以上时**不能照抄**。
        //
        // 真机事故：用户拿到 retryDelay=590255s（163 小时 57 分），v280 照抄成「账号配额已用尽，
        // 约 6 天 19 小时后重置，现在重试没有用」，结果他第二天早上（约 10 小时后）就能正常用了。
        // 查证结论：Google 后端另有一层**没在配额接口暴露**的短时/高强度限制，被它拦下时返回的
        // 却是最大那只桶（周桶）的 resetTime —— 于是倒计时看着像一周，实际几小时就放行
        // （gemini-cli #14883 / #13158 / #12940 都是同一个现象，社区记录 6~8 小时恢复）。
        //
        // 所以这一档只把倒计时当「周额度的边界」如实说出来，同时点明真实等待远短于它，
        // 并给出两条马上能走的路。24 小时以内的倒计时仍照实说（那种量级是可信的）。
        retryAfterSeconds != null && retryAfterSeconds > UNTRUSTED_DELAY_SECONDS ->
            " —— 触发了配额限制。Google 给出的倒计时是「每周额度」的（约 " +
                "${formatQuotaWait(retryAfterSeconds)}），但这类报错多半是撞上了它没公开的短时" +
                "高强度限制，实际往往几小时就自动恢复，不必真等那么久。可以先换 Claude、GPT 那类" +
                "模型（走另一份独立额度），或者过几小时再回来试。"

        reason == REASON_QUOTA_EXHAUSTED ||
            (retryAfterSeconds != null && retryAfterSeconds > SHORT_RETRY_LIMIT_SECONDS) ->
            " —— 账号配额已用尽$resetsIn。现在重试没有用。Gemini 系列（Pro/Flash）共用同一份额度；" +
                "Claude、GPT 那类模型走的是另一份独立额度，可以先换过去用。"

        retryAfterSeconds != null ->
            " —— 这是短时限流，等 ${formatQuotaWait(retryAfterSeconds)}后重试即可。"

        else ->
            " —— 账号级限流：响应里没有给出恢复时间，通常是 Google 侧的配额同步问题或对该账号的软限流，" +
                "立刻重试没有用。可以过一段时间再试，或先在官方 Antigravity 网页端确认这个账号本身还能不能用。"
    }
}

private const val REASON_QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED"
private const val REASON_MODEL_CAPACITY = "MODEL_CAPACITY_EXHAUSTED"

// gemini-cli 用 300 秒作「可等待 → 判为终端错误」的分界，这里照用。
private const val SHORT_RETRY_LIMIT_SECONDS = 300.0

/**
 * v281：倒计时超过这个值就认定「Google 给的数字不可信」。
 *
 * 取 24 小时的理由：真正可信的等待量级是分钟到小时（5 小时桶最多也就 5 小时）；一旦跳到
 * 「几天」，那就是周桶的 resetTime 被当成本次重试延迟返回了 —— 已知实测 163h 的倒计时
 * 实际 10 小时就恢复。用天做分界，既不会误伤 5 小时桶真耗尽的情形，也能把周桶那种
 * 明显离谱的数字全部拦下。
 */
private const val UNTRUSTED_DELAY_SECONDS = 86400.0

/**
 * v280：取 `error.details[]` 里 ErrorInfo 的 `reason`。
 *
 * 不按 `@type` 挑，而是取第一个带 `reason` 字段的 detail —— Google 偶尔省略或改写 `@type`，
 * 而 `reason` 只出现在 ErrorInfo 上，按字段挑更耐得住形状变化。
 */
internal fun quotaReasonOf(error: JsonObject): String? =
    (error["details"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstNotNullOfOrNull { it["reason"]?.jsonPrimitive?.contentOrNull }
        ?.takeIf { it.isNotBlank() }

/**
 * v280：取恢复等待时长，单位秒。
 *
 * 依次找 RetryInfo 的 `retryDelay`、detail 顶层的 `quotaResetDelay`、以及 ErrorInfo.metadata
 * 里的 `quotaResetDelay` —— 三种位置在真实响应里都出现过。
 */
internal fun retryDelaySecondsOf(error: JsonObject): Double? =
    (error["details"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstNotNullOfOrNull { detail ->
            detail["retryDelay"]?.jsonPrimitive?.contentOrNull
                ?: detail["quotaResetDelay"]?.jsonPrimitive?.contentOrNull
                ?: (detail["metadata"] as? JsonObject)
                    ?.get("quotaResetDelay")?.jsonPrimitive?.contentOrNull
        }
        ?.let(::parseDurationSeconds)

/**
 * v280：解析 Google 的时长字符串。
 *
 * protobuf 的 Duration 序列化成 `"590255.840167514s"`，但配额相关字段在真实响应里还出现过
 * `"10m"`、`"539.477544ms"`、`"1h30m"` 这些写法，所以按单位逐段累加，而不是简单 `removeSuffix("s")`。
 * 完全不带单位时（例如 `"300"`）按秒处理。
 */
internal fun parseDurationSeconds(raw: String): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val matches = DURATION_PATTERN.findAll(text).toList()
    if (matches.isEmpty()) return text.toDoubleOrNull()
    var total = 0.0
    for (match in matches) {
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        total += when (match.groupValues[2]) {
            "ms" -> value / 1000
            "s" -> value
            "m" -> value * 60
            "h" -> value * 3600
            else -> value * 86400
        }
    }
    return total
}

// ms 必须排在 s 前面，否则 "539ms" 会被当成 "539s" 再剩一个 m。
private val DURATION_PATTERN = Regex("([0-9]*\\.?[0-9]+)(ms|s|m|h|d)")

/** v280：给用户看的等待时长，最多两级单位（"6 天 19 小时"、"2 小时 3 分"、"45 秒"）。 */
internal fun formatQuotaWait(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val days = total / 86400
    val hours = (total % 86400) / 3600
    val minutes = (total % 3600) / 60
    return when {
        days > 0 -> if (hours > 0) "$days 天 $hours 小时" else "$days 天"
        hours > 0 -> if (minutes > 0) "$hours 小时 $minutes 分" else "$hours 小时"
        minutes > 0 -> "$minutes 分"
        else -> "$total 秒"
    }
}

/**
 * Resolves what a `streamText` [EventSourceListener.onFailure] should close the SSE producer
 * with. Reading the error body ([readDetail], normally [stringSafe]) or parsing it
 * ([parseErrorMessage]) can itself throw - e.g. a truncated or aborted body - and OkHttp never
 * re-dispatches a signalled callback, so letting that escape here would strand the producer and
 * the collector would wait forever. Catching it and falling back to the underlying throwable (or
 * the read failure itself) guarantees the caller always has a cause to close with.
 */
private fun resolveStreamFailureCause(
    t: Throwable?,
    responseCode: Int?,
    json: Json,
    readDetail: () -> String?,
): Throwable {
    return try {
        val detail = readDetail()
        t ?: HttpException(
            parseErrorMessage(detail, json)
                ?: "Cloud Code Assist request failed: $responseCode $detail",
            statusCode = responseCode,
        )
    } catch (e: Throwable) {
        // android.util.Log is unmocked in JVM unit tests (throws instead of logging), so this
        // testable top-level function uses the Logging facade the rest of :app already relies on
        // for exactly that reason (e.g. ChatService.kt) rather than android.util.Log.
        Logging.log(TAG, "onFailure: failed to read error body, detail lost: ${e.javaClass.simpleName}: ${e.message}")
        t ?: e
    }
}

/**
 * Raise a thinking budget below Claude's floor up to that floor.
 *
 * Code Assist fronts Anthropic models as well as Gemini, and Anthropic rejects any request whose
 * thinking budget is under 1024 tokens. Gemini's own reasoning levels can ask for less (e.g.
 * [me.rerere.ai.core.ReasoningLevel.LOW] is 1000), so anything in 1..1023 is raised here; `0`
 * (reasoning off) and budgets already at or above the floor are left untouched, as is the
 * `thinkingLevel` string used by Gemini-3 models. Must run before
 * [raiseMaxTokensAboveThinkingBudget] so that function sees the clamped budget.
 */
private fun raiseThinkingBudgetToClaudeFloor(request: JsonObject): JsonObject {
    val config = request["generationConfig"] as? JsonObject ?: return request
    val thinkingConfig = config["thinkingConfig"] as? JsonObject ?: return request
    val budget = thinkingConfig["thinkingBudget"]?.jsonPrimitive?.intOrNull ?: return request
    if (budget !in 1..1023) return request
    val raisedThinkingConfig = JsonObject(
        thinkingConfig + ("thinkingBudget" to JsonPrimitive(CLAUDE_MIN_THINKING_BUDGET))
    )
    return JsonObject(request + ("generationConfig" to JsonObject(
        config + ("thinkingConfig" to raisedThinkingConfig)
    )))
}

// Anthropic's floor for `thinking.budget_tokens` on Claude models fronted by Code Assist.
private const val CLAUDE_MIN_THINKING_BUDGET = 1024

/**
 * Guarantee `maxOutputTokens` sits above the thinking budget.
 *
 * Code Assist fronts Anthropic models as well as Gemini, and those reject any request whose
 * thinking budget is not strictly below `max_tokens`. Gemini itself has no such rule, so the
 * shared wire builder does not enforce it and the ceiling is raised here instead. Leaving
 * `maxOutputTokens` unset is not safe either: the backend then applies a default that a high
 * reasoning level overshoots.
 */
private fun raiseMaxTokensAboveThinkingBudget(request: JsonObject): JsonObject {
    val config = request["generationConfig"] as? JsonObject ?: return request
    val budget = (config["thinkingConfig"] as? JsonObject)
        ?.get("thinkingBudget")?.jsonPrimitive?.intOrNull ?: return request
    if (budget <= 0) return request
    val maxTokens = config["maxOutputTokens"]?.jsonPrimitive?.intOrNull
    if (maxTokens != null && maxTokens > budget) return request
    val raised = JsonObject(
        config + ("maxOutputTokens" to JsonPrimitive(budget + THINKING_ANSWER_HEADROOM))
    )
    return JsonObject(request + ("generationConfig" to raised))
}

// Room for the answer itself once the thinking budget is spent.
private const val THINKING_ANSWER_HEADROOM = 8192

/**
 * v279：给每次生成请求生成 Antigravity 形状的 `requestId`。
 *
 * 官方客户端每发一次都换一个新 id，前缀固定 `agent-`，中间是毫秒时间戳，末尾是随机串
 * （badlogic/pi-mono 的 google-gemini-cli.ts：`agent-${Date.now()}-${随机}`）。参数留成
 * 可注入的，纯粹是为了让单元测试能断言格式而不依赖真实时钟与随机源。
 */
internal fun newAntigravityRequestId(
    nowMillis: Long = System.currentTimeMillis(),
    random: Random = Random.Default,
): String {
    val suffix = buildString {
        repeat(REQUEST_ID_SUFFIX_LENGTH) {
            append(REQUEST_ID_ALPHABET[random.nextInt(REQUEST_ID_ALPHABET.length)])
        }
    }
    return "agent-$nowMillis-$suffix"
}

private const val REQUEST_ID_SUFFIX_LENGTH = 9
private const val REQUEST_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/**
 * v279：把内层 Gemini 请求体补成 Antigravity 的形状。
 *
 * 两处差异都是 Cloud Code Assist 用来判断「这是不是官方 agent 流量」的：`sessionId` 必须在
 * 同一会话内保持不变（随机值会破坏提示缓存亲和，router-for-me/CLIProxyAPI issue #592），
 * 而 `systemInstruction` 必须带 `role: "user"`（badlogic/pi-mono PR #571）。会话 id 由首条
 * 用户消息派生，因此同一对话里每一轮都算出同一个值，换对话自然换新值 —— 不需要额外存状态。
 */
internal fun withAntigravityRequestShape(
    request: JsonObject,
    messages: List<UIMessage>,
): JsonObject = withSystemInstructionRole(
    withStableSessionId(
        resolveAntigravityToolShape(
            stripBuiltInSearchTool(request),
            messages,
        ),
        messages,
    )
)

/**
 * v310 起：本函数之前会先跑 [stripBuiltInSearchTool] —— 谷歌搜索已改走独立请求
 * （GeminiSearchClient），主请求里不会再出现 googleSearch；下面「保内置搜索 / 摘客户端工具」
 * 两条分支现在只对 urlContext / codeExecution 这类**残留**内置键生效，属纵深防御。
 *
 * v309：这条通道上「内置工具」与「客户端函数工具」**不能同请求共存**，二选一并一次算完
 * tools 与 toolConfig（刻意不做成「一个函数删、另一个函数再加」——那样后者在真实链路上
 * 永远不可达，是「看着在保护、其实不执行」的假象，审查位抓过）。
 *
 * 依据（三家独立实测 + 用户真机两轮，不是推测）：
 * - 用户真机：v308 补上 camelCase flag + `functionCallingConfig.mode=VALIDATED` 之后，
 *   400 原样不动（换 gemini-3.1-pro / gemini-3.8-flash 都一样）。
 * - Antigravity-Manager 源码注释：「v1internal (cloudcode-pa) does NOT support mixing
 *   googleSearch with functionDeclarations」，最终做法是有 functionDeclarations 时不注入 googleSearch。
 * - opencode-antigravity-auth 规范：「googleSearch 与 urlContext **不能**与 functionDeclarations 同请求组合」。
 * - OmniRoute 试过「加 flag + 诱饵工具」，v3.8.49 又整体回滚。
 *
 * 二选一的取舍规则（两条分支都有明确理由）：
 * 1. **默认保内置搜索、摘客户端工具** —— 用户诉求就是「我要用谷歌自带搜索」。
 *    摘完的形状与官方 gemini-cli 的独立 web-search 调用一致（只带内置工具、无 toolConfig），
 *    那是已知可工作的形态。
 * 2. **历史里已出现过客户端工具调用时反过来：保工具、丢内置搜索。**
 *    因为 `GoogleProvider.buildContents` 会把历史中的 functionCall / functionResponse
 *    照原样回传（本通道无过滤），「有调用记录、没声明」是不一致形状，后果没人验证过；
 *    把一段正在用的对话弄坏，比这一轮搜不到更糟。
 *
 * 🔴 实现细节两条：
 * - 必须**逐对象删 functionDeclarations 键**，不能只按条目过滤 —— 自定义请求体可以把两者
 *   塞进同一个 tool 对象，按条目过滤会把整个对象保留（工具没摘掉、toolConfig 却被删了），
 *   修复静默失效。
 * - 内置工具按**已知键白名单**识别（[ANTIGRAVITY_BUILT_IN_TOOL_KEYS]），不用「任意非
 *   functionDeclarations 的键」—— 后者会让 customBody 里的杂项键误触发摘除客户端工具。
 *
 * 未混发的请求（没有内置工具，或没有函数工具）一个字节不动：上游只在真混发时才写
 * toolConfig，所以这里也不需要额外表态。曾试过「只要带 functionDeclarations 就补
 * flag + VALIDATED」，被审查位反证推翻：若服务端真对所有 agent 流量要求这个 flag，
 * 今天不带它的普通工具调用早就全部 400 —— 实际没有；而 OmniRoute 那条旁证又被它自己的
 * tool-cloak（注入 21 个合成 functionDeclarations）污染。
 *
 * 刻意**不做** camelCase + snake_case 双写：ProtoJSON 规范原文
 * 「A conforming ProtoJSON serializer MUST NOT emit duplicate keys」
 * 「parse failure on duplicate alternate spellings」，Java JsonFormat 更直接抛
 * 「Field X has already been set」—— 双写有把 400 换成另一种 400 的实际风险。
 * 报错文案里的 snake_case 只是服务端按 .proto 字段名渲染消息路径，不代表它只认下划线
 * （vercel/ai #14655 的日志就是路径 snake_case、被拒的键 camelCase）。
 */
internal fun resolveAntigravityToolShape(
    request: JsonObject,
    messages: List<UIMessage>,
): JsonObject {
    val tools = request["tools"] as? JsonArray ?: return request
    val functionCount = tools.flatMap { tool ->
        ((tool as? JsonObject)?.get("functionDeclarations") as? JsonArray).orEmpty()
    }.size
    val builtInKeys = tools.flatMap { tool ->
        ((tool as? JsonObject)?.keys ?: emptySet()).filter { it in ANTIGRAVITY_BUILT_IN_TOOL_KEYS }
    }.distinct()
    if (functionCount == 0 || builtInKeys.isEmpty()) return request

    val historyHasToolCalls = messages.any { message ->
        message.parts.any { it is UIMessagePart.Tool }
    }
    val keepFunctions = historyHasToolCalls
    if (keepFunctions) {
        Logging.log(
            TAG,
            "内置工具与客户端工具在这条通道上互斥，且本对话历史里已有工具调用记录：" +
                "本轮保留 $functionCount 个客户端工具、不启用内置工具（新建对话即可用）"
        )
    } else {
        Logging.log(
            TAG,
            "内置工具与客户端工具在这条通道上互斥：本轮已摘掉 $functionCount 个客户端工具，" +
                "保留内置工具"
        )
    }
    val kept = tools.mapNotNull { tool ->
        val obj = tool as? JsonObject ?: return@mapNotNull null
        val rest = if (keepFunctions) {
            obj.filterKeys { it !in ANTIGRAVITY_BUILT_IN_TOOL_KEYS }
        } else {
            obj.filterKeys { it != "functionDeclarations" }
        }
        if (rest.isEmpty()) null else JsonObject(rest)
    }
    // 两种形状都不带 toolConfig：混用表态已被证明无效（v308 真机），而单用一类工具本来就不需要它。
    return JsonObject(request - "toolConfig" + ("tools" to JsonArray(kept)))
}

/**
 * v310：把「内置搜索」从主请求里摘掉 —— 它已改由独立搜索请求承担（GeminiSearchClient）。
 *
 * 为什么必须摘：这条通道上 googleSearch 与函数工具同请求必 400（三家实测 + 两轮真机），
 * v308（补开关）与 v309（二选一）都只能缓解；官方 gemini-cli 与反代项目的共同做法是把
 * 搜索拆成独立请求，所以主请求里从此不该再出现 googleSearch。
 *
 * 边界（刻意保守）：
 * - 只在本轮真的会「混发」（有函数工具 + 有 googleSearch）时才摘；纯搜索请求原样保留；
 * - 只动 googleSearch 这一个键；urlContext / codeExecution 与 customBody 的其它内容一律不碰；
 * - 没有 googleSearch 的请求「一个字节不动」；
 * - 删完后若不再有任何内置工具残留，顺手摘掉配套的 `includeServerSideToolInvocations`
 *   开关（避免留下「只有函数工具 + 服务端开关」这种没人验证过的形状）；toolConfig 的
 *   其它内容（例如用户自定义的 functionCallingConfig）原样保留。
 */
internal fun stripBuiltInSearchTool(request: JsonObject): JsonObject {
    val tools = request["tools"] as? JsonArray ?: return request
    // v310：只有「真会混发」才摘 —— googleSearch 与函数工具同请求必 400；
    // 纯搜索请求（没有任何函数工具）原样保留，让圆桌/子代理等没有替代搜索工具的路径
    // 继续能靠内置搜索工作。
    val functionCount = tools.flatMap { tool ->
        ((tool as? JsonObject)?.get("functionDeclarations") as? JsonArray).orEmpty()
    }.size
    if (functionCount == 0) return request
    var removed = 0
    val kept = tools.mapNotNull { tool ->
        val obj = tool as? JsonObject ?: return@mapNotNull tool
        if (!obj.containsKey("googleSearch")) return@mapNotNull tool
        removed += 1
        val rest = obj.filterKeys { it != "googleSearch" }
        if (rest.isEmpty()) null else JsonObject(rest)
    }
    if (removed == 0) return request
    val result = request.toMutableMap()
    if (kept.isEmpty()) result.remove("tools") else result["tools"] = JsonArray(kept)
    val hasRemainingBuiltIn = kept.any { tool ->
        (tool as? JsonObject)?.keys?.any { it in ANTIGRAVITY_BUILT_IN_TOOL_KEYS } == true
    }
    if (!hasRemainingBuiltIn) {
        val config = result["toolConfig"] as? JsonObject
        if (config != null) {
            val pruned = JsonObject(config - "includeServerSideToolInvocations")
            if (pruned.isEmpty()) result.remove("toolConfig") else result["toolConfig"] = pruned
        }
    }
    Logging.log(
        TAG,
        "v310：已把谷歌内置搜索移出主请求（改走独立搜索），本轮 googleSearch 出现 $removed 次"
    )
    return JsonObject(result)
}

/** Cloud Code Assist 认识的内置工具键（v1internal 原生形态，见 CLIProxyAPI 的 antigravity 翻译层）。 */
private val ANTIGRAVITY_BUILT_IN_TOOL_KEYS = setOf("googleSearch", "urlContext", "codeExecution")

/**
 * v309：把「这次到底发了什么工具配置」记进 App 内日志页（设置 → 日志）。
 *
 * 为什么必须记：Cloud Code Assist 的报错只给一句 `status=INVALID_ARGUMENT`、**没有字段级
 * 说明**，而这条通道用的是「干净 OkHttpClient」，没挂 RequestLoggingInterceptor，
 * 所以打开请求日志也抓不到 —— 前两轮只能靠猜。这里只记**结构与工具名**，
 * 不记对话内容、不记密钥、不记 token，用户复制一行就能让下一次定位变成确定的事。
 */
internal fun logAntigravityToolShape(request: JsonObject, modelId: String) {
    antigravityToolShapeLog(request, modelId)?.let { Logging.log(TAG, it) }
}

/**
 * 纯函数版：把上面那段结构摘要算出来（不写日志），单测可以直接断言它
 * 「只含结构、不含对话内容」。返回 null 表示这次请求没有任何工具、不值得记。
 */
internal fun antigravityToolShapeLog(request: JsonObject, modelId: String): String? {
    val tools = request["tools"] as? JsonArray
    val hasFunctionTools = tools?.any { (it as? JsonObject)?.containsKey("functionDeclarations") == true } == true
    val builtIns = tools?.flatMap { tool ->
        ((tool as? JsonObject)?.keys ?: emptySet()).filter { it != "functionDeclarations" }
    }?.distinct().orEmpty()
    if (!hasFunctionTools && builtIns.isEmpty()) return null
    val functionNames = tools?.flatMap { tool ->
        ((tool as? JsonObject)?.get("functionDeclarations") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }.orEmpty()
    val toolConfig = request["toolConfig"] as? JsonObject
    val mode = (toolConfig?.get("functionCallingConfig") as? JsonObject)
        ?.get("mode")?.jsonPrimitive?.contentOrNull ?: "-"
    // 可空链的结果要先落成非空值再 ifEmpty —— 直接在 String? 上调 ifEmpty 编译不过。
    val configKeys = toolConfig?.keys?.sorted()?.joinToString("/").orEmpty().ifEmpty { "无" }
    val toolNames = functionNames.take(12).joinToString(",").ifEmpty { "-" }
    return "Antigravity 请求形状 model=$modelId " +
        "functionDeclarations=$hasFunctionTools 内置工具=${builtIns.ifEmpty { listOf("无") }} " +
        "toolConfig=$configKeys mode=$mode 工具名=$toolNames"
}

internal fun withStableSessionId(
    request: JsonObject,
    messages: List<UIMessage>,
): JsonObject {
    if (request.containsKey("sessionId")) return request
    val seed = messages.firstOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("") { it.text }
        ?.takeIf { it.isNotBlank() }
        ?: return request
    return JsonObject(request + ("sessionId" to JsonPrimitive(stableSessionId(seed))))
}

/**
 * v279：SHA-256 取前 8 字节大端、掩掉符号位，再前置一个 `-` —— 与 CLIProxyAPI 的
 * generateStableSessionID 同一算法，这样同一段种子文本在两端算出同一个 id。
 */
internal fun stableSessionId(seed: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (digest[index].toLong() and 0xFF)
    }
    return "-" + (value and 0x7FFFFFFFFFFFFFFFL)
}

internal fun withSystemInstructionRole(request: JsonObject): JsonObject {
    val instruction = request["systemInstruction"] as? JsonObject ?: return request
    if (instruction.containsKey("role")) return request
    return JsonObject(
        request + ("systemInstruction" to JsonObject(
            instruction + ("role" to JsonPrimitive("user"))
        ))
    )
}
