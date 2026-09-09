package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方 2.4.9 起 MAX 是正式档位（`reasoningLevel = "max"`），
 * 不再需要二改早期那个「XHIGH + useMaxReasoning 标记」的变通做法。
 *
 * 这里守住两件事：
 *  1. MAX 能正常存取；
 *  2. v211 及更早存下来的旧数据（含已废弃的 useMaxReasoning 字段）读进来不会崩，
 *     只会安全地显示成「超高」，用户手动再选一次 MAX 即可。
 */
class AssistantMaxCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `empty assistant data falls back to auto`() {
        val assistant = json.decodeFromString<Assistant>("{}")
        assertEquals(ReasoningLevel.AUTO, assistant.reasoningLevel)
    }

    @Test
    fun `MAX persists as the official max level`() {
        val encoded = json.encodeToString(Assistant(reasoningLevel = ReasoningLevel.MAX))
        val decoded = json.decodeFromString<Assistant>(encoded)

        assertEquals(ReasoningLevel.MAX, decoded.reasoningLevel)
        assertTrue(encoded.contains("\"reasoningLevel\":\"max\""))
    }

    @Test
    fun `legacy v211 data with the removed flag still loads and shows xhigh`() {
        val legacyJson = """{"reasoningLevel":"xhigh","useMaxReasoning":true}"""
        val decoded = json.decodeFromString<Assistant>(legacyJson)

        assertEquals(ReasoningLevel.XHIGH, decoded.reasoningLevel)
    }

    @Test
    fun `every reasoning level round trips`() {
        ReasoningLevel.entries.forEach { level ->
            val encoded = json.encodeToString(Assistant(reasoningLevel = level))
            assertEquals(level, json.decodeFromString<Assistant>(encoded).reasoningLevel)
        }
    }
}
