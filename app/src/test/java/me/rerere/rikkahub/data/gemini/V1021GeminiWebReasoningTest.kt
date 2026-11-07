package me.rerere.rikkahub.data.gemini

import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavior regressions for Gemini Web native thought snapshots. All protocol rows are local fixtures. */
class V1021GeminiWebReasoningTest {
    @Test
    fun `native thought and body are rendered as separate parts`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_MAIN", "THOUGHT_MARKER_MAIN"))

        assertEquals(listOf("BODY_MARKER_MAIN"), stream.textParts())
        assertEquals(listOf("THOUGHT_MARKER_MAIN"), stream.reasoningParts().map { it.reasoning })
    }

    @Test
    fun `cumulative native thought snapshots A AB ABC render only ABC`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_A", "THOUGHT_MARKER_A"))
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_AB", "THOUGHT_MARKER_AB"))
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_ABC", "THOUGHT_MARKER_ABC", status = 2))

        assertEquals(listOf("THOUGHT_MARKER_ABC"), stream.reasoningParts().map { it.reasoning })
        assertEquals(listOf("BODY_MARKER_ABC"), stream.textParts())
    }

    @Test
    fun `rewritten shorter native thought replaces the previous draft`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_LONG", "THOUGHT_MARKER_LONG_DRAFT"))
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_SHORT", "THOUGHT_MARKER_SHORT", status = 2))

        assertEquals(listOf("THOUGHT_MARKER_SHORT"), stream.reasoningParts().map { it.reasoning })
        assertEquals(listOf("BODY_MARKER_SHORT"), stream.textParts())
    }

    @Test
    fun `empty native thought clears it while later body continues updating`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_FIRST", "THOUGHT_MARKER_WITHDRAWN"))
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_LATEST", "", status = 2))

        assertTrue(stream.reasoningParts().isEmpty())
        assertEquals(listOf("BODY_MARKER_LATEST"), stream.textParts())
    }

    @Test
    fun `new rcid without thought clears the old candidate thought`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-old", "BODY_MARKER_OLD", "THOUGHT_MARKER_OLD"))
        stream.accept(candidateRow("rcid-new", "BODY_MARKER_NEW", thought = null, status = 2))

        assertTrue(stream.reasoningParts().none { it.reasoning.contains("THOUGHT_MARKER_OLD") })
        assertEquals(listOf("BODY_MARKER_NEW"), stream.textParts())
    }

    @Test
    fun `new rcid thought replaces old thought instead of leaving multiple parts`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-old", "BODY_MARKER_OLD", "THOUGHT_MARKER_OLD"))
        stream.accept(candidateRow("rcid-new", "BODY_MARKER_NEW", "THOUGHT_MARKER_NEW", status = 2))

        assertEquals(listOf("THOUGHT_MARKER_NEW"), stream.reasoningParts().map { it.reasoning })
        assertEquals(listOf("BODY_MARKER_NEW"), stream.textParts())
    }

    @Test
    fun `sparse frame without thought preserves the selected candidate thought`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_FIRST", "THOUGHT_MARKER_KEEP"))
        // This candidate ends at slot 8: native thought slot 37 is absent, not synthesized by a parser helper.
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_LATEST", thought = null, status = 2))

        assertEquals(listOf("THOUGHT_MARKER_KEEP"), stream.reasoningParts().map { it.reasoning })
        assertEquals(listOf("BODY_MARKER_LATEST"), stream.textParts())
    }

    @Test
    fun `native thought follows selected rcid and never mixes another candidate`() {
        val stream = TestStream()
        stream.accept(outerRow(
            candidate("rcid-selected", "BODY_MARKER_SELECTED_1", "THOUGHT_MARKER_SELECTED_1"),
            candidate("rcid-other", "BODY_MARKER_OTHER", "THOUGHT_MARKER_OTHER"),
        ))
        stream.accept(outerRow(
            candidate("rcid-other", "BODY_MARKER_OTHER_LATEST", "THOUGHT_MARKER_OTHER_LATEST"),
            candidate("rcid-selected", "BODY_MARKER_SELECTED_LATEST", "THOUGHT_MARKER_SELECTED_LATEST", status = 2),
        ))

        assertEquals(listOf("THOUGHT_MARKER_SELECTED_LATEST"), stream.reasoningParts().map { it.reasoning })
        assertEquals(listOf("BODY_MARKER_SELECTED_LATEST"), stream.textParts())
    }

    @Test
    fun `thought only remains visible at eof while finish reports missing body`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", body = null, thought = "THOUGHT_MARKER_ONLY"))

        assertEquals(listOf("THOUGHT_MARKER_ONLY"), stream.reasoningParts().map { it.reasoning })
        val failure = try {
            stream.finish()
            null
        } catch (expected: IOException) {
            expected
        }
        assertNotNull(failure)
        assertEquals(listOf("THOUGHT_MARKER_ONLY"), stream.reasoningParts().map { it.reasoning })
        assertTrue(stream.finishedMessages().isEmpty())
    }

    @Test
    fun `ordinary body containing thought summary wording stays ordinary text`() {
        val stream = TestStream()
        val body = "BODY_MARKER ordinary 思考摘要 BODY_MARKER still-text"
        stream.accept(candidateRow("rcid-main", body, thought = null, status = 2))

        assertEquals(listOf(body), stream.textParts())
        assertTrue(stream.reasoningParts().isEmpty())
    }

    @Test
    fun `finish closes an unfinished native reasoning part`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_MAIN", "THOUGHT_MARKER_MAIN"))

        stream.finish()

        assertEquals(1, stream.reasoningParts().size)
        assertNotNull(stream.reasoningParts().single().finishedAt)
        assertNotNull(stream.messages.last().finishedAt)
    }

    @Test
    fun `interleaved native reasoning and body updates retain one latest part each`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_1", "THOUGHT_MARKER_1"))
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_2", "THOUGHT_MARKER_2"))
        stream.accept(candidateRow("rcid-main", "BODY_MARKER_3", "THOUGHT_MARKER_3", status = 2))

        assertEquals(listOf("THOUGHT_MARKER_3"), stream.reasoningParts().map { it.reasoning })
        assertEquals(listOf("BODY_MARKER_3"), stream.textParts())
    }

    @Test
    fun `thought-only update must not revoke already completed body`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY", "THOUGHT_A", status = 2))
        stream.accept(candidateRow("rcid-main", "BODY", "THOUGHT_B", status = null))
        val finish = stream.finish()
        assertEquals("stop", finish.finishReason)
        assertTrue(!finish.terminatedWithoutSentinel)
        assertEquals(listOf("THOUGHT_B"), stream.reasoningParts().map { it.reasoning })
    }

    @Test
    fun `sparse unchanged frame preserves known completion and thought`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY", "THOUGHT", status = 2))
        stream.accept(candidateRow("rcid-main", "BODY", thought = null, status = null))
        assertEquals("stop", stream.finish().finishReason)
        assertEquals(listOf("THOUGHT"), stream.reasoningParts().map { it.reasoning })
    }

    @Test
    fun `withdrawn native thought does not disable existing tagged text separation`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-old", "OLD_BODY", "OLD_THOUGHT"))
        val tagged = "<" + "think>TAGGED_THOUGHT</" + "think>NEW_BODY"
        stream.accept(candidateRow("rcid-new", tagged, thought = null, status = 2))
        val shown = stream.messages.transformThinkTags(Instant.parse("2026-10-31T00:00:00Z"), false).last()
        assertEquals(listOf("TAGGED_THOUGHT"), shown.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
        assertEquals(listOf("NEW_BODY"), shown.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test
    fun `withdrawing native thought reindexes existing text and image parts`() {
        val decoder = GeminiWebStreamDecoder(Json.Default)
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        fun apply(chunk: StreamChunk) { messages = handler.handle(messages, chunk) }
        decoder.accept(candidateRow("rcid-main", null, "THOUGHT")).forEach(::apply)
        apply(StreamChunk.TextDelta("answer", "B"))
        apply(StreamChunk.ImageStart("image"))
        apply(StreamChunk.ImageDelta("image", "A"))
        decoder.accept(candidateRow("rcid-main", null, "")).forEach(::apply)
        apply(StreamChunk.TextDelta("answer", "ODY"))
        apply(StreamChunk.ImageDelta("image", "B"))
        val parts = messages.last().parts
        assertTrue(parts.none { it is UIMessagePart.Reasoning })
        assertEquals(listOf("BODY"), parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertEquals(listOf("data:image/png;base64,AB"), parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
    }

    @Test
    fun `native snapshot event round trips through the real event serializer`() {
        val decoder = GeminiWebStreamDecoder(Json.Default)
        val event = decoder.accept(candidateRow("rcid-main", null, "THOUGHT")).single()
        val restored = Json.decodeFromString<StreamChunk>(Json.encodeToString<StreamChunk>(event))
        assertEquals(event, restored)
        val initial = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val message = StreamChunkHandler().handle(initial, restored).last()
        assertEquals(listOf("THOUGHT"), message.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
        assertTrue(message.parts.none { it is UIMessagePart.Text })
    }

    @Test
    fun `clearing one native stream preserves another reasoning stream and its index`() {
        val decoder = GeminiWebStreamDecoder(Json.Default)
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        fun apply(chunk: StreamChunk) { messages = handler.handle(messages, chunk) }
        decoder.accept(candidateRow("rcid-main", null, "WITHDRAWN")).forEach(::apply)
        apply(StreamChunk.ReasoningDelta("other-reason", "KEEP"))
        decoder.accept(candidateRow("rcid-main", null, "")).forEach(::apply)
        apply(StreamChunk.ReasoningDelta("other-reason", "_LATEST"))
        assertEquals(listOf("KEEP_LATEST"), messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
    }

    @Test
    fun `explicit active state still revokes completion after native update`() {
        val stream = TestStream()
        stream.accept(candidateRow("rcid-main", "BODY", "THOUGHT_A", status = 2))
        stream.accept(candidateRow("rcid-main", "BODY", "THOUGHT_B", status = 1))
        val finish = stream.finish()
        assertEquals(null, finish.finishReason)
        assertTrue(finish.terminatedWithoutSentinel)
    }

    @Test
    fun `empty thought withdrawal must not start generation timing`() {
        var now = 0L
        val handler = StreamChunkHandler(nowNanos = { now })
        val decoder = GeminiWebStreamDecoder(Json.Default)
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        now = 100_000_000L
        decoder.accept(candidateRow("rcid-main", null, "")).forEach { messages = handler.handle(messages, it) }
        now = 200_000_000L
        messages = handler.handle(messages, StreamChunk.TextDelta("body", "BODY"))
        now = 300_000_000L
        handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))
        assertEquals(100L, handler.roundTiming.generationMs)
        assertEquals(300L, handler.roundTiming.responseMs)
    }

    @Test
    fun `nonempty native thought counts as first content before body`() {
        var now = 0L
        val handler = StreamChunkHandler(nowNanos = { now })
        val decoder = GeminiWebStreamDecoder(Json.Default)
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        now = 100_000_000L
        decoder.accept(candidateRow("rcid-main", null, "THOUGHT")).forEach { messages = handler.handle(messages, it) }
        now = 200_000_000L
        messages = handler.handle(messages, StreamChunk.TextDelta("body", "BODY"))
        now = 300_000_000L
        handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))
        assertEquals(200L, handler.roundTiming.generationMs)
        assertEquals(300L, handler.roundTiming.responseMs)
    }

    private class TestStream {
        private val decoder = GeminiWebStreamDecoder(Json.Default)
        private val handler = StreamChunkHandler()
        var messages: List<UIMessage> = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        )
            private set

        fun accept(row: JsonArray) {
            decoder.accept(row).forEach { chunk ->
                messages = handler.handle(messages, chunk)
            }
        }

        fun finish(): StreamChunk.Finish {
            val chunk = decoder.finish("gemini-web")
            messages = handler.handle(messages, chunk)
            return chunk
        }

        fun textParts(): List<String> = messages.last().parts
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }

        fun reasoningParts(): List<UIMessagePart.Reasoning> = messages.last().parts
            .filterIsInstance<UIMessagePart.Reasoning>()

        fun finishedMessages(): List<UIMessage> = messages.filter { it.finishedAt != null }
    }

    /**
     * Constructs the literal protocol path under test:
     * outer row [0]="wrb.fr", [2]=serialized payload; payload [4]=candidate list;
     * candidate [0]=rcid, [1][0]=body, [8][0]=status, [37][0][0]=native thought.
     * Every intervening slot is an explicit JSON null, so the fixture shape is auditable.
     */
    private fun candidateRow(
        rcid: String,
        body: String?,
        thought: String?,
        status: Int? = 1,
    ): JsonArray = outerRow(candidate(rcid, body, thought, status))

    private fun outerRow(vararg candidates: JsonArray): JsonArray {
        val payload = sparseArray(26, 4 to JsonArray(candidates.toList()))
        return JsonArray(listOf(
            JsonPrimitive("wrb.fr"),
            JsonPrimitive("StreamGenerate"),
            JsonPrimitive(payload.toString()),
        ))
    }

    private fun candidate(
        rcid: String,
        body: String?,
        thought: String?,
        status: Int? = 1,
    ): JsonArray {
        val fields = mutableListOf<Pair<Int, JsonElement>>(0 to JsonPrimitive(rcid))
        body?.let { fields += 1 to JsonArray(listOf(JsonPrimitive(it))) }
        status?.let { fields += 8 to JsonArray(listOf(JsonPrimitive(it))) }
        thought?.let {
            fields += 37 to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(it)))))
        }
        val size = fields.maxOf { it.first } + 1
        return sparseArray(size, *fields.toTypedArray())
    }

    private fun sparseArray(size: Int, vararg fields: Pair<Int, JsonElement>): JsonArray =
        JsonArray(MutableList<JsonElement>(size) { JsonNull }.apply {
            fields.forEach { (index, value) -> this[index] = value }
        })
}
