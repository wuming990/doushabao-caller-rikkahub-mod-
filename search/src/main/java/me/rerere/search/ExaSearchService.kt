package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    private const val MAX_EVIDENCE_TEXT_CHARACTERS = 8_000
    private const val MAX_EVIDENCE_HIGHLIGHT_CHARACTERS = 1_200
    private const val MIN_MAX_AGE_HOURS = -1
    private const val MAX_MAX_AGE_HOURS = 720
    override val name: String = "Exa"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://dashboard.exa.ai/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
                put("startPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date lower bound; results are published after this date")
                })
                put("endPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date upper bound; results are published before this date")
                })
                put("includeDomains", domainArraySchema("Optional domains to include"))
                put("excludeDomains", domainArraySchema("Optional domains to exclude"))
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content; use only when content freshness matters")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
                // v277（二改融合）：可选定向查询。提供 query 时只返回与该问题相关的原文片段（targeted_highlights），
                // 不抓整页全文，也不走按位置分页。
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "optional directed question. When provided, Exa returns only the query-relevant highlights of the page (targeted_highlights), not the full page. Use this to locate later-portion or specific details without downloading the whole page.")
                })
                // v277（二改融合）：可选分页参数，模型可按需分段读取长页，避免一次全文被统一工具截断。
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "max characters to return for this segment (default 12000). Negative or invalid values are ignored. The actual returned content is limited by tool output capacity, and the returned read info tells you the next start_index to continue from.")
                })
                put("start_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "character offset to start reading from (default 0). Use the next start_index from the returned read info to read the following segment of a long page. Must increase monotonically; do not go back to 0 unless you really intend to re-read.")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildSearchRequestBody(params, commonOptions.resultSize)
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val response = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(mapSearchResult(response))
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            // v277（二改融合）：可选定向 query 与本地分页参数（v2.5.0 官方骨架 + 二改能力）
            val scrapeQuery = params["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            val startIndex = params["start_index"]?.jsonPrimitive?.content?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
            val requestedMax = params["max_chars"]?.jsonPrimitive?.content?.toIntOrNull()
            val maxChars = if (requestedMax == null || requestedMax <= 0) {
                EXA_SCRAPE_DEFAULT_MAX_CHARS
            } else {
                requestedMax
            }
            val body = buildScrapeRequestBody(params, scrapeQuery)
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/contents")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val data = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(mapScrapedResult(data, scrapeQuery, startIndex, maxChars))
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
            }
        }
    }

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String? = null,
        @SerialName("autopromptString")
        val autopromptString: String? = null,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String? = null,
        @SerialName("results")
        val results: List<ExaResult>,
        @SerialName("output")
        val output: ExaOutput? = null,
    )

    @Serializable
    data class ExaOutput(
        @SerialName("content")
        val content: String? = null,
        @SerialName("grounding")
        val grounding: List<ExaGrounding> = emptyList(),
    )

    @Serializable
    data class ExaGrounding(
        @SerialName("field")
        val field: String? = null,
        @SerialName("citations")
        val citations: List<ExaCitation> = emptyList(),
        @SerialName("confidence")
        val confidence: String? = null,
    )

    @Serializable
    data class ExaCitation(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
    )

    @Serializable
    data class ExaResult(
        @SerialName("id")
        val id: String,
        @SerialName("title")
        val title: String,
        @SerialName("url")
        val url: String,
        @SerialName("publishedDate")
        val publishedDate: String? = null,
        @SerialName("author")
        val author: String? = null,
        @SerialName("text")
        val text: String? = null,
        @SerialName("image")
        val image: String? = null,
        @SerialName("highlights")
        val highlights: List<String>? = null,
    )

    internal fun buildSearchRequestBody(
        params: JsonObject,
        resultSize: Int,
    ) = buildJsonObject {
        val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
        val maxAgeHours = optionalMaxAgeHours(params)
        val hasEvidenceOptions = hasOptionalString(params, "startPublishedDate") ||
            hasOptionalString(params, "endPublishedDate") ||
            hasOptionalStringArray(params, "includeDomains") ||
            hasOptionalStringArray(params, "excludeDomains") ||
            maxAgeHours != null

        put("query", JsonPrimitive(query))
        put("numResults", JsonPrimitive(resultSize))
        put("type", JsonPrimitive(params["type"]?.jsonPrimitive?.content ?: "auto"))
        putOptionalString(this, params, "startPublishedDate")
        putOptionalString(this, params, "endPublishedDate")
        putOptionalStringArray(this, params, "includeDomains")
        putOptionalStringArray(this, params, "excludeDomains")
        put("contents", buildJsonObject {
            if (hasEvidenceOptions) {
                put("text", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_TEXT_CHARACTERS))
                })
                put("highlights", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_HIGHLIGHT_CHARACTERS))
                })
            } else {
                // v277（二改融合）：默认也走「highlights 优先 + 受限 text 备用」，绝不拉整篇正文
                put("highlights", buildJsonObject {
                    put("query", JsonPrimitive(query))
                    put("maxCharacters", JsonPrimitive(EXA_HIGHLIGHT_MAX_CHARS))
                })
                put("text", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(EXA_FALLBACK_MAX_CHARS))
                })
            }
            maxAgeHours?.let { put("maxAgeHours", it) }
        })
    }

    internal fun buildScrapeRequestBody(params: JsonObject, scrapeQuery: String) = buildJsonObject {
        val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
        put("urls", buildJsonArray {
            add(JsonPrimitive(url))
        })
        if (scrapeQuery.isEmpty()) {
            // v277（二改融合）：全文分页模式 —— 请求完整 text，由本服务按 start_index/max_chars 本地切片
            //（v278 真机教训：/contents 的 highlights / text 必须是**顶层**参数，包进 contents 会被静默忽略）
            put("text", JsonPrimitive(true))
        } else {
            // v278（二改融合）：定向模式 —— highlights 与 query 相关的原文片段为主，受限 text 兜底
            put("highlights", buildJsonObject {
                put("query", JsonPrimitive(scrapeQuery))
                put("maxCharacters", JsonPrimitive(EXA_HIGHLIGHT_MAX_CHARS))
            })
            put("text", buildJsonObject {
                put("maxCharacters", JsonPrimitive(EXA_FALLBACK_MAX_CHARS))
            })
        }
        optionalMaxAgeHours(params)?.let { put("maxAgeHours", it) }
    }

    internal fun mapSearchResult(data: ExaData): SearchResult = SearchResult(
        answer = data.output?.content,
        items = data.results.map {
            SearchResultItem(
                title = it.title,
                url = it.url,
                // v277（二改融合）：text 只放 query-relevant 片段（highlights 优先，无 highlights 时
                // 回退受限摘录并提示用 scrape_web 读全文），绝不放整篇正文。
                text = exaSearchText(it.highlights.orEmpty(), it.text, it.url),
                publishedDate = it.publishedDate,
                highlights = it.highlights.orEmpty(),
            )
        },
        images = data.results.mapNotNull { it.image?.takeIf { url -> url.isNotBlank() } },
    )

    internal fun mapScrapedResult(
        data: ExaData,
        scrapeQuery: String,
        startIndex: Int,
        maxChars: Int,
    ): ScrapedResult = ScrapedResult(
        urls = data.results.map {
            // v277/v278（二改融合）：content 受 query / start_index / max_chars 约束；
            // 结构化的段读取信息放在 metadata.description，由 SearchTools 的 Exa 分支解析输出。
            val info = if (scrapeQuery.isEmpty()) {
                exaScrapeChunkInfo(it.text ?: "", it.url, startIndex, maxChars)
            } else {
                exaTargetedReadInfo(it.url, it.highlights.orEmpty(), it.text)
            }
            ScrapedResultUrl(
                url = it.url,
                content = info.content,
                metadata = ScrapedResultMetadata(
                    title = it.title,
                    publishedDate = it.publishedDate,
                    description = exaScrapeReadInfoToJson(info).toString(),
                )
            )
        },
    )

    private fun domainArraySchema(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "string")
        })
    }

    private fun putOptionalString(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        params[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.put(name, it) }
    }

    private fun putOptionalStringArray(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        val values = params[name]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?: return
        builder.put(name, buildJsonArray { values.forEach { add(it) } })
    }

    private fun hasOptionalString(params: JsonObject, name: String): Boolean =
        runCatching {
            params[name]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }.getOrDefault(false)

    private fun hasOptionalStringArray(params: JsonObject, name: String): Boolean =
        runCatching {
            params[name]?.jsonArray?.any {
                it.jsonPrimitive.contentOrNull?.isNotBlank() == true
            } == true
        }.getOrDefault(false)

    private fun optionalMaxAgeHours(params: JsonObject): Int? =
        runCatching { params["maxAgeHours"]?.jsonPrimitive?.intOrNull }
            .getOrNull()
            ?.takeIf { it in MIN_MAX_AGE_HOURS..MAX_MAX_AGE_HOURS }

    // v277：以下为内部常量与纯函数，供单元测试直接调用（不依赖 Android / 网络）。

    /** search_web 每条结果 text 的本地上限（highlights 拼接总长） */
    internal const val EXA_HIGHLIGHT_MAX_CHARS = 2400

    /** 无 highlights 时 fallback 摘录的最大长度（受限制的 text 备用内容） */
    internal const val EXA_FALLBACK_MAX_CHARS = 2400

    /** scrape_web 默认返回的字符数 */
    internal const val EXA_SCRAPE_DEFAULT_MAX_CHARS = 12000

    /** 搜索结果条目的内容模式标记 */
    internal const val MODE_HIGHLIGHT = "highlight"
    internal const val MODE_FALLBACK_EXCERPT = "fallback_excerpt"
    internal const val MODE_UNAVAILABLE = "unavailable"

    /** scrape 读取状态标记（结构化，不藏在普通提示文字里） */
    internal const val EXA_SCRAPE_STATUS_OK = "ok"
    internal const val EXA_SCRAPE_STATUS_EMPTY = "empty"
    internal const val EXA_SCRAPE_STATUS_END = "end"
    internal const val EXA_SCRAPE_STATUS_OUT_OF_RANGE = "out_of_range"
    internal const val EXA_SCRAPE_STATUS_TARGETED = "targeted_highlights"
    internal const val EXA_SCRAPE_STATUS_TARGETED_FALLBACK = "targeted_fallback"
    internal const val EXA_SCRAPE_STATUS_OUTPUT_LIMITED = "output_limited"

    /** fallback 摘录的统一可识别前缀（用于从 text 反推 content_mode） */
    internal const val EXA_FALLBACK_SUFFIX_MARKER = "[excerpt only — call scrape_web"

    /**
     * v278：定向查询没挑出相关片段时的诚实说明。
     * 真机实测发现定向模式可能返回空 highlights，此时必须明确告知模型「这不是全文、也没命中」，
     * 而不是回一个空结果让模型以为这一页没内容。
     */
    internal const val EXA_TARGETED_FALLBACK_MARKER =
        "[no query-relevant highlight was returned for this page — the text above is only a limited excerpt; call scrape_web again without 'query' and use start_index to read the page in segments]"

    /**
     * v277：构造 /search 的 contents 请求体，使用官方支持的 contents.highlights + contents.text 对象形式。
     *
     * - highlights：按当前 query 相关性选取的原文片段（主要内容）；
     * - text：受限制的备用摘录（仅在没有 highlights 时兜底），绝不代表全文。
     */
    internal fun exaSearchContentsBody(
        query: String,
        highlightMaxChars: Int = EXA_HIGHLIGHT_MAX_CHARS,
        fallbackMaxChars: Int = EXA_FALLBACK_MAX_CHARS,
    ): JsonObject = buildJsonObject {
        put("highlights", buildJsonObject {
            put("query", JsonPrimitive(query))
            put("maxCharacters", JsonPrimitive(highlightMaxChars))
        })
        put("text", buildJsonObject {
            put("maxCharacters", JsonPrimitive(fallbackMaxChars))
        })
    }

    /**
     * v278：scrape 定向查询模式请求体。
     *
     * ⚠️ 真机实测（v277）证明：`/contents` 端点的 `highlights` / `text` 是**顶层**参数，
     * 把它们嵌在 `contents` 下面会被 Exa 静默忽略、返回空 highlights。所以这里返回的这些键
     * 必须由调用方**平铺到请求体顶层**，不能再包一层。
     *
     * - highlights：与 query 相关的原文片段（主要内容）；
     * - text：受限制的备用摘录，仅在这一页挑不出相关片段时兜底，绝不代表全文。
     */
    internal fun exaTargetedContentsBody(
        query: String,
        maxCharacters: Int = EXA_HIGHLIGHT_MAX_CHARS,
        fallbackMaxChars: Int = EXA_FALLBACK_MAX_CHARS,
    ): JsonObject = buildJsonObject {
        put("highlights", buildJsonObject {
            put("query", JsonPrimitive(query))
            put("maxCharacters", JsonPrimitive(maxCharacters))
        })
        put("text", buildJsonObject {
            put("maxCharacters", JsonPrimitive(fallbackMaxChars))
        })
    }

    /**
     * v278：定向查询结果的结构化读取信息。
     *
     * - 有相关片段 → targeted_highlights；
     * - 没有相关片段但拿到受限摘录 → targeted_fallback，并明确写清「没命中、这不是全文、可改用分段读」；
     * - 两者都没有 → empty。
     * 三种情况都不标 at_end / complete：定向查询本来就不是整页读取。
     */
    internal fun exaTargetedReadInfo(
        url: String,
        highlights: List<String>,
        fallbackText: String?,
        maxChars: Int = EXA_HIGHLIGHT_MAX_CHARS,
    ): ExaScrapeReadInfo {
        val bounds = maxChars.coerceAtLeast(1)
        val joined = joinTargetedHighlights(highlights, bounds)
        if (joined.isNotBlank()) {
            return ExaScrapeReadInfo(
                url = url,
                status = EXA_SCRAPE_STATUS_TARGETED,
                content = joined,
                startIndex = 0,
                endIndex = 0,
                totalChars = 0,
                nextStartIndex = null,
                hasMore = false,
                atEnd = false,
                complete = false,
            )
        }
        val fallback = fallbackText?.trim().orEmpty()
        if (fallback.isBlank()) {
            return ExaScrapeReadInfo(
                url = url,
                status = EXA_SCRAPE_STATUS_EMPTY,
                content = "",
                startIndex = 0,
                endIndex = 0,
                totalChars = 0,
                nextStartIndex = null,
                hasMore = false,
                atEnd = false,
                complete = false,
            )
        }
        val hint = "\n\n$EXA_TARGETED_FALLBACK_MARKER"
        val budget = (bounds - hint.length).coerceAtLeast(0)
        return ExaScrapeReadInfo(
            url = url,
            status = EXA_SCRAPE_STATUS_TARGETED_FALLBACK,
            content = safeTake(fallback, budget) + hint,
            startIndex = 0,
            endIndex = 0,
            totalChars = 0,
            nextStartIndex = null,
            hasMore = false,
            atEnd = false,
            complete = false,
        )
    }

    /** v277：搜索结果片段及其模式标记（SearchTools 序列化时用它带出 content_mode / text_is_full_page）。 */
    internal data class ExaSearchText(
        val content: String,
        val contentMode: String,
        val textIsFullPage: Boolean,
    )

    /**
     * v277：截取文本时尽量保持完整 Unicode 码点，避免把四字节表情从中间切开。
     * maxChars 仍按 Kotlin 字符数计算；若边界正好落在代理对之间，允许多带一个字符。
     */
    private fun safeTake(text: String, maxChars: Int): String {
        val requested = maxChars.coerceAtLeast(0)
        if (requested >= text.length) return text
        var end = requested
        if (end > 0 && end < text.length &&
            Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
            end++
        }
        return text.substring(0, end)
    }

    /** v277：计算不会落在代理对中间的分段终点。 */
    private fun safeSliceEnd(text: String, start: Int, maxChars: Int): Int {
        val requestedEnd = (start.toLong() + maxChars.toLong())
            .coerceAtMost(text.length.toLong())
            .toInt()
        if (requestedEnd > start && requestedEnd < text.length &&
            Character.isHighSurrogate(text[requestedEnd - 1]) && Character.isLowSurrogate(text[requestedEnd])) {
            return requestedEnd + 1
        }
        return requestedEnd
    }

    /**
     * v277：选择要喂给模型的搜索结果 text 并给出清晰状态。
     *
     * - highlights 非空时优先使用（Exa 已按 query 相关性选取），把所有高亮片段拼起来并受
     *   [maxChars] 上限约束，绝不简单 take 网页开头；模式为 highlight；
     * - 否则回退到正文的有限摘录（最多 [maxChars]），并明确提示可用 scrape_web 读取全文；模式为 fallback_excerpt；
     * - 两者都为空时返回空字符串；模式为 unavailable。
     * textIsFullPage 恒为 false：搜索片段绝不能被当成全文。
     */
    internal fun exaSearchTextInfo(
        highlights: List<String>,
        fallbackText: String?,
        url: String,
        maxChars: Int = EXA_HIGHLIGHT_MAX_CHARS,
    ): ExaSearchText {
        val bounds = maxChars.coerceAtLeast(1)
        val picked = highlights.map { it.trim() }.filter { it.isNotBlank() }
        if (picked.isNotEmpty()) {
            val sb = StringBuilder()
            for (h in picked) {
                val separator = "\n\n---\n\n"
                val separatorLength = if (sb.isEmpty()) 0 else separator.length
                val remaining = bounds - sb.length - separatorLength
                if (remaining <= 0) break
                if (separatorLength > 0) sb.append(separator)
                sb.append(safeTake(h, remaining))
            }
            return ExaSearchText(sb.toString(), MODE_HIGHLIGHT, false)
        }
        val fallback = fallbackText?.trim().orEmpty()
        if (fallback.isBlank()) return ExaSearchText("", MODE_UNAVAILABLE, false)
        val hint = "\n\n$EXA_FALLBACK_SUFFIX_MARKER with url=$url and max_chars/start_index to read the full page]"
        val contentBudget = (bounds - hint.length).coerceAtLeast(0)
        val capped = safeTake(fallback, contentBudget)
        return ExaSearchText(
            capped + hint,
            MODE_FALLBACK_EXCERPT,
            false,
        )
    }

    /**
     * v277：兼容入口 —— 只返回拼接好的 text 字符串（供 SearchResultItem.text 使用）。
     */
    internal fun exaSearchText(
        highlights: List<String>,
        fallbackText: String?,
        url: String,
        maxChars: Int = EXA_HIGHLIGHT_MAX_CHARS,
    ): String = exaSearchTextInfo(highlights, fallbackText, url, maxChars).content

    /**
     * v277：从 search 结果的 text 反推其内容模式（供 SearchTools 的 Exa 分支序列化时标记）。
     * 该判定与 exaSearchTextInfo 的产出规则一致。
     */
    internal fun classifySearchText(text: String): ExaSearchText {
        return when {
            text.isBlank() -> ExaSearchText("", MODE_UNAVAILABLE, false)
            text.contains(EXA_FALLBACK_SUFFIX_MARKER) -> ExaSearchText(text, MODE_FALLBACK_EXCERPT, false)
            else -> ExaSearchText(text, MODE_HIGHLIGHT, false)
        }
    }

    /** v277：scrape 本地切分结果（兼容保留）——content 为干净受控片段，nextStartIndex 为下一次 start_index。 */
    internal data class ExaScrapeChunk(
        val content: String,
        val nextStartIndex: Int?,
    )

    /**
     * v277：scrape 的结构化读取信息（Exa 专属公开结果类型，供 SearchTools 序列化与单元测试）。
     *
     * - status：ok / empty / end / out_of_range / targeted_highlights；
     * - start_index / end_index：本段正文的字符区间；
     * - total_chars：从 Exa 取到的整页字符数（定向模式为 0，表示非全文）；
     * - next_start_index：下一段起点（无则 null）；has_more / at_end：是否还有内容 / 是否已到末尾；
     * - complete：仅当从头(start=0)开始且本段就已读到末尾时才为 true（代表确实读完了整页），
     *   中途直接读到末尾不能标 true。
     */
    internal data class ExaScrapeReadInfo(
        val url: String,
        val status: String,
        val content: String,
        val startIndex: Int,
        val endIndex: Int,
        val totalChars: Int,
        val nextStartIndex: Int?,
        val hasMore: Boolean,
        val atEnd: Boolean,
        val complete: Boolean,
    )

    /**
     * v277：对 Exa 返回的全文在本地切片，返回结构化读取信息。
     *
     * - start_index 单调向后；start > total 时明确 out_of_range，绝不伪装成“已读完”；
     * - 空文本返回 empty；start == total（且 total>0) 返回 end（已到末尾）；
     * - maxChars 不做固定封顶，仅保证非正数回退到默认、超大值按总长度安全截断；
     * - end/next 按实际返回的 content 位置计算，不会跳字或重复。
     */
    internal fun exaScrapeChunkInfo(
        fullText: String,
        url: String,
        startIndex: Int,
        maxChars: Int = EXA_SCRAPE_DEFAULT_MAX_CHARS,
    ): ExaScrapeReadInfo {
        val start = startIndex.coerceAtLeast(0)
        val total = fullText.length
        val size = if (maxChars <= 0) EXA_SCRAPE_DEFAULT_MAX_CHARS else maxChars
        if (fullText.isEmpty()) {
            return ExaScrapeReadInfo(url, EXA_SCRAPE_STATUS_EMPTY, "", start, start, 0, null, false, false, false)
        }
        if (start > total) {
            return ExaScrapeReadInfo(url, EXA_SCRAPE_STATUS_OUT_OF_RANGE, "", start, start, total, null, false, false, false)
        }
        if (start == total) {
            return ExaScrapeReadInfo(url, EXA_SCRAPE_STATUS_END, "", start, start, total, null, false, true, false)
        }
        val end = safeSliceEnd(fullText, start, size)
        val content = fullText.substring(start, end)
        val hasMore = end < total
        return ExaScrapeReadInfo(
            url = url,
            status = EXA_SCRAPE_STATUS_OK,
            content = content,
            startIndex = start,
            endIndex = end,
            totalChars = total,
            nextStartIndex = if (hasMore) end else null,
            hasMore = hasMore,
            atEnd = !hasMore,
            complete = start == 0 && !hasMore,
        )
    }

    /** v277：把结构化读取信息序列化为合法 JSON（Exa 专属字段，放进 ScrapedResultUrl.metadata.description）。 */
    internal fun exaScrapeReadInfoToJson(info: ExaScrapeReadInfo): JsonObject = buildJsonObject {
        put("url", JsonPrimitive(info.url))
        put("status", JsonPrimitive(info.status))
        put("start_index", JsonPrimitive(info.startIndex))
        put("end_index", JsonPrimitive(info.endIndex))
        put("total_chars", JsonPrimitive(info.totalChars))
        if (info.nextStartIndex != null) {
            put("next_start_index", JsonPrimitive(info.nextStartIndex))
        } else {
            put("next_start_index", JsonNull)
        }
        put("has_more", JsonPrimitive(info.hasMore))
        put("at_end", JsonPrimitive(info.atEnd))
        put("complete", JsonPrimitive(info.complete))
    }

    /** v277：scrape 本地切分（兼容保留）——委托给结构化版本，content 保持干净正文（不再附加普通文字提示）。 */
    internal fun exaScrapeChunk(
        fullText: String,
        startIndex: Int,
        maxChars: Int = EXA_SCRAPE_DEFAULT_MAX_CHARS,
    ): ExaScrapeChunk {
        val info = exaScrapeChunkInfo(fullText, "scrape", startIndex, maxChars)
        return ExaScrapeChunk(info.content, info.nextStartIndex)
    }

    /** v277：把定向查询返回的 highlights 拼接成受限文本（非全文）。 */
    internal fun joinTargetedHighlights(
        highlights: List<String>,
        maxChars: Int = EXA_HIGHLIGHT_MAX_CHARS,
    ): String {
        val bounds = maxChars.coerceAtLeast(1)
        val picked = highlights.map { it.trim() }.filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (h in picked) {
            val separator = "\n\n---\n\n"
            val separatorLength = if (sb.isEmpty()) 0 else separator.length
            val remaining = bounds - sb.length - separatorLength
            if (remaining <= 0) break
            if (separatorLength > 0) sb.append(separator)
            sb.append(safeTake(h, remaining))
        }
        return sb.toString()
    }

    // ===== SearchTools 的 Exa 专属输出方法（public，供 app 模块调用并传入主模型 MAX_TOOL_OUTPUT_CHARS）=====

    /**
     * v277：把普通 search 结果序列化为 Exa 专属工具输出（合法 JSON），在 maxOutputChars 内自适应压缩。
     *
     * 优先级：保留标题、网址、id / index 和相关片段；再缩短 answer / text；必要时去掉图片或低优先级结果。
     * 若确有省略，会用 omitted_count / payload_limited / answer_truncated 等字段明确说明，绝不静默假装完整，
     * 也绝不从 JSON 字符串中间硬截断（始终重新构造合法 JSON）。
     */
    fun exaSearchToolOutput(
        result: SearchResult,
        maxOutputChars: Int,
    ): String {
        val cap = maxOutputChars.coerceAtLeast(2)

        fun buildPayload(
            items: List<JsonObject>,
            images: List<String>,
            answer: String?,
            omitted: Int,
            limited: Boolean,
            answerTruncated: Boolean,
        ): String = buildJsonObject {
            put("items", JsonArray(items))
            if (answer != null) put("answer", JsonPrimitive(answer))
            if (images.isNotEmpty()) put("images", JsonArray(images.map { JsonPrimitive(it) }))
            if (omitted > 0) put("omitted_count", JsonPrimitive(omitted))
            if (limited) put("payload_limited", JsonPrimitive(true))
            if (answerTruncated) put("answer_truncated", JsonPrimitive(true))
        }.toString()

        fun itemJson(
            index: Int,
            id: String,
            title: String,
            url: String,
            text: String,
            contentMode: String,
            textIsFullPage: Boolean,
        ): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("index", JsonPrimitive(index + 1))
            put("title", JsonPrimitive(title))
            put("url", JsonPrimitive(url))
            put("text", JsonPrimitive(text))
            put("content_mode", JsonPrimitive(contentMode))
            put("text_is_full_page", JsonPrimitive(textIsFullPage))
        }

        fun buildAll(textFactor: Double): Pair<List<JsonObject>, Boolean> {
            val items = result.items.mapIndexed { index, item ->
                val meta = classifySearchText(item.text)
                val text = if (item.text.length * textFactor < item.text.length) {
                    safeTake(item.text, (item.text.length * textFactor).toInt().coerceAtLeast(0))
                } else item.text
                itemJson(index, Uuid.random().toString().take(6), item.title, item.url, text, meta.contentMode, meta.textIsFullPage)
            }
            return items to false
        }

        // 逐级压缩：先完整，再缩短 text，再去图片，再减少 items。
        var payload = buildPayload(buildAll(1.0).first, result.images, result.answer, 0, false, false)
        if (payload.length <= cap) return payload

        // 第一级：整体按比例缩短 text 与 answer。
        var factor = 0.7
        var answer = result.answer
        var answerTruncated = false
        while (factor > 0.05) {
            val items = result.items.mapIndexed { index, item ->
                val meta = classifySearchText(item.text)
                val text = safeTake(item.text, (item.text.length * factor).toInt())
                itemJson(index, Uuid.random().toString().take(6), item.title, item.url, text, meta.contentMode, meta.textIsFullPage)
            }
            val ans = answer?.let {
                val newLen = (it.length * factor).toInt()
                val capped = safeTake(it, newLen)
                if (capped.length < it.length) answerTruncated = true
                capped
            }
            payload = buildPayload(items, result.images, ans, 0, true, answerTruncated)
            if (payload.length <= cap) return payload
            factor -= 0.15
        }

        // 第二级：去掉图片。
        val itemsNoImg = result.items.mapIndexed { index, item ->
            val meta = classifySearchText(item.text)
            val text = safeTake(item.text, (item.text.length * 0.05).toInt())
            itemJson(index, Uuid.random().toString().take(6), item.title, item.url, text, meta.contentMode, meta.textIsFullPage)
        }
        payload = buildPayload(itemsNoImg, emptyList(), answer?.let { safeTake(it, (it.length * 0.05).toInt()) }, 0, true, answer != null)
        if (payload.length <= cap) return payload

        // 第三级：减少 items（低优先级 = 靠后的结果），并明确 omitted_count。
        var maxItems = result.items.size
        while (maxItems > 1) {
            maxItems = (maxItems * 0.7).toInt().coerceAtLeast(1)
            val kept = result.items.take(maxItems).mapIndexed { index, item ->
                val meta = classifySearchText(item.text)
                val id = Uuid.random().toString().take(6)
                val text = safeTake(item.text, (item.text.length * 0.05).toInt())
                itemJson(index, id, item.title, item.url, text, meta.contentMode, meta.textIsFullPage)
            }
            val omitted = result.items.size - maxItems
            payload = buildPayload(kept, emptyList(), null, omitted, true, true)
            if (payload.length <= cap) return payload
        }

        // 兜底：只保留第一个条目的最小信息。
        val first = result.items.firstOrNull()
        val minimal = if (first != null) {
            listOf(itemJson(0, Uuid.random().toString().take(6), first.title, first.url, "", MODE_UNAVAILABLE, false))
        } else emptyList()
        val minimalPayload = buildPayload(
            minimal,
            emptyList(),
            null,
            (result.items.size - minimal.size),
            true,
            result.answer != null,
        )
        if (minimalPayload.length <= cap) return minimalPayload

        // 极端情况下标题/网址本身就超过调用方容量：宁可返回明确的空结果，也不返回超限内容。
        val emptyPayload = buildPayload(
            emptyList(),
            emptyList(),
            null,
            result.items.size,
            true,
            result.answer != null,
        )
        return if (emptyPayload.length <= cap) emptyPayload else "{}"
    }

    /**
     * v277：把普通 scrape 结果序列化为 Exa 专属工具输出（合法 JSON），在 maxOutputChars 内自适应压缩。
     *
     * 每个 url 输出 content 与结构化的 read 块（来自 scrape() 时写入 metadata.description 的
     * ExaScrapeReadInfo JSON）。若 content 因容量被进一步缩短，end / next / has_more / at_end / complete
     * 会按实际返回的正文位置重新计算，不会出现跳字或重复。
     */
    fun exaScrapeToolOutput(
        result: ScrapedResult,
        maxOutputChars: Int,
    ): String {
        val cap = maxOutputChars.coerceAtLeast(2)

        fun applyCap(readBase: JsonObject?, url: String, content: String): JsonObject {
            val readObj = readBase?.let { r ->
                val status = r["status"]?.jsonPrimitive?.content.orEmpty()
                if (status != EXA_SCRAPE_STATUS_OK) {
                    // 定向 / 空 / 末尾 / 越界状态：结构化字段已明确（非全文或已到末尾），原样保留。
                    r
                } else {
                    // 正常分段：按 content 实际返回长度重算 end / next / has_more / at_end / complete，
                    // 这样即使容量压缩缩短了正文，next 也指向真实剩余位置，不会跳字或重复。
                    val startIdx = r["start_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val totalChars = r["total_chars"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val safeEnd = (startIdx.toLong() + content.length.toLong())
                        .coerceAtMost(totalChars.toLong())
                        .toInt()
                    val noProgress = content.isEmpty() && startIdx < totalChars
                    val effectiveStatus = if (noProgress) EXA_SCRAPE_STATUS_OUTPUT_LIMITED else status
                    val hasMore = if (noProgress) true else safeEnd < totalChars
                    val atEnd = !hasMore && !noProgress
                    val complete = startIdx == 0 && atEnd
                    buildJsonObject {
                        put("url", JsonPrimitive(url))
                        put("status", JsonPrimitive(effectiveStatus))
                        put("start_index", JsonPrimitive(startIdx))
                        put("end_index", JsonPrimitive(safeEnd))
                        put("total_chars", JsonPrimitive(totalChars))
                        if (hasMore && safeEnd > startIdx) put("next_start_index", JsonPrimitive(safeEnd)) else put("next_start_index", JsonNull)
                        put("has_more", JsonPrimitive(hasMore))
                        put("at_end", JsonPrimitive(atEnd))
                        put("complete", JsonPrimitive(complete))
                    }
                }
            }
            return buildJsonObject {
                put("url", JsonPrimitive(url))
                put("content", JsonPrimitive(content))
                if (readObj != null) put("read", readObj)
            }
        }

        fun buildFull(factor: Double, limited: Boolean): JsonObject {
            val urls = result.urls.map { u ->
                val readMeta = u.metadata?.description?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val content = if (factor < 1.0 && u.content.isNotEmpty()) {
                    safeTake(u.content, (u.content.length * factor).toInt().coerceAtLeast(0))
                } else u.content
                applyCap(readMeta, u.url, content)
            }
            return buildJsonObject {
                put("urls", JsonArray(urls))
                if (limited) put("payload_limited", JsonPrimitive(true))
            }
        }

        // 全量输出；超出再按比例缩短每条 content。
        var payload = buildFull(1.0, limited = false).toString()
        if (payload.length <= cap) return payload
        var factor = 0.7
        while (factor > 0.05) {
            payload = buildFull(factor, limited = true).toString()
            if (payload.length <= cap) return payload
            factor -= 0.15
        }
        // 兜底：只保留每个 url 的最小信息（content 清空，保留 read 的索引）。
        val minimalUrls = result.urls.map { u ->
            val readMeta = u.metadata?.description?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            applyCap(readMeta, u.url, "")
        }
        val minimalPayload = buildJsonObject {
            put("urls", JsonArray(minimalUrls))
            if (result.urls.isNotEmpty()) put("payload_limited", JsonPrimitive(true))
        }.toString()
        if (minimalPayload.length <= cap) return minimalPayload

        // 极端情况下网址/元数据本身就超过调用方容量：返回明确的空列表，不返回超限 JSON。
        val emptyPayload = buildJsonObject {
            put("urls", JsonArray(emptyList()))
            if (result.urls.isNotEmpty()) {
                put("payload_limited", JsonPrimitive(true))
                put("omitted_count", JsonPrimitive(result.urls.size))
            }
        }.toString()
        return if (emptyPayload.length <= cap) emptyPayload else "{}"
    }
}
