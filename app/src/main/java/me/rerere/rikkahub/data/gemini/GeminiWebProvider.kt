package me.rerere.rikkahub.data.gemini

import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.mergeCustomHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v323：Gemini 网页版（cookie 直连 gemini.google.com）。
 *
 * 协议**照抄 HanaokaYuzu/Gemini-API（3.4k★，2026-08 仍在维护）的 81 槽实现**，
 * 不是自己发明的格式：
 * - `f.req` = `[null, "<81 槽 inner json>"]`，prompt 放在 `inner[0]`；
 * - 模型选择走请求头 `x-goog-ext-525001261-jspb`，不在 body 里；
 * - 必须带 `at`（页面里的 SNlM0e），缺了带 cookie 会全部 400；
 * - 响应是 `)]}'` + “UTF-16 长度行 + JSON 行”，正文在 `payload[4][0][1][0]`。
 *
 * 注意：这是非公开接口。Google 随时可能改协议；封禁/限流风险由使用者承担。
 */
class GeminiWebProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : Provider<ProviderSetting.GeminiWeb> {

    companion object {
        private const val TAG = "GeminiWeb"
        private const val ORIGIN = "https://gemini.google.com"
        private const val APP_URL = "$ORIGIN/app"
        private const val STREAM_URL =
            "$ORIGIN/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
        private const val FALLBACK_BL = "boq_assistant-bard-web-server_20260525.09_p0"
        private const val MODEL_HEADER = "x-goog-ext-525001261-jspb"
        private const val UUID_HEADER = "x-goog-ext-525005358-jspb"

        /** v1016：表单类型只留一处定义，别出现两个写法不一致的副本。 */
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=utf-8"

        private data class WebModel(
            val modelId: String,
            val displayName: String,
            val hash: String,
            val number: Int,
        )

        /** hash 来自公开逆向目录；模型编号用于 inner[79] 与 header 末位，必须同源。 */
        private val WEB_MODELS = listOf(
            WebModel("gemini-3.1-pro", "Gemini 3.1 Pro（网页）", "9d8ca3786ebdfbea", 1),
            WebModel("gemini-3.8-flash", "Gemini 3.8 Flash（网页）", "56fdd199312815e2", 2),
            WebModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite（网页）", "cf41b0e0dd7d53e5", 3),
        )

        private fun modelOf(modelId: String): WebModel =
            WEB_MODELS.firstOrNull { it.modelId == modelId } ?: WEB_MODELS.first()
    }

