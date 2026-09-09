package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v289：续跑只处理本次请求新增文字与工具调用，不碰已完成的历史部分。 */
class GenerationResumePartsTest {
    @Test
    fun `续跑文字只取请求开始后的新增部分`() {
        val message = assistant(
            UIMessagePart.Text("旧内容"),
            executedTool(),
            UIMessagePart.Text("本次新增内容"),
        )

        assertEquals("本次新增内容", message.textSincePartCount(2))
        assertEquals("", message.textSincePartCount(3))
    }

    @Test
    fun `续跑清理未执行工具但保留历史和已执行工具`() {
        val executed = executedTool()
        val pending = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "workspace_shell",
            input = "{\"command\":\"echo pending\"}",
        )
        val message = assistant(
            UIMessagePart.Text("旧内容"),
            executed,
            UIMessagePart.Text("本次新增内容"),
            pending,
        )

        val cleaned = message.removeNewUnexecutedToolsAfter(2)

        assertEquals(3, cleaned.parts.size)
        assertTrue(cleaned.parts.contains(executed))
        assertTrue(cleaned.parts.contains(UIMessagePart.Text("本次新增内容")))
        assertFalse(cleaned.parts.contains(pending))
    }

    @Test
    fun `请求开始后的未执行工具才会被清理`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "workspace_shell",
            input = "{}",
        )
        val message = assistant(pending)

        assertEquals(1, message.removeNewUnexecutedToolsAfter(1).parts.size)
        assertEquals(0, message.removeNewUnexecutedToolsAfter(0).parts.size)
    }

    // v295：商汤 deepseek「思考完不给正文」修复 —— 空正文催答的输入构造
    @Test
    fun `空正文催答只发继续不拼空助手消息`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("问题"))),
        )
        val input = buildResumeProviderInput(history, partialText = "", model = me.rerere.ai.provider.Model())
        // 历史 1 条 + 催答 1 条，中间不出现空 assistant 消息（商汤网关对空 content 行为不明示）
        assertEquals(2, input.size)
        assertEquals(MessageRole.USER, input.last().role)
        assertEquals("继续", (input.last().parts.first() as UIMessagePart.Text).text)
        assertFalse(input.any { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun `有半截内容时催答仍然拼回半截再发继续`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("问题"))),
        )
        val input = buildResumeProviderInput(history, partialText = "写了一半", model = me.rerere.ai.provider.Model())
        assertEquals(3, input.size)
        assertEquals(MessageRole.ASSISTANT, input[1].role)
        assertEquals("写了一半", (input[1].parts.first() as UIMessagePart.Text).text)
        assertEquals(MessageRole.USER, input[2].role)
    }

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun executedTool() = UIMessagePart.Tool(
        toolCallId = "executed",
        toolName = "workspace_shell",
        input = "{}",
        output = listOf(UIMessagePart.Text("exit 0")),
    )
}
