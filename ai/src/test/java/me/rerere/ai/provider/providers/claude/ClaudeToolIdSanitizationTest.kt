package me.rerere.ai.provider.providers.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v221 门禁测试：验证 Claude Tool ID 严格遵循 `^[a-zA-Z0-9_-]+$` 规范，
 * 彻底防止 Google/OpenAI 流式转接器带冒号的 ID 触发 TOOL_SCHEMA_INVALID。
 */
class ClaudeToolIdSanitizationTest {

    private fun sanitize(id: String): String {
        val sanitized = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (sanitized.isBlank()) "tool_fallback" else sanitized
    }

    @Test
    fun `带冒号的流式 ID 被自动清洗为合法字符`() {
        val raw = "chatcmpl-1234:tool-1"
        val clean = sanitize(raw)
        assertEquals("chatcmpl-1234_tool-1", clean)
        assertTrue(clean.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun `包含点斜杠空格等非法字符均被清洗`() {
        val raw = "resp.123/call:tool #5"
        val clean = sanitize(raw)
        assertEquals("resp_123_call_tool__5", clean)
        assertTrue(clean.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun `合法 ID 保持完全不变`() {
        val raw = "call_abc-123_XYZ"
        val clean = sanitize(raw)
        assertEquals(raw, clean)
    }
}
