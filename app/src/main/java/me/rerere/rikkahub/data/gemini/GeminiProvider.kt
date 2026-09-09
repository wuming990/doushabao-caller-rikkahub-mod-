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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
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
                )
            )
        }
        val request = Request.Builder()
            .url("${GeminiAccountRepository.DAILY_CODE_ASSIST_ENDPOINT}/v1internal:streamGenerateContent?alt=sse")
            .headers(params.customHeaders.toHeaders())
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

    private fun startNew(kind: OpenKind): String {
        openKind = kind
        openId = "gemini-${nextId++}"
        return openId
    }

    fun translate(chunk: MessageChunk): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        val choice = chunk.choices.getOrNull(0)
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

    /** Sent once, right before the underlying event source closes normally. */
    fun finish(): StreamChunk = StreamChunk.Finish()
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
): JsonObject = withSystemInstructionRole(withStableSessionId(request, messages))

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
