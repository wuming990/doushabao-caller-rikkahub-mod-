package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Exercises the real classic workflow and ConversationSession, replacing only model I/O and storage. */
class V1019ClassicCompressionTest {
    private class Store(var persisted: Conversation) : ConversationStore {
        var writes = 0
        var beforeWrite: (suspend () -> Unit)? = null
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) {
            beforeWrite?.invoke()
            persisted = current
            writes++
        }
    }

    private suspend fun fixture(messages: List<UIMessage>, block: suspend (ConversationSession, Store) -> Unit) {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() })
        val store = Store(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(source.id, source, scope, store, {})
        try {
            session.initialize(load = { source })
            block(session, store)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun expectIssue(issue: ClassicCompressionIssue, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $issue")
        } catch (e: ClassicCompressionException) {
            assertEquals(issue, e.issue)
        }
    }

    @Test
    fun `default compresses short history and preserves original nodes`() = runBlocking {
        val messages = listOf(UIMessage.user("question"), UIMessage.assistant("answer"))
        fixture(messages) { session, store ->
            val original = session.state.value.messageNodes
            session.compressClassicHistory { snapshot, sent ->
                assertEquals(messages, sent)
                assertEquals(original, snapshot.messageNodes)
                "summary"
            }
            assertEquals(original, store.persisted.messageNodes.take(2))
            assertEquals(3, store.persisted.messageNodes.size)
            assertTrue(store.persisted.currentMessages.last().isContextCheckpoint)
            assertEquals(listOf("summary"), store.persisted.currentMessages.limitContext(0).map { it.toText() })
            assertEquals(1, store.writes)
        }
    }

    @Test
    fun `keep recent controls compression boundary and retains tail unchanged`() = runBlocking {
        val messages = List(4) { UIMessage.user("message $it") }
        fixture(messages) { session, store ->
            val tail = session.state.value.messageNodes.takeLast(2)
            session.compressClassicHistory(keepRecentMessages = 2) { _, sent ->
                assertEquals(messages.take(2), sent)
                "summary"
            }
            assertEquals(tail, store.persisted.messageNodes.takeLast(2))
            assertEquals(listOf("summary", "message 2", "message 3"), store.persisted.currentMessages.limitContext(0).map { it.toText() })
        }
    }

    @Test
    fun `long message tail is sent intact instead of being cut at 2000 characters`() = runBlocking {
        val text = "x".repeat(8000) + "IMPORTANT_END"
        fixture(listOf(UIMessage.user(text))) { session, _ ->
            session.compressClassicHistory { _, sent ->
                assertEquals(text, sent.single().toText())
                "summary"
            }
        }
    }

    @Test
    fun `history keeps assistant role and executed tool results`() = runBlocking {
        val tool = UIMessagePart.Tool(toolCallId = "tool_1", toolName = "read_file", input = "{}", output = listOf(UIMessagePart.Text("critical result")))
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        fixture(listOf(UIMessage.user("question"), reply)) { session, _ ->
            session.compressClassicHistory { _, sent ->
                assertEquals(MessageRole.ASSISTANT, sent[1].role)
                assertEquals(tool, sent[1].getTools().single())
                "summary"
            }
        }
    }

    @Test
    fun `nothing after latest checkpoint avoids duplicate summary request`() = runBlocking {
        fixture(listOf(UIMessage.user("summary").copy(isContextCheckpoint = true))) { session, store ->
            expectIssue(ClassicCompressionIssue.NOTHING_TO_COMPRESS) {
                session.compressClassicHistory { _, _ -> error("must not request model") }
            }
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun `empty conversation does not request model or write checkpoint`() = runBlocking {
        fixture(emptyList()) { session, store ->
            expectIssue(ClassicCompressionIssue.NOTHING_TO_COMPRESS) {
                session.compressClassicHistory { _, _ -> error("must not request model") }
            }
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun `keeping all messages reports not enough rather than losing messages`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            expectIssue(ClassicCompressionIssue.NOT_ENOUGH_MESSAGES) {
                session.compressClassicHistory(keepRecentMessages = 5) { _, _ -> error("must not request model") }
            }
            assertEquals(listOf("question"), store.persisted.currentMessages.map { it.toText() })
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun `negative keep value follows official zero clamp`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            session.compressClassicHistory(keepRecentMessages = -1) { _, sent ->
                assertEquals("question", sent.single().toText())
                "summary"
            }
            assertEquals(listOf("summary"), store.persisted.currentMessages.limitContext(0).map { it.toText() })
        }
    }

    @Test
    fun `newly appended messages survive summary completion`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            val appended = UIMessage.user("arrived while compressing").toMessageNode()
            session.compressClassicHistory { _, _ ->
                session.update { it.copy(messageNodes = it.messageNodes + appended) }
                "summary"
            }
            assertEquals(appended, store.persisted.messageNodes.last())
            assertEquals(listOf("summary", "arrived while compressing"), store.persisted.currentMessages.limitContext(0).map { it.toText() })
        }
    }

    @Test
    fun `anchor removal never writes summary to the wrong location`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            expectIssue(ClassicCompressionIssue.CONVERSATION_CHANGED) {
                session.compressClassicHistory { _, _ ->
                    session.update { it.copy(messageNodes = emptyList()) }
                    "summary"
                }
            }
            assertEquals(0, store.writes)
            assertTrue(session.state.value.messageNodes.isEmpty())
        }
    }

    @Test
    fun `provider failure leaves original history and storage untouched`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            val original = session.state.value
            val failure = IllegalStateException("upstream failure")
            try {
                session.compressClassicHistory { _, _ -> throw failure }
                fail("Expected provider failure")
            } catch (e: IllegalStateException) {
                assertSame(failure, e)
            }
            assertSame(original, session.state.value)
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun `blank summary does not replace history`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            expectIssue(ClassicCompressionIssue.EMPTY_SUMMARY) {
                session.compressClassicHistory { _, _ -> "  " }
            }
            assertEquals(0, store.writes)
            assertEquals(listOf("question"), session.state.value.currentMessages.map { it.toText() })
        }
    }

    @Test
    fun `cancellation interrupts model I O and never saves a summary`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                session.compressClassicHistory { _, _ ->
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(cancelled)
            assertEquals(0, store.writes)
            assertEquals(listOf("question"), session.state.value.currentMessages.map { it.toText() })
        }
    }

    @Test
    fun `generation started during compression is not overwritten`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            val generating = Job()
            try {
                expectIssue(ClassicCompressionIssue.GENERATING) {
                    session.compressClassicHistory { _, _ ->
                        session.setJob(generating)
                        "summary"
                    }
                }
                assertEquals(0, store.writes)
            } finally { generating.cancel() }
        }
    }

    @Test
    fun `existing generation rejects compression before model request`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            val generating = Job()
            try {
                session.setJob(generating)
                expectIssue(ClassicCompressionIssue.GENERATING) {
                    session.compressClassicHistory { _, _ -> error("must not request model") }
                }
                assertEquals(0, store.writes)
            } finally { generating.cancel() }
        }
    }

    @Test
    fun `second compression sends previous summary and new conversation instead of archived tools`() = runBlocking {
        fixture(listOf(UIMessage.user("old question"))) { session, store ->
            session.compressClassicHistory { _, _ -> "first summary" }
            session.update { it.copy(messageNodes = it.messageNodes + UIMessage.user("new question").toMessageNode()) }
            session.compressClassicHistory { _, sent ->
                assertEquals(listOf("first summary", "new question"), sent.limitContext(0).map { it.toText() })
                "second summary"
            }
            assertEquals(listOf("second summary"), store.persisted.currentMessages.limitContext(0).map { it.toText() })
            assertEquals(4, store.persisted.messageNodes.size)
        }
    }

    @Test
    fun `pending tools in compression range stop before model request`() = runBlocking {
        val tool = UIMessagePart.Tool(toolCallId = "pending", toolName = "ask_user", input = "{}", approvalState = ToolApprovalState.Pending)
        fixture(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))) { session, store ->
            expectIssue(ClassicCompressionIssue.PENDING_TOOLS) {
                session.compressClassicHistory { _, _ -> error("must not request model") }
            }
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun `pending tools in retained tail are not compressed or blocked`() = runBlocking {
        val tool = UIMessagePart.Tool(toolCallId = "pending", toolName = "ask_user", input = "{}", approvalState = ToolApprovalState.Pending)
        val tail = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        fixture(listOf(UIMessage.user("question"), tail)) { session, store ->
            session.compressClassicHistory(keepRecentMessages = 1) { _, sent ->
                assertEquals(listOf("question"), sent.map { it.toText() })
                "summary"
            }
            assertEquals(tail, store.persisted.currentMessages.last())
            assertEquals(1, store.writes)
        }
    }

    @Test
    fun `archived pending tools do not block compressing new messages`() = runBlocking {
        val tool = UIMessagePart.Tool(toolCallId = "archived", toolName = "ask_user", input = "{}", approvalState = ToolApprovalState.Pending)
        fixture(listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
            UIMessage.user("previous summary").copy(isContextCheckpoint = true),
            UIMessage.user("new question"),
        )) { session, store ->
            session.compressClassicHistory { _, sent ->
                assertEquals(listOf("previous summary", "new question"), sent.limitContext(0).map { it.toText() })
                "new summary"
            }
            assertEquals(1, store.writes)
        }
    }

    @Test
    fun `smart input after classic does not replay archived original history`() = runBlocking {
        fixture(listOf(UIMessage.user("archived original"))) { session, _ ->
            session.compressClassicHistory { _, _ -> "summary" }
            assertEquals(listOf("summary"), smartCompressionHistory(session.state.value).map { it.toText() })
        }
    }

    @Test
    fun `smart input after classic includes summary and retained messages`() = runBlocking {
        fixture(listOf(UIMessage.user("archived original"), UIMessage.user("retained"))) { session, _ ->
            session.compressClassicHistory(keepRecentMessages = 1) { _, _ -> "summary" }
            assertEquals(listOf("summary", "retained"), smartCompressionHistory(session.state.value).map { it.toText() })
        }
    }

    @Test
    fun `smart input without classic checkpoint preserves old valid-message behavior`() = runBlocking {
        val messages = listOf(UIMessage.user("original"), UIMessage.user(""), UIMessage.assistant("answer"))
        fixture(messages) { session, _ ->
            assertEquals(listOf(messages[0], messages[2]), smartCompressionHistory(session.state.value))
        }
    }

    @Test
    fun `future-dated retained reply never restores pre-compression usage`() = runBlocking {
        val reply = UIMessage.assistant("answer").copy(
            createdAt = LocalDateTime(2099, 1, 1, 0, 0),
            usage = TokenUsage(promptTokens = 899_000, completionTokens = 1_000),
        )
        fixture(listOf(UIMessage.user("question"), reply)) { session, _ ->
            session.compressClassicHistory(keepRecentMessages = 1) { _, _ -> "summary" }
            assertNull(reportedConversationTokens(session.state.value))
            assertEquals(reply, session.state.value.currentMessages.last())
        }
    }

    @Test
    fun `equal-clock retained reply never restores pre-compression usage`() = runBlocking {
        val reply = UIMessage.assistant("answer").copy(usage = TokenUsage(promptTokens = 899_000, completionTokens = 1_000))
        fixture(listOf(UIMessage.user("question"), reply)) { session, _ ->
            session.compressClassicHistory(keepRecentMessages = 1) { _, _ -> "summary" }
            val checkpointTime = session.state.value.currentMessages.first { it.isContextCheckpoint }.createdAt
            session.update { latest ->
                latest.copy(messageNodes = latest.messageNodes.dropLast(1) + reply.copy(createdAt = checkpointTime).toMessageNode())
            }
            assertNull(reportedConversationTokens(session.state.value))
        }
    }

    @Test
    fun `regenerated retained reply can report fresh usage even when token counts match old request`() = runBlocking {
        val reply = UIMessage.assistant("answer").copy(
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
            usage = TokenUsage(promptTokens = 123, completionTokens = 4),
        )
        fixture(listOf(UIMessage.user("question"), reply)) { session, _ ->
            session.compressClassicHistory(keepRecentMessages = 1) { _, _ -> "summary" }
            session.update { latest ->
                latest.copy(messageNodes = latest.messageNodes.dropLast(1) + reply.copy(finishedAt = LocalDateTime(2026, 10, 27, 22, 0)).toMessageNode())
            }
            assertEquals(127L, reportedConversationTokens(session.state.value))
        }
    }

    @Test
    fun `old usage baseline survives saving and loading conversation JSON`() = runBlocking {
        val reply = UIMessage.assistant("answer").copy(
            createdAt = LocalDateTime(2099, 1, 1, 0, 0),
            usage = TokenUsage(promptTokens = 899_000, completionTokens = 1_000),
        )
        fixture(listOf(UIMessage.user("question"), reply)) { session, _ ->
            session.compressClassicHistory(keepRecentMessages = 1) { _, _ -> "summary" }
            val restored = Json.decodeFromString<Conversation>(Json.encodeToString(session.state.value))
            assertNull(reportedConversationTokens(restored))
            assertEquals(TokenUsage(promptTokens = 899_000, completionTokens = 1_000), restored.currentMessages.last().usage)
            assertEquals(listOf("summary", "answer"), smartCompressionHistory(restored).map { it.toText() })
        }
    }

    @Test
    fun `editing compressed content with the same anchor refuses stale summary`() = runBlocking {
        fixture(listOf(UIMessage.user("question"), UIMessage.assistant("answer"))) { session, store ->
            expectIssue(ClassicCompressionIssue.CONVERSATION_CHANGED) {
                session.compressClassicHistory { _, _ ->
                    session.update { latest ->
                        latest.copy(messageNodes = latest.messageNodes.toMutableList().also { nodes ->
                            nodes[0] = nodes[0].copy(messages = listOf(nodes[0].currentMessage.copy(parts = listOf(UIMessagePart.Text("edited question")))))
                        })
                    }
                    "stale summary"
                }
            }
            assertEquals(0, store.writes)
            assertEquals("edited question", session.state.value.currentMessages.first().toText())
        }
    }

    @Test
    fun `switching compressed branch while anchor stays refuses stale summary`() = runBlocking {
        fixture(listOf(UIMessage.user("question"), UIMessage.assistant("answer"))) { session, store ->
            expectIssue(ClassicCompressionIssue.CONVERSATION_CHANGED) {
                session.compressClassicHistory { _, _ ->
                    session.update { latest ->
                        latest.copy(messageNodes = latest.messageNodes.toMutableList().also { nodes ->
                            nodes[0] = nodes[0].copy(messages = nodes[0].messages + UIMessage.user("other branch"), selectIndex = 1)
                        })
                    }
                    "stale summary"
                }
            }
            assertEquals(0, store.writes)
            assertEquals("other branch", session.state.value.currentMessages.first().toText())
        }
    }

    @Test
    fun `storage write failure removes only uncommitted summary and retains new messages`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            store.beforeWrite = {
                session.update { it.copy(messageNodes = it.messageNodes + UIMessage.user("new message").toMessageNode()) }
                throw IllegalStateException("disk write failed")
            }
            try {
                session.compressClassicHistory { _, _ -> "summary" }
                fail("Expected storage failure")
            } catch (e: IllegalStateException) {
                assertEquals("disk write failed", e.message)
            }
            assertEquals(listOf("question", "new message"), session.state.value.currentMessages.map { it.toText() })
            assertFalse(session.state.value.currentMessages.any { it.isContextCheckpoint })
            assertFalse(store.persisted.currentMessages.any { it.isContextCheckpoint })
        }
    }

    @Test
    fun `cancellation during commit finishes consistent storage instead of half committing`() = runBlocking {
        fixture(listOf(UIMessage.user("question"))) { session, store ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            store.beforeWrite = { entered.complete(Unit); release.await() }
            val job = launch(start = CoroutineStart.UNDISPATCHED) { session.compressClassicHistory { _, _ -> "summary" } }
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertEquals(session.state.value, store.persisted)
            assertEquals(1, store.writes)
            assertTrue(store.persisted.currentMessages.last().isContextCheckpoint)
        }
    }

    @Test
    fun `official prompt appends user instructions without consuming smart custom prompt`() {
        val prompt = buildCompressPrompt(additionalPrompt = "Keep the critical file path", locale = "中文")
        assertTrue(prompt.endsWith("Additional instructions from user: Keep the critical file path"))
        assertTrue(prompt.contains("## Tool Results and Artifacts"))
        assertTrue(prompt.contains("## Open Items"))
        assertFalse(prompt.contains("{content}"))
    }
}
