package me.rerere.rikkahub.data.gemini

import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for issue #48: [GeminiStreamChunkAdapter] must forward a Tool part's
 * metadata onto the emitted [StreamChunk.ToolCallStart]/[StreamChunk.ToolCallDelta], or a
 * `thoughtSignature` that Gemini attached directly to the functionCall part (rather than to a
 * preceding thought part) is dropped, and the continuation request comes back
 * `400: Function call is missing a thought_signature`.
 */
class GeminiStreamChunkAdapterTest {

    @Test
    fun `tool part metadata is forwarded to ToolCallStart and ToolCallDelta`() {
        val metadata = GoogleThoughtMetadata(thoughtSignature = "sig-xyz").toMetadata()
        val chunk = MessageChunk(
            id = "resp-1",
            model = "gemini-2.5-pro",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "tool-1",
                                toolName = "search",
                                input = "{}",
                                metadata = metadata,
                            )
                        ),
                    ),
                    message = null,
                    finishReason = null,
                )
            ),
        )

        val out = GeminiStreamChunkAdapter().translate(chunk)

        val start = out.filterIsInstance<StreamChunk.ToolCallStart>().single()
        val delta = out.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals(metadata, start.metadata)
        assertEquals(metadata, delta.metadata)
    }

    // ---- v308：内置工具（谷歌自家搜索）的服务端调用片段必须翻译，不许掉进 else 被丢弃 ----

    private fun chunkWith(parts: List<UIMessagePart>) = MessageChunk(
        id = "resp-1",
        model = "gemini-3.1-pro-low",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                message = null,
                finishReason = null,
            )
        ),
    )

    @Test
    fun `an in-progress server tool becomes ServerToolStart`() {
        val metadata = GoogleThoughtMetadata(thoughtSignature = "sig-1").toMetadata()
        val out = GeminiStreamChunkAdapter().translate(
            chunkWith(
                listOf(
                    UIMessagePart.ServerTool(
                        toolCallId = "srv-1",
                        toolName = "GOOGLE_SEARCH_WEB",
                        input = buildJsonObject { put("queries", "latest news") },
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = metadata,
                    )
                )
            )
        )

        val start = out.filterIsInstance<StreamChunk.ServerToolStart>().single()
        assertEquals("srv-1", start.id)
        assertEquals("GOOGLE_SEARCH_WEB", start.toolName)
        assertEquals(metadata, start.metadata)
        assertTrue(out.none { it is StreamChunk.ServerToolEnd })
    }

    @Test
    fun `a finished server tool becomes ServerToolEnd with its output`() {
        val out = GeminiStreamChunkAdapter().translate(
            chunkWith(
                listOf(
                    UIMessagePart.ServerTool(
                        toolCallId = "srv-1",
                        toolName = "GOOGLE_SEARCH_WEB",
                        input = buildJsonObject { put("queries", "latest news") },
                        output = buildJsonObject { put("results", "ok") },
                        status = ServerToolStatus.COMPLETED,
                    )
                )
            )
        )

        val end = out.filterIsInstance<StreamChunk.ServerToolEnd>().single()
        assertEquals("srv-1", end.id)
        assertEquals(ServerToolStatus.COMPLETED, end.status)
        assertTrue(out.none { it is StreamChunk.ServerToolStart })
    }

    @Test
    fun `a mixed reasoning plus server tool plus text stream keeps all three kinds`() {
        // 真正的回归点：ServerTool 若仍掉进 else 分支，中间的搜索调用会整个消失，
        // 用户看到的就是「搜索能用但过程不可见」。
        val adapter = GeminiStreamChunkAdapter()
        val out = adapter.translate(
            chunkWith(
                listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking hard"),
                    UIMessagePart.ServerTool(
                        toolCallId = "srv-2",
                        toolName = "GOOGLE_SEARCH_WEB",
                        status = ServerToolStatus.IN_PROGRESS,
                    ),
                    UIMessagePart.Text("the answer"),
                )
            )
        )
        assertTrue(out.any { it is StreamChunk.ReasoningDelta })
        assertTrue(out.any { it is StreamChunk.ServerToolStart })
        val text = out.filterIsInstance<StreamChunk.TextDelta>().single()
        assertEquals("the answer", text.text)
    }
}
