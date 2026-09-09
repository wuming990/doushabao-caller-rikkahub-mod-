package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.MAX_TOOL_OUTPUT_CHARS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.ExaSearchService
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        // v277：Exa 使用专属说明与专属输出（高亮片段 / 定向查询 / 结构化分段读取），
        // 其他 provider 保留原有通用说明与执行/输出格式，互不影响。
        val isExa = options is SearchServiceOptions.ExaOptions

        // v277：search_web 说明 —— Exa 专属（提到片段模式 / 定向查找 / 分段读取），其余走通用文案。
        val searchDescription = if (isExa) {
            """
                Search the web for up-to-date or specific information.
                Use this when the user asks for the latest news, current facts, or needs verification.
                Generate focused keywords and run multiple searches if needed.
                Today is ${LocalDate.now().toLocalString(true)}.

                Response format:
                - items[].id (short id), title, url, text
                - images[]: image urls related to the query (may be empty)

                Note (Exa): items[].text is a query-relevant excerpt returned by Exa, NOT the full page body
                (the content_mode / text_is_full_page fields make this explicit). If you need exact or
                later-portion content from a specific result, call scrape_web with that url and an optional
                'query' to do a directed search, or read a long page in segments using max_chars / start_index
                from the returned read info.

                Citations:
                - After using results, add `[citation,domain](id)` after the sentence.
                - Multiple citations are allowed.
                - If no results are cited, omit citations.

                Images:
                - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                Example:
                The capital of France is Paris. [citation,example.com](abc123)
                The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
            """.trimIndent()
        } else {
            """
                Search the web for up-to-date or specific information.
                Use this when the user asks for the latest news, current facts, or needs verification.
                Generate focused keywords and run multiple searches if needed.
                Today is ${LocalDate.now().toLocalString(true)}.

                Response format:
                - items[].id (short id), title, url, text
                - images[]: image urls related to the query (may be empty)

                Note: items[].text is an excerpt of the page content returned by the search service.

                Citations:
                - After using results, add `[citation,domain](id)` after the sentence.
                - Multiple citations are allowed.
                - If no results are cited, omit citations.

                Images:
                - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                Example:
                The capital of France is Paris. [citation,example.com](abc123)
                The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
            """.trimIndent()
        }

        add(
            Tool(
                name = "search_web",
                description = searchDescription,
                parameters = {
                    service.parameters(options)
                },
                execute = {
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    // v2.5.0 融合：官方新增 retrievedAt（本地取回时间，非发布时间）
                    val exaResult = result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    if (isExa) {
                        // v277：Exa 专属输出 —— 在现有 MAX_TOOL_OUTPUT_CHARS 内自适应压缩，
                        // 绝不从 JSON 字符串中间硬截断；非 Exa 服务走下方通用路径。
                        val out = ExaSearchService.exaSearchToolOutput(
                            result = exaResult,
                            maxOutputChars = MAX_TOOL_OUTPUT_CHARS,
                        )
                        listOf(UIMessagePart.Text(out))
                    } else {
                        val results =
                            JsonInstantPretty.encodeToJsonElement(exaResult).jsonObject.let { json ->
                                val map = json.toMutableMap()
                                map["items"] =
                                    JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                        JsonObject(item.jsonObject.toMutableMap().apply {
                                            put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                            put("index", JsonPrimitive(index + 1))
                                        })
                                    })
                                JsonObject(map)
                            }
                        listOf(UIMessagePart.Text(results.toString()))
                    }
                }
            )
        )

        if (service.scrapingParameters(options) != null) {
            // v277：scrape_web 说明 —— Exa 专属（定向 query / 结构化分段），其余走通用文案。
            val scrapeDescription = if (isExa) {
                """
                    Scrape a URL for detailed page content.
                    Use this when the user requests content from a specific page or when search snippets are insufficient.
                    Avoid using it for common questions unless the user asks.

                    Exa modes:
                    - Provide an optional 'query' to do a directed search: Exa returns only the query-relevant
                      highlights of the page (targeted_highlights), locating later-portion or specific details
                      without downloading the whole page. If that page yields no relevant highlight, the read
                      status is targeted_fallback and only a short excerpt is returned — switch to the segmented
                      full-page mode below in that case.
                    - Without 'query', the full page is fetched and read in segments: use max_chars / start_index.
                      Every returned url carries a structured 'read' object with start_index, end_index,
                      total_chars, next_start_index, has_more, at_end and complete. Continue with the
                      next_start_index for the following segment. When has_more is false the page has been fully
                      covered from the requested start; do not go back to start_index=0 unless you really
                      intend to re-read.
                    """.trimIndent()
            } else {
                """
                    Scrape a URL for detailed page content.
                    Use this when the user requests content from a specific page or when search snippets are insufficient.
                    Avoid using it for common questions unless the user asks.
                    """.trimIndent()
            }
            add(
                Tool(
                    name = "scrape_web",
                    description = scrapeDescription,
                    parameters = {
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        // v2.5.0 融合：官方新增 retrievedAt
                        val exaScrapeResult = result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        if (isExa) {
                            // v277：Exa 专属输出 —— 自适应压缩并保留结构化 read 信息。
                            val out = ExaSearchService.exaScrapeToolOutput(
                                result = exaScrapeResult,
                                maxOutputChars = MAX_TOOL_OUTPUT_CHARS,
                            )
                            listOf(UIMessagePart.Text(out))
                        } else {
                            val payload = JsonInstantPretty.encodeToJsonElement(exaScrapeResult).jsonObject
                            listOf(UIMessagePart.Text(payload.toString()))
                        }
                    }
                ))
        }
    }
}