    override suspend fun listModels(providerSetting: ProviderSetting.GeminiWeb): List<Model> =
        WEB_MODELS.map { model ->
            Model(
                modelId = model.modelId,
                displayName = model.displayName,
                contextLength = 1_000_000,
            )
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val handler = StreamChunkHandler(params.model)
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = handler.handle(collected, chunk)
        }
        return TextGenerationResult(
            id = "",
            model = params.model.modelId,
            message = collected.last(),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiWeb,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val rawCookie = providerSetting.cookie.trim()
        if (rawCookie.isBlank()) {
            error("Gemini 网页版还没有登录。请到供应商设置里点「一键登录 Google」，或手动粘贴 gemini.google.com 的 Cookie。")
        }
        // v1007：粘贴进来的可能是一整段请求头 / 一段 JSON / 换行分隔的键值，
        // 统一挑出 PSID / PSIDTS / SAPISID 再用；挑不出来就按原样送，让服务端报错更直观。
        val cookie = normalizeGeminiWebCookie(rawCookie) ?: rawCookie

        val prompt = buildPrompt(messages)
        val webModel = modelOf(params.model.modelId)
        val session = fetchSession(providerSetting, cookie)
        if (session.at.isBlank()) {
            error("Cookie 里没有登录态（读不到 SNlM0e）。点「一键登录 Google」重新抓一次，或确认粘贴的是已登录 gemini.google.com 的完整 Cookie。")
        }

        val requestUuid = UUID.randomUUID().toString()
        val fReq = buildFReq(prompt, webModel, requestUuid)

        val body = buildString {
            append("at=").append(java.net.URLEncoder.encode(session.at, "UTF-8"))
            append("&f.req=").append(java.net.URLEncoder.encode(fReq, "UTF-8"))
        }

        val url = buildString {
            append(STREAM_URL)
            append("?hl=zh-CN&_reqid=").append((100000..999999).random()).append("&rt=c")
            append("&bl=").append(java.net.URLEncoder.encode(session.buildLabel, "UTF-8"))
            if (session.sid.isNotBlank()) append("&f.sid=").append(session.sid)
        }

        val builder = Request.Builder()
            .url(url)
            .header("content-type", FORM_CONTENT_TYPE)
            .header("origin", ORIGIN)
            .header("referer", "$ORIGIN/")
            .header("x-same-domain", "1")
            // v1016：浏览器标识必须与登录这套 Cookie 时用的那个一致，否则 Google 会判定
            // 会话被搬到了别的浏览器上。登录页抓出来的时候会一起回传 userAgent；
            // 手动粘贴的用户留空即可，行为与旧版完全相同。
            .header("user-agent", userAgentOf(providerSetting))
            .header("cookie", cookie)
            .header(
                MODEL_HEADER,
                """[1,null,null,null,"${webModel.hash}",null,null,0,[4,5,6,8],null,null,1,null,null,${webModel.number}]""",
            )
            .header(UUID_HEADER, """["$requestUuid",1]""")
        sapisidHash(cookie)?.let { builder.header("authorization", it) }
        val merged = providerSetting.mergeCustomHeaders(params.customHeaders)
        for (index in 0 until merged.size) {
            builder.header(merged.name(index), merged.value(index))
        }

        Log.i(
            TAG,
            "streamText model=${params.model.modelId} bl=${session.buildLabel} at=${session.at.length} cookie=${cookie.length}",
        )

        val response = client.newCall(builder.post(body.toRequestBody(FORM_MEDIA)).build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty().take(2000)
                Log.e(TAG, "HTTP ${resp.code}: $text")
                when (resp.code) {
                    401, 403 -> error("Gemini 网页版拒绝访问（${resp.code}）。登录态已过期，或与登录时的浏览器标识不一致：点「一键登录 Google」重抓一次即可（手动粘贴的 Cookie 请重新登录后再复制）。")
                    429 -> error("Gemini 网页版触发限流（429）。等几分钟再试，或换一个账号。")
                    else -> error("Gemini 网页版返回 ${resp.code}：${text.take(300)}")
                }
            }

            val source = resp.body?.source() ?: error("Gemini 网页版返回空内容")
            val textId = "gemini-web"
            var emitted = false
            var sawAnyWrb = false
            var errorText: String? = null

            while (true) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim().removePrefix(")]}'").trim()
                if (trimmed.isEmpty() || trimmed.all { it.isDigit() }) continue
                val root = runCatching { json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
                    ?: continue
                root.forEach { item ->
                    val row = item as? JsonArray ?: return@forEach
                    if (row.getOrNull(0)?.toString()?.contains("wrb.fr") == true) sawAnyWrb = true
                    val payloadRaw = row.getOrNull(2)?.let { part ->
                        runCatching { part.jsonPrimitive.content }.getOrNull()
                    } ?: return@forEach
                    val payload = runCatching { json.parseToJsonElement(payloadRaw) as? JsonArray }
                        .getOrNull() ?: return@forEach

                    val code = payload.getOrNull(5)?.toString()?.toIntOrNull()
                    if (code != null && code != 0) {
                        errorText = "Gemini 网页版错误码 $code"
                    }

                    val candidates = payload.getOrNull(4) as? JsonArray ?: return@forEach
                    candidates.forEach { candidate ->
                        val candidateArray = candidate as? JsonArray ?: return@forEach
                        val parts = candidateArray.getOrNull(1) as? JsonArray ?: return@forEach
                        val text = parts.getOrNull(0)?.let { part ->
                            runCatching { part.jsonPrimitive.content }.getOrNull()
                        } ?: return@forEach
                        if (text.isNotEmpty()) {
                            emitted = true
                            emit(StreamChunk.TextDelta(id = textId, text = text))
                        }
                    }
                }
            }

            if (!emitted) {
                error(
                    errorText ?: if (!sawAnyWrb) {
                        "Gemini 网页版没有返回可解析内容（协议可能已变更，或 Cookie 已失效）"
                    } else {
                        "Gemini 网页版返回了空回复"
                    }
                )
            }
            emit(StreamChunk.Finish(finishReason = "stop", model = params.model.modelId))
        }
    }.flowOn(Dispatchers.IO)

    private data class WebSession(val at: String, val buildLabel: String, val sid: String)

    /** 从 /app 页面取 at（SNlM0e）、bl（cfb2h）、f.sid（FdrFJe）。正则与 Gemini-API 一致。 */
    private fun fetchSession(providerSetting: ProviderSetting.GeminiWeb, cookie: String): WebSession {
        val builder = Request.Builder()
            .url(APP_URL)
            .header("cookie", cookie)
            .header("user-agent", userAgentOf(providerSetting))
            .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        sapisidHash(cookie)?.let { builder.header("authorization", it) }
        val html = runCatching {
            client.newCall(builder.get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")

        fun pick(key: String): String =
            Regex(""""$key"\s*:\s*"(.*?)"""").find(html)?.groupValues?.get(1).orEmpty()

        val bl = providerSetting.buildLabel.trim()
            .ifBlank { pick("cfb2h") }
            .ifBlank { Regex("""boq_assistant-bard-web-server_[\d.]+_p\d+""").find(html)?.value.orEmpty() }
            .ifBlank { FALLBACK_BL }
        return WebSession(
            at = pick("SNlM0e"),
            buildLabel = bl,
            sid = pick("FdrFJe"),
        )
    }

    /**
     * v1016：这套 Cookie 应该用哪个浏览器标识发请求。
     *
     * 留空 = 沿用内置的桌面 Chrome UA，老配置和"手动粘贴 Cookie"的行为一点不变；
     * 一键登录会把它抓取时真正用的那个 UA 一起回传存进来 —— Cookie 和标识同源，
     * 才不会出现"Cookie 看着没问题、一发就 403"。
     */
    private fun userAgentOf(providerSetting: ProviderSetting.GeminiWeb): String =
        providerSetting.userAgent.trim().ifBlank { USER_AGENT }

    /** 与 Gemini-API/gpt4free 同一算法：sha1("<epoch> <SAPISID> <origin>")。 */
    private fun sapisidHash(cookie: String): String? {
        val sapisid = cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val epoch = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$epoch $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${epoch}_$digest"
    }

    /** 81 槽结构照抄 Gemini-API：缺槽位 Google 是宽容的，但 0/1/2/6/7/10/11/17/18/27/30/41/68/79 不能少。 */
    private fun buildFReq(prompt: String, model: WebModel, requestUuid: String): String {
        val inner = arrayOfNulls<JsonElement>(81)
        inner[0] = buildJsonArray {
            add(JsonPrimitive(prompt))
            add(0)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(0)
        }
        inner[1] = buildJsonArray { add(JsonPrimitive("zh-CN")) }
        inner[2] = buildJsonArray {
            add(""); add(""); add("")
            add(JsonNull); add(JsonNull); add(JsonNull)
            add(JsonNull); add(JsonNull); add(JsonNull)
            add("")
        }
        inner[4] = JsonPrimitive(requestUuid.replace("-", ""))
        inner[6] = buildJsonArray { add(1) }
        inner[7] = JsonPrimitive(1)
        inner[10] = JsonPrimitive(1)
        inner[11] = JsonPrimitive(0)
        inner[17] = buildJsonArray { add(buildJsonArray { add(0) }) }
        inner[18] = JsonPrimitive(0)
        inner[27] = JsonPrimitive(1)
        inner[30] = buildJsonArray { add(4) }
        inner[41] = buildJsonArray { add(1) }
        inner[59] = JsonPrimitive(requestUuid)
        inner[61] = buildJsonArray { }
        inner[68] = JsonPrimitive(1)
        inner[79] = JsonPrimitive(model.number)
        inner[80] = JsonPrimitive(1)

        val filled = inner.map { it ?: JsonNull }
        return buildJsonArray {
            add(JsonNull)
            add(JsonPrimitive(JsonArray(filled).toString()))
        }.toString()
    }

    /** 网页接口只收单条 prompt，把历史折叠成文本（Gemini-API 也是这么处理本地历史）。 */
    private fun buildPrompt(messages: List<UIMessage>): String {
        if (messages.size == 1) {
            return messages.first().parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { error("消息内容为空") }
        }
        return buildString {
            messages.forEach { message ->
                val text = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                if (text.isBlank()) return@forEach
                when (message.role) {
                    MessageRole.USER -> append("User: ")
                    MessageRole.ASSISTANT -> append("Assistant: ")
                    else -> append("System: ")
                }
                append(text).append("\n\n")
            }
            append("Assistant:")
        }.trim()
    }
}

private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

/**
 * v1007：把用户粘贴的东西统一成可用的 Cookie 头。
 *
 * 用户拿到 Cookie 的方式很杂，粘贴进来的形态也不一样：
 * - 完整请求头：`Cookie: __Secure-1PSID=...; NID=...; ...`；
 * - 开发者工具里复制的 JSON：`{"__Secure-1PSID":"...","__Secure-1PSIDTS":"..."}`；
 * - 换行分隔的若干行。
 *
 * 这里只挑出登录真正需要的三个键，其余（NID / AEC / __Secure-3PSID 等）**故意丢掉**：
 * 少送无关键不影响登录，反而少一点指纹。找不到 `__Secure-1PSID` 说明粘的东西不对，返回 null
 * 让界面直接提示用户，而不是发出去换一个看不懂的 400。
 */
internal fun normalizeGeminiWebCookie(raw: String): String? {
    if (raw.isBlank()) return null
    val wanted = listOf("__Secure-1PSID", "__Secure-1PSIDTS", "SAPISID")
    val picked = LinkedHashMap<String, String>()
    for (key in wanted) {
        // 键后面必须紧跟 = 或 :（JSON 里还有引号），前面不能是字母/数字/下划线/短横。
        // 左边界不是洁癖：Google 的 Cookie 里还有 __Secure-1PSAPISID / __Secure-3PSAPISID，
        // 它们**字面上就含** "SAPISID=" —— 不加边界会把它们的值当成 SAPISID 抓走，
        // 于是界面提示「已填入」，实际 SAPISIDHASH 用错值、请求 401/403（审查位抓到）。
        val pattern = Regex(
            """(?<![\w\-])["']?""" + Regex.escape(key) +
                """["']?\s*[:=]\s*["']?([^"';,\r\n}]+)"""
        )
        val matched = pattern.find(raw) ?: continue
        val value = matched.groupValues[1].trim()
        if (value.isNotEmpty()) picked[key] = value
    }
    if (!picked.containsKey("__Secure-1PSID")) return null
    return picked.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

