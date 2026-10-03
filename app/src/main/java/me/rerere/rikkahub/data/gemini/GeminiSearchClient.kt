package me.rerere.rikkahub.data.gemini

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.common.android.Logging
import me.rerere.common.http.await
import me.rerere.rikkahub.data.ai.MAX_TOOL_OUTPUT_CHARS
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiSearchClient"
private val SEARCH_JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * v310：谷歌账号通道的「独立搜索」客户端。
 *
 * 为什么需要它：这条通道（cloudcode-pa / v1internal）上，「内置搜索（googleSearch）」与
 * 「客户端函数工具」不能出现在同一次请求里（混发必 400；本项目两轮真机 + 三家开源实测）。
 * 官方 gemini-cli 与开源反代（CLIProxyAPI）的共同做法是：**把搜索拆成一次独立请求**——
 * 请求里只带 `tools:[{googleSearch:{}}]`、不带任何函数工具，取回「摘要 + 来源」再回填主对话。
 * 本类就是那次独立请求，形状照抄官方 gemini-cli 的 web-search 实现：
 * - 端点：`v1internal:generateContent`（非流式；官方搜索走的就是非流式）
 * - 两种信封都试：PLAIN（官方 gemini-cli 用法）→ AGENT_ENVELOPE（本 App 流式通道的自报形状）
 * - 模型候选：gemini-3-flash-preview → gemini-2.5-flash（官方用 flash 系跑搜索，不跟随用户模型）
 * 第一个成功的组合会被记在内存里，后续搜索直接先试它。
 *
 * 安全/隐私：日志只记模型名、变体名与 HTTP 错误片段，**不记搜索词、不记响应正文**。
 */
class GeminiSearchClient(
    private val repository: GeminiAccountRepository,
    baseClient: OkHttpClient,
    private val json: Json,
) {
    // 搜索是「快问快答」：复用 gemini 专用干净客户端，仅对这条请求加总超时。
    private val client: OkHttpClient = baseClient.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var preferred: Pair<String, SearchRequestVariant>? = null

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val target = query.trim()
        if (target.isEmpty()) return@withContext "Error: the search query is empty."

        val account = try {
            repository.acquireAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logging.log(TAG, "search aborted: no usable account (${e.message})")
            return@withContext "Error: Google search is unavailable right now (account error: ${e.message})."
        }

        var lastError = "unknown error"
        for ((model, variant) in buildSearchAttemptOrder(preferred)) {
            val attemptResult = attempt(account, target, model, variant)
            if (attemptResult.isSuccess) {
                preferred = model to variant
                return@withContext attemptResult.getOrThrow()
            }
            lastError = attemptResult.exceptionOrNull()?.message ?: "unknown error"
            Logging.log(TAG, "search attempt failed model=$model variant=$variant: ${lastError.take(120)}")
        }
        "Error: Google search failed after trying every known request shape. $lastError"
    }

    private suspend fun attempt(
        account: GeminiAccount,
        query: String,
        model: String,
        variant: SearchRequestVariant,
    ): Result<String> = try {
        val payload = json.encodeToString(
            buildSearchRequestBody(model, account.projectId, query, variant)
        )
        val request = Request.Builder()
            .url("${GeminiAccountRepository.DAILY_CODE_ASSIST_ENDPOINT}/v1internal:generateContent")
            .antigravityHeaders(account.accessToken)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(SEARCH_JSON_MEDIA_TYPE))
            .build()
        val response = client.newCall(request).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("HTTP ${response.code}: ${body.take(300)}")
        }
        val outcome = parseGeminiSearchResponse(json, body)
            ?: error("the response did not look like a search result: ${body.take(300)}")
        Result.success(formatGeminiSearchOutput(query, outcome))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * 搜索请求的两种信封。
 *
 * - [PLAIN]：官方 gemini-cli 的用法（顶层只放 model / project / request）。
 * - [AGENT_ENVELOPE]：本 App 流式通道使用的「自报 agent 流量」形状（requestType / userAgent /
 *   requestId）。先试 PLAIN，不通过再试这个；成功的组合会被记住。
 */
internal enum class SearchRequestVariant { PLAIN, AGENT_ENVELOPE }

/** 搜索专用模型候选（照官方 gemini-cli：搜索走 flash 系，不用用户当前的对话模型）。 */
internal val SEARCH_MODEL_CANDIDATES = listOf("gemini-3-flash-preview", "gemini-2.5-flash")

