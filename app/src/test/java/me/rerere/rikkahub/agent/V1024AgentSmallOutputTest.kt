package me.rerere.rikkahub.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

/** v1024审查回归：只在子代理工具入口补小于4KB的用户值，不动共享GenerationLoop。 */
class V1024AgentSmallOutputTest {
    @Suppress("UNCHECKED_CAST")
    private fun limit(parts: List<UIMessagePart>, chars: Int): List<UIMessagePart> {
        val type = try { Class.forName("me.rerere.rikkahub.agent.tools.AgentSmallOutputLimitKt") }
            catch (_: ClassNotFoundException) { throw AssertionError("缺少子代理小返回值适配，1KB仍会被共享层放宽成4KB") }
        return type.getDeclaredMethod("limitSmallAgentToolOutput", List::class.java, Int::class.javaPrimitiveType)
            .invoke(null, parts, chars) as List<UIMessagePart>
    }
    @Test fun `用户选1KB时通用工具返回不能再带回3KB`() {
        val result = limit(listOf(UIMessagePart.Text("x".repeat(3000))), 1024)
        assertTrue(result.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length } <= 1024)
        assertTrue(result.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }.contains("truncated"))
    }
    @Test fun `小返回值截断也必须保持JSON有效并明确不是完整结果`() {
        val json = "{\"entries\":[\"" + "文件\\\"内容".repeat(1200) + "\"]}"
        val result = limit(listOf(UIMessagePart.Text(json)), 1024)
        val text = result.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertTrue(text.length <= 1024)
        assertEquals("true", Json.parseToJsonElement(text).jsonObject["truncated"]?.jsonPrimitive?.content)
    }
    @Test fun `默认值和不限原样交给旧流程且不丢非文本附件`() {
        val original = listOf<UIMessagePart>(UIMessagePart.Text("x".repeat(5000)), UIMessagePart.Image("file:///not-opened-test.png"))
        assertSame(original, limit(original, 64 * 1024))
        assertSame(original, limit(original, Int.MAX_VALUE))
        val limited = limit(original, 1024)
        assertEquals(original.last(), limited.last())
    }
}
