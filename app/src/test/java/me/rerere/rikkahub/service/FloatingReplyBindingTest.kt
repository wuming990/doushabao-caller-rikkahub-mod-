package me.rerere.rikkahub.service

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.V1008NestedScrollGuardTest
import me.rerere.rikkahub.ui.floating.buildFloatingChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingReplyBindingTest {
    private class MemoryConversationStore(var persisted: Conversation?) : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }

        override suspend fun write(previous: Conversation?, current: Conversation) {
            persisted = current
        }
    }

    private suspend fun newSession(
        scope: CoroutineScope,
        conversation: Conversation = Conversation.ofId(Uuid.random()),
    ): ConversationSession {
        val session = ConversationSession(
            id = conversation.id,
            initial = conversation,
            scope = scope,
            store = MemoryConversationStore(conversation),
            onIdle = {},
        )
        session.initialize(load = { conversation })
        return session
    }

    private fun state(session: ConversationSession, generating: Boolean = true) =
        buildFloatingChatUiState(
            conversation = session.state.value,
            replyMessageId = session.replyMessageId.value,
            generating = generating,
            processingStatus = null,
            queue = InboxState(),
            error = null,
        )

    /** 先模拟消息 chunk 落入会话，再更新悬浮窗当前回复 ID 投影。 */
    private fun applyChunk(session: ConversationSession, messages: List<UIMessage>) {
        session.update { it.updateCurrentMessages(messages) }
        session.trackFloatingReply(messages)
    }

    @Test
    fun `first reasoning chunk is bound and shown as thinking`() = runTest {
        val session = newSession(backgroundScope)
        val reasoning = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("thinking so far", finishedAt = null)),
        )

        applyChunk(session, listOf(reasoning))

        val floating = state(session)
        assertEquals(reasoning.id, session.replyMessageId.value)
        assertEquals("thinking so far", floating.preview)
        assertTrue(floating.previewIsReasoning)
        assertEquals(GenerationPhase.Thinking("thinking so far"), floating.phase)
    }

    @Test
    fun `same message ID updates from partial to complete text`() = runTest {
        val session = newSession(backgroundScope)
        val partial = UIMessage.assistant("The answer is")
        applyChunk(session, listOf(partial))

        val firstState = state(session)
        assertEquals(partial.id, session.replyMessageId.value)
        assertEquals("The answer is", firstState.preview)

        val complete = UIMessage.assistant("The answer is 42.").copy(id = partial.id)
        applyChunk(session, listOf(complete))

        val latestState = state(session)
        assertEquals(partial.id, session.replyMessageId.value)
        assertEquals("The answer is 42.", latestState.preview)
    }

    @Test
    fun `shorter full snapshot replaces old text without appending it`() = runTest {
        val session = newSession(backgroundScope)
        val full = UIMessage.assistant("A much longer earlier snapshot")
        applyChunk(session, listOf(full))

        val shorter = UIMessage.assistant("Short snapshot").copy(id = full.id)
        applyChunk(session, listOf(shorter))

        val floating = state(session)
        assertEquals(full.id, session.replyMessageId.value)
        assertEquals("Short snapshot", floating.preview)
        assertFalse(floating.preview.contains("A much longer earlier snapshot"))
    }

    @Test
    fun `empty next turn clears the previous reply ID and preview`() = runTest {
        val session = newSession(backgroundScope)
        val previousReply = UIMessage.assistant("previous turn")
        applyChunk(session, listOf(previousReply))
        assertEquals(previousReply.id, session.replyMessageId.value)

        session.trackFloatingReply(emptyList())

        val floating = state(session)
        assertNull(session.replyMessageId.value)
        assertFalse(floating.hasReply)
        assertEquals("", floating.preview)
    }

    @Test
    fun `history ending in a user message does not bind the previous assistant`() = runTest {
        val session = newSession(backgroundScope)
        val previousReply = UIMessage.assistant("older assistant reply")
        val latestUser = UIMessage.user("latest user input")
        applyChunk(session, listOf(previousReply, latestUser))

        val floating = state(session)
        assertNull(session.replyMessageId.value)
        assertFalse(floating.hasReply)
        assertEquals("", floating.preview)
    }

    @Test
    fun `continuation ending in assistant retains that message ID and preview`() = runTest {
        val user = UIMessage.user("continue this answer")
        val continuedReply = UIMessage.assistant("continued answer")
        val conversation = Conversation.ofId(Uuid.random())
            .updateCurrentMessages(listOf(user, continuedReply))
        val session = newSession(backgroundScope, conversation)

        session.trackFloatingReply(session.state.value.currentMessages)

        val floating = state(session)
        assertEquals(continuedReply.id, session.replyMessageId.value)
        assertEquals("continued answer", floating.preview)
        assertEquals(GenerationPhase.Writing("continued answer"), floating.phase)
    }

    @Test
    fun `regenerating an earlier branch binds its new ID rather than the history tail`() = runTest {
        val id = Uuid.random()
        val firstUser = UIMessage.user("first")
        val oldBranch = UIMessage.assistant("old answer")
        val regeneratedReply = UIMessage.assistant("new answer")
        val branchNode = MessageNode(
            messages = listOf(oldBranch),
            selectIndex = 0,
        )
        val secondUser = UIMessage.user("second")
        val historyTail = UIMessage.assistant("tail answer")
        val conversation = Conversation.ofId(
            id,
            messages = listOf(
                firstUser.toMessageNode(),
                branchNode,
                secondUser.toMessageNode(),
                historyTail.toMessageNode(),
            ),
        )
        val session = newSession(backgroundScope, conversation)

        // Exercise the same updateCurrentMessages -> trackFloatingReply sequence as the real collector:
        // the new branch does not exist before this chunk, and the later history must survive the write.
        assertEquals(listOf(oldBranch.id), session.state.value.messageNodes[1].messages.map { it.id })
        applyChunk(session, listOf(firstUser, regeneratedReply))
        assertEquals(
            listOf(oldBranch.id, regeneratedReply.id),
            session.state.value.messageNodes[1].messages.map { it.id },
        )
        assertEquals(historyTail.id, session.state.value.currentMessages.last().id)
        assertEquals(4, session.state.value.messageNodes.size)

        var floating = state(session)
        assertEquals(regeneratedReply.id, session.replyMessageId.value)
        assertEquals("new answer", floating.preview)
        assertFalse(floating.preview.contains("tail answer"))

        session.update { current ->
            current.copy(
                messageNodes = current.messageNodes.map { node ->
                    if (node.id == branchNode.id) node.copy(selectIndex = 0) else node
                }
            )
        }
        floating = state(session)
        assertEquals("old answer", session.state.value.currentMessages[1].toText())
        assertEquals(regeneratedReply.id, session.replyMessageId.value)
        assertEquals("new answer", floating.preview)
    }

    @Test
    fun `user checkpoint clears old binding and the next assistant binds without deleting history`() = runTest {
        val user = UIMessage.user("original question")
        val oldReply = UIMessage.assistant("before checkpoint")
        val conversation = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(user, oldReply))
        val session = newSession(backgroundScope, conversation)
        session.trackFloatingReply(session.state.value.currentMessages)
        assertEquals(oldReply.id, session.replyMessageId.value)

        val checkpoint = UIMessage.user("checkpoint summary").copy(isContextCheckpoint = true)
        session.update { it.updateCurrentMessages(it.currentMessages + checkpoint) }
        session.trackFloatingReply(session.state.value.currentMessages)
        assertNull(session.replyMessageId.value)

        val newReply = UIMessage.assistant("after checkpoint")
        session.update { it.updateCurrentMessages(it.currentMessages + newReply) }
        session.trackFloatingReply(session.state.value.currentMessages)

        val floating = state(session)
        assertEquals(newReply.id, session.replyMessageId.value)
        assertEquals("after checkpoint", floating.preview)
        assertEquals(
            listOf(user.id, oldReply.id, checkpoint.id, newReply.id),
            session.state.value.currentMessages.map { it.id },
        )
    }

    @Test
    fun `finish generation preserves the latest bound preview`() = runTest {
        val session = newSession(backgroundScope)
        val latestReply = UIMessage.assistant("latest complete preview")
        applyChunk(session, listOf(latestReply))

        session.finishGeneration()

        val floating = state(session, generating = false)
        assertEquals(latestReply.id, session.replyMessageId.value)
        assertEquals("latest complete preview", floating.preview)
        assertTrue(floating.hasReply)
    }

    @Test
    fun `zero-text tool reply keeps its own ID without borrowing earlier text`() = runTest {
        val session = newSession(backgroundScope)
        val previousReply = UIMessage.assistant("previous visible answer")
        val toolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "shell",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        applyChunk(session, listOf(previousReply, toolReply))

        val floating = state(session)
        assertEquals(toolReply.id, session.replyMessageId.value)
        assertTrue(floating.hasReply)
        assertEquals("", floating.preview)
        assertEquals(GenerationPhase.RunningTool("shell", "{}"), floating.phase)
        assertTrue(floating.pendingTools.any { it.toolCallId == "tool-1" })
        assertFalse(floating.preview.contains("previous visible answer"))
    }

    @Test
    fun `two sessions keep their floating reply IDs and previews independent`() = runTest {
        val first = newSession(backgroundScope)
        val second = newSession(backgroundScope)
        val firstReply = UIMessage.assistant("first session content")
        val secondReply = UIMessage.assistant("second session content")

        applyChunk(first, listOf(firstReply))
        applyChunk(second, listOf(secondReply))

        assertEquals(firstReply.id, first.replyMessageId.value)
        assertEquals(secondReply.id, second.replyMessageId.value)
        assertEquals("first session content", state(first).preview)
        assertEquals("second session content", state(second).preview)
        assertFalse(state(first).preview.contains("second session content"))
        assertFalse(state(second).preview.contains("first session content"))
    }

    @Test
    fun `ChatService wires reply tracking into each generation and collected message chunk`() {
        val source = readChatServiceSource()
        val methodStart = source.indexOf("private suspend fun handleMessageComplete(")
        assertTrue("handleMessageComplete must be found", methodStart >= 0)
        val methodEnd = source.indexOf("\n    private suspend fun createWorkspaceToolsIfReady(", methodStart)
        assertTrue("handleMessageComplete end boundary must be found", methodEnd > methodStart)
        val method = withoutComments(source.substring(methodStart, methodEnd))

        val loopStart = method.indexOf("while (true) {")
        val generateStart = method.indexOf("generationLoop.generateText(", loopStart)
        assertTrue("generation loop boundaries must be found", loopStart >= 0 && generateStart > loopStart)
        assertTrue(
            "each loop iteration must project its actual generation input before starting",
            method.substring(loopStart, generateStart).contains("session.trackFloatingReply(generationMessages)"),
        )

        val collectStart = method.indexOf("}.collect { chunk ->")
        val collectEnd = method.indexOf("\n                if (!midTurnPaused)", collectStart)
        assertTrue("generation chunk collector boundaries must be found", collectStart >= 0 && collectEnd > collectStart)
        val collector = method.substring(collectStart, collectEnd)
        val messageBranchStart = collector.indexOf("is GenerationChunk.Messages -> {")
        assertTrue("Messages chunk branch must be found", messageBranchStart >= 0)
        val messageBranchEnd = collector.indexOf("\n                    }\n                }", messageBranchStart)
        assertTrue("Messages chunk branch end must be found", messageBranchEnd > messageBranchStart)
        val messagesBranch = collector.substring(messageBranchStart, messageBranchEnd)
        val updateIndex = messagesBranch.indexOf("updateConversation(conversationId, updatedConversation)")
        val trackIndex = messagesBranch.indexOf("session.trackFloatingReply(chunk.messages)")
        val firstSaveIndex = messagesBranch.indexOf("saveConversation(")
        assertTrue("Messages branch must update the conversation before tracking", updateIndex >= 0)
        assertTrue("Messages branch must track the actual chunk after the session update", trackIndex > updateIndex)
        assertTrue("Messages branch must track before any suspending save", firstSaveIndex > trackIndex)

        // The real regeneration entry must pass the earlier input slice, not the later history tail.
        val cleanSource = withoutComments(source)
        val regenerateStart = cleanSource.indexOf("fun regenerateAtMessage(")
        val regenerateEnd = cleanSource.indexOf("private object RoundTableSlot", regenerateStart)
        assertTrue("regenerateAtMessage boundaries must be found", regenerateStart >= 0 && regenerateEnd > regenerateStart)
        val regenerate = cleanSource.substring(regenerateStart, regenerateEnd)
        val clearBeforeJob = regenerate.indexOf(
            "if (message.role == MessageRole.USER || regenerateAssistantMsg) session.trackFloatingReply(emptyList())",
        )
        val launchJob = regenerate.indexOf("val job = launchGenerationJob(")
        assertTrue("generation-style regeneration must clear the previous preview before launching", clearBeforeJob >= 0 && launchJob > clearBeforeJob)
        assertTrue(
            "assistant regeneration must pass only history preceding the regenerated node",
            regenerate.contains("handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)"),
        )
        assertTrue(
            "handleMessageComplete must actually slice generation input at the requested node",
            method.substring(0, loopStart).contains("it.subList(messageRange.start, messageRange.endInclusive + 1)"),
        )
        val firstSettingsRead = method.indexOf("val settings = settingsStore.settingsFlow.first()")
        val clearAtEntry = method.indexOf("session.trackFloatingReply(emptyList())")
        assertTrue("preparation failure must not retain the previous reply", clearAtEntry >= 0 && firstSettingsRead > clearAtEntry)
        val sendStart = cleanSource.indexOf("private fun sendQueuedMessage(")
        val sendEnd = cleanSource.indexOf("private fun preprocessUserInputParts(", sendStart)
        assertTrue("sendQueuedMessage boundaries must be found", sendStart >= 0 && sendEnd > sendStart)
        val send = cleanSource.substring(sendStart, sendEnd)
        val clearNewReply = send.indexOf("if (answer) session.trackFloatingReply(emptyList())")
        assertTrue("new reply must clear before preprocessing or launching", clearNewReply >= 0 && send.indexOf("val job = launchGenerationJob(") > clearNewReply)
    }

    private fun readChatServiceSource(): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val source = File(
                directory,
                "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
            )
            if (source.isFile) return source.readText()
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
    }

    private fun withoutComments(source: String): String =
        V1008NestedScrollGuardTest().stripComments(source)
}