/** 先试上次成功的组合，再按固定顺序把其余组合试完。 */
internal fun buildSearchAttemptOrder(
    preferred: Pair<String, SearchRequestVariant>?,
): List<Pair<String, SearchRequestVariant>> {
    val all = SEARCH_MODEL_CANDIDATES.flatMap { model ->
        SearchRequestVariant.entries.map { variant -> model to variant }
    }
    return preferred?.let { first -> listOf(first) + all.filter { it != first } } ?: all
}

internal data class GeminiSearchSource(val title: String, val uri: String)

internal data class GeminiSearchOutcome(
    val text: String,
    val sources: List<GeminiSearchSource>,
)

/**
 * 纯函数：把一次搜索请求的响应体解析成「正文 + 来源」。
 *
 * 兼容两种外壳：`{ response: { candidates… }, traceId }`（Code Assist 常见）或直接
 * `{ candidates… }`。解析不出 candidates（例如错误体、空响应）返回 null —— 调用方据此换下一种组合。
 */
internal fun parseGeminiSearchResponse(json: Json, raw: String): GeminiSearchOutcome? {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val inner = (root["response"] as? JsonObject) ?: root
    val candidate = (inner["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        ?: return null
    val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
    val text = parts?.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        val isThought = (obj["thought"] as? JsonPrimitive)?.booleanOrNull == true
        if (isThought) null else (obj["text"] as? JsonPrimitive)?.contentOrNull
    }?.joinToString("").orEmpty()
    val chunks = (candidate["groundingMetadata"] as? JsonObject)
        ?.get("groundingChunks") as? JsonArray
    val sources = chunks?.mapNotNull { chunk ->
        val web = (chunk as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
        GeminiSearchSource(
            title = (web["title"] as? JsonPrimitive)?.contentOrNull ?: "Untitled",
            uri = (web["uri"] as? JsonPrimitive)?.contentOrNull ?: "No URI",
        )
    }.orEmpty()
    return GeminiSearchOutcome(text = text, sources = sources)
}

/**
 * 纯函数：把搜索结果渲染成回填给主对话的文本（照官方 gemini-cli 的格式）：
 * `Web search results for "<query>":` + 摘要正文 + `Sources:` 列表。
 * 超过工具输出上限时截断（与其它工具一致，防止一次搜索把上下文撑爆）。
 */
internal fun formatGeminiSearchOutput(query: String, outcome: GeminiSearchOutcome): String {
    val body = outcome.text.ifBlank { "No search results or information found for the query." }
    val builder = StringBuilder()
    builder.append("Web search results for \"").append(query).append("\":\n\n").append(body)
    if (outcome.sources.isNotEmpty()) {
        builder.append("\n\nSources:\n")
        outcome.sources.forEachIndexed { index, source ->
            builder.append('[').append(index + 1).append("] ")
                .append(source.title).append(" (").append(source.uri).append(")\n")
        }
    }
    val rendered = builder.toString().trimEnd()
    return if (rendered.length <= MAX_TOOL_OUTPUT_CHARS) {
        rendered
    } else {
        rendered.take(MAX_TOOL_OUTPUT_CHARS) + "\n…[truncated]"
    }
}

/**
 * 纯函数：构造一次搜索请求的请求体。搜索请求**只允许**带 `googleSearch` 一个工具，
 * 绝不允许出现 functionDeclarations（那正是这条通道 400 的成因）。
 */
internal fun buildSearchRequestBody(
    model: String,
    projectId: String,
    query: String,
    variant: SearchRequestVariant,
): JsonObject {
    val inner = buildJsonObject {
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", query) })
                            }
                        )
                    }
                )
            }
        )
        put(
            "tools",
            buildJsonArray {
                add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
            }
        )
        put(
            "generationConfig",
            buildJsonObject {
                put("temperature", 0)
                put("topP", 1)
            }
        )
    }
    return buildJsonObject {
        if (variant == SearchRequestVariant.AGENT_ENVELOPE) {
            put("project", projectId)
            put("model", model)
            put("requestType", "agent")
            put("userAgent", "antigravity")
            put("requestId", newAntigravityRequestId())
        } else {
            put("model", model)
            put("project", projectId)
        }
        put("request", inner)
    }
}
