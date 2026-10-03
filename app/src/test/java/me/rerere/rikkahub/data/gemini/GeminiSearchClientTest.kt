package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v310：独立搜索的纯函数单测 —— 请求形状、响应解析、回填格式。
 *
 * 网络层不在这里测（要真账号 + 真端点），由真机验收覆盖；这里钉住的是
 * 「搜索请求永远只带 googleSearch」「响应怎么解析」「回填文本长什么样」这三件最容易改坏的事。
 */
class GeminiSearchClientTest {
    private val json = Json.Default

    // ---- 请求形状 ----

    @Test
    fun `the plain search request carries only googleSearch and never function declarations`() {
        val body = buildSearchRequestBody(
            model = "gemini-3-flash-preview",
            projectId = "proj-1",
            query = "weather in tokyo",
            variant = SearchRequestVariant.PLAIN,
        )
        val inner = body["request"]!!.jsonObject
        val tools = inner["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertTrue(tools[0].jsonObject.containsKey("googleSearch"))
        assertFalse("搜索请求绝不允许带函数工具", tools.any { it.jsonObject.containsKey("functionDeclarations") })
        assertEquals("gemini-3-flash-preview", body["model"]!!.jsonPrimitive.content)
        assertEquals("proj-1", body["project"]!!.jsonPrimitive.content)
        assertFalse("PLAIN 不带 agent 信封", body.containsKey("requestType"))
    }

    @Test
    fun `the agent envelope adds the self-reported traffic fields`() {
        val body = buildSearchRequestBody(
            model = "gemini-2.5-flash",
            projectId = "proj-2",
            query = "q",
            variant = SearchRequestVariant.AGENT_ENVELOPE,
        )
        assertEquals("agent", body["requestType"]!!.jsonPrimitive.content)
        assertEquals("antigravity", body["userAgent"]!!.jsonPrimitive.content)
        assertTrue(body["requestId"]!!.jsonPrimitive.content.startsWith("agent-"))
    }

    @Test
    fun `attempt order tries the last known good pair first`() {
        val preferred = "gemini-2.5-flash" to SearchRequestVariant.AGENT_ENVELOPE
        val order = buildSearchAttemptOrder(preferred)
        assertEquals(preferred, order.first())
        assertEquals(SEARCH_MODEL_CANDIDATES.size * SearchRequestVariant.entries.size, order.size)
        assertEquals("组合不得重复", order.size, order.toSet().size)
    }

    @Test
    fun `no preferred pair means the default order starts with the official shape`() {
        val order = buildSearchAttemptOrder(null)
        assertEquals(SEARCH_MODEL_CANDIDATES.first() to SearchRequestVariant.PLAIN, order.first())
        assertEquals(SEARCH_MODEL_CANDIDATES.size * SearchRequestVariant.entries.size, order.size)
    }

    // ---- 响应解析 ----

    @Test
    fun `a wrapped response is unwrapped and thought parts are ignored`() {
        val raw = """
            {"response":{"candidates":[{"content":{"parts":[
              {"text":"ignored","thought":true},
              {"text":"Tokyo is sunny. "},
              {"text":"Bring a light coat."}
            ]},"groundingMetadata":{"groundingChunks":[
              {"web":{"title":"Weather JP","uri":"https://example.com/a"}},
              {"web":{"uri":"https://example.com/b"}}
            ]}}]},"traceId":"t-1"}
        """.trimIndent()
        val outcome = parseGeminiSearchResponse(json, raw)!!
        assertEquals("Tokyo is sunny. Bring a light coat.", outcome.text)
        assertEquals(2, outcome.sources.size)
        assertEquals("Weather JP", outcome.sources[0].title)
        assertEquals("https://example.com/a", outcome.sources[0].uri)
        assertEquals("Untitled", outcome.sources[1].title)
        assertEquals("https://example.com/b", outcome.sources[1].uri)
    }

    @Test
    fun `a top-level response without the wrapper is accepted too`() {
        val raw = """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""
        assertEquals("ok", parseGeminiSearchResponse(json, raw)!!.text)
    }

    @Test
    fun `an error body from the service is rejected instead of being treated as success`() {
        assertNull(parseGeminiSearchResponse(json, """{"error":{"code":400,"message":"bad"}}"""))
        assertNull(parseGeminiSearchResponse(json, "not json at all"))
        assertNull(parseGeminiSearchResponse(json, """{"candidates":[]}"""))
    }

    // ---- 回填格式 ----

    @Test
    fun `the tool output carries the query, the summary and the sources`() {
        val outcome = GeminiSearchOutcome(
            text = "It is sunny.",
            sources = listOf(GeminiSearchSource("Example", "https://example.com/x")),
        )
        val rendered = formatGeminiSearchOutput("tokyo weather", outcome)
        assertTrue(rendered.startsWith("Web search results for \"tokyo weather\":"))
        assertTrue(rendered.contains("It is sunny."))
        assertTrue(rendered.contains("Sources:"))
        assertTrue(rendered.contains("[1] Example (https://example.com/x)"))
    }

    @Test
    fun `an empty summary renders a clear no-results line`() {
        val rendered = formatGeminiSearchOutput("q", GeminiSearchOutcome("", emptyList()))
        assertTrue(rendered.contains("No search results"))
        assertFalse(rendered.contains("Sources:"))
    }
}
