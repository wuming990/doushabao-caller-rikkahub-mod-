package me.rerere.search

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaSearchServiceTest {
    @Test
    fun `search mapping preserves publication date and highlights`() {
        val result = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult(
                        id = "result-1",
                        title = "Official release",
                        url = "https://example.com/release",
                        publishedDate = "2026-08-31T06:26:20Z",
                        highlights = listOf("Current release evidence"),
                        text = "Release text",
                    )
                )
            )
        )

        assertEquals("2026-08-31T06:26:20Z", result.items.single().publishedDate)
        assertEquals(listOf("Current release evidence"), result.items.single().highlights)
        assertEquals("https://example.com/release", result.items.single().url)
    }

    @Test
    fun `missing publication date decodes as null`() {
        val data = SearchService.json.decodeFromString<ExaSearchService.ExaData>(
            """
            {
              "results": [{
                "id": "result-1",
                "title": "Undated result",
                "url": "https://example.com/undated"
              }]
            }
            """.trimIndent()
        )

        assertNull(ExaSearchService.mapSearchResult(data).items.single().publishedDate)
    }

    @Test
    fun `current query does not inject domain or freshness filters`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "What is the current stable RikkaHub version?")
            },
            resultSize = 10,
        )

        assertTrue("includeDomains" !in body)
        assertTrue("maxAgeHours" !in body["contents"]!!.jsonObject)
    }

    @Test
    fun `search mapping preserves provider result order`() {
        val result = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult("old", "Old", "https://example.com/old", "2026-01-01T00:00:00Z"),
                    ExaSearchService.ExaResult("undated", "Undated", "https://example.com/undated"),
                    ExaSearchService.ExaResult("new", "New", "https://example.com/new", "2026-09-01T00:00:00Z"),
                )
            )
        )

        assertEquals(
            listOf("old", "undated", "new"),
            result.items.map { it.url.substringAfterLast('/') })
    }

    @Test
    fun `normal search request uses highlight first bounded content`() {
        // v277（二改融合）：默认请求也走「highlights 优先 + 受限 text 备用」，绝不拉整篇正文
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        assertEquals(
            2_400,
            contents["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertEquals(
            "stable knowledge",
            contents["highlights"]!!.jsonObject["query"]!!.jsonPrimitive.content
        )
        assertTrue("startPublishedDate should be absent", "startPublishedDate" !in body)
        assertTrue("includeDomains should be absent", "includeDomains" !in body)
    }

    @Test
    fun `evidence request serializes dates domains and content freshness`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "latest release")
                put("startPublishedDate", "2026-08-01T00:00:00Z")
                put("endPublishedDate", "2026-09-03T23:59:59Z")
                put("includeDomains", buildJsonArray { add(JsonPrimitive("example.com")) })
                put("excludeDomains", buildJsonArray { add(JsonPrimitive("old.example.com")) })
                put("maxAgeHours", 1)
            },
            resultSize = 10,
        )

        assertEquals("2026-08-01T00:00:00Z", body["startPublishedDate"]!!.jsonPrimitive.content)
        assertEquals("2026-09-03T23:59:59Z", body["endPublishedDate"]!!.jsonPrimitive.content)
        assertEquals("example.com", body["includeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("old.example.com", body["excludeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, body["contents"]!!.jsonObject["maxAgeHours"]!!.jsonPrimitive.int)
        assertEquals(
            8_000,
            body["contents"]!!.jsonObject["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertEquals(
            1_200,
            body["contents"]!!.jsonObject["highlights"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )

        val schema = ExaSearchService.parameters(SearchServiceOptions.ExaOptions()) as InputSchema.Obj
        assertEquals("integer", schema.properties["maxAgeHours"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            schema.properties["startPublishedDate"]!!.jsonObject["description"]!!.jsonPrimitive.content
                .contains("after this date")
        )
        assertTrue(
            schema.properties["endPublishedDate"]!!.jsonObject["description"]!!.jsonPrimitive.content
                .contains("before this date")
        )
        assertEquals(-1, schema.properties["maxAgeHours"]!!.jsonObject["minimum"]!!.jsonPrimitive.int)
        assertEquals(720, schema.properties["maxAgeHours"]!!.jsonObject["maximum"]!!.jsonPrimitive.int)
    }

    @Test
    fun `empty and null optional values keep legacy request shape`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
                put("startPublishedDate", "")
                put("endPublishedDate", JsonNull)
                put("includeDomains", buildJsonArray { add(JsonPrimitive("")) })
                put("excludeDomains", buildJsonArray {})
                put("maxAgeHours", JsonPrimitive("not-an-integer"))
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        // v277（二改融合）：无有效证据选项时同样走「highlights 优先 + 受限 text」
        assertEquals(
            2_400,
            contents["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertTrue("highlights should stay requested", "highlights" in contents)
        assertTrue("empty start date should be absent", "startPublishedDate" !in body)
        assertTrue("null end date should be absent", "endPublishedDate" !in body)
        assertTrue("empty include domains should be absent", "includeDomains" !in body)
        assertTrue("empty exclude domains should be absent", "excludeDomains" !in body)
        assertTrue("invalid max age should be absent", "maxAgeHours" !in contents)
    }

    @Test
    fun `out of range max age is omitted`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "latest release")
                put("maxAgeHours", 721)
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        assertTrue("out of range max age should be absent", "maxAgeHours" !in contents)
        // v277（二改融合）：越界 maxAge 被滤掉后仍走「highlights 优先 + 受限 text」
        assertEquals(
            2_400,
            contents["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertTrue("highlights should stay requested", "highlights" in contents)
    }

    @Test
    fun `scrape request supports content freshness and bounded text`() {
        val body = ExaSearchService.buildScrapeRequestBody(
            buildJsonObject {
                put("url", "https://example.com/release")
                put("maxAgeHours", 0)
            },
            scrapeQuery = "",
        )

        assertEquals(0, body["maxAgeHours"]!!.jsonPrimitive.int)
        // v277（二改融合）：空 query = 全量分页模式，请求完整 text 由本地切片
        assertEquals(true, body["text"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `targeted scrape keeps highlights and bounded text at top level`() {
        // v278（二改融合）：定向模式的 highlights / text 必须平铺在请求体顶层，
        // 包进 contents 会被 Exa 静默忽略（真机实测）
        val body = ExaSearchService.buildScrapeRequestBody(
            buildJsonObject {
                put("url", "https://example.com/release")
            },
            scrapeQuery = "release date",
        )

        assertEquals(
            "release date",
            body["highlights"]!!.jsonObject["query"]!!.jsonPrimitive.content
        )
        assertEquals(
            2_400,
            body["highlights"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertEquals(
            2_400,
            body["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int
        )
        assertTrue("highlights must stay top level", "contents" !in body)
    }

    @Test
    fun `search mapping marks text as bounded excerpt not full page`() {
        // v277（二改融合）：text 一律是受限摘录（highlights 优先），绝不代表整页正文
        val withHighlights = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult(
                        id = "r1", title = "T", url = "https://example.com/a",
                        highlights = listOf("matched evidence"), text = "full body",
                    )
                )
            )
        )
        assertEquals("matched evidence", withHighlights.items.single().text)

        val fallbackOnly = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult(
                        id = "r2", title = "T", url = "https://example.com/b",
                        text = "full body",
                    )
                )
            )
        )
        assertTrue(
            "fallback text must carry the bounded-excerpt marker",
            fallbackOnly.items.single().text.contains("[excerpt only")
        )
    }
}
