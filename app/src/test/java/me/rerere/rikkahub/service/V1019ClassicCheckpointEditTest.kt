package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/** v1019：编辑压缩摘要、旧用量水位随摘要保留、以及消息列表的检查点卡片接线。 */
class V1019ClassicCheckpointEditTest {
    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String = File(repoRoot, relative).readText()

    private fun conversationOf(messageNodes: List<me.rerere.rikkahub.data.model.MessageNode>) =
        Conversation(assistantId = Uuid.random(), messageNodes = messageNodes)

    private class MemoryStore(var persisted: Conversation) : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) { persisted = current }
    }

    /** 走真实经典压缩流程，得到带旧用量水位的检查点节点。 */
    private suspend fun compressed(
        messages: List<UIMessage>,
        keepRecentMessages: Int,
    ): Conversation = compressedNodes(messages.map { it.toMessageNode() }, keepRecentMessages)

    private suspend fun compressedNodes(
        nodes: List<MessageNode>,
        keepRecentMessages: Int,
    ): Conversation {
        val source = conversationOf(nodes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(source.id, source, scope, MemoryStore(source), {})
        try {
            session.initialize(load = { source })
            session.compressClassicHistory(keepRecentMessages) { _, _ -> "summary text" }
            return session.state.value
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `editing a normal assistant message still adds a selected branch`() {
        val original = UIMessage.assistant("old")

        val updated = editMessageNodes(listOf(original.toMessageNode()), original.id, listOf(UIMessagePart.Text("new")))!!

        val node = updated.single()
        assertEquals(2, node.messages.size)
        assertEquals(1, node.selectIndex)
        assertEquals(MessageRole.ASSISTANT, node.currentMessage.role)
        assertEquals("new", node.currentMessage.toText())
        // v1018 的写法就是不补 modelId —— 本次是纯搬移，主对话编辑行为一字不变
        assertNull(node.currentMessage.modelId)
        assertEquals(original, node.messages.first())
    }

    @Test
    fun `editing an unknown message reports nothing to save`() {
        val nodes = listOf(UIMessage.user("hi").toMessageNode())

        assertNull(editMessageNodes(nodes, Uuid.random(), listOf(UIMessagePart.Text("x"))))
    }

    @Test
    fun `editing a summary rewrites it in place instead of losing the checkpoint flag`() = runBlocking {
        val archived = UIMessage.user("archived original")
        val before = compressed(listOf(archived), keepRecentMessages = 0)
        val checkpoint = before.currentMessages.single { it.isContextCheckpoint }

        val updated = editMessageNodes(before.messageNodes, checkpoint.id, listOf(UIMessagePart.Text("edited summary")))!!
        val after = conversationOf(updated)

        assertEquals(before.messageNodes.size, updated.size)
        assertEquals(1, updated.single { node -> node.currentMessage.id == checkpoint.id }.messages.size)
        val edited = after.currentMessages.single { it.isContextCheckpoint }
        assertEquals(checkpoint.id, edited.id)
        assertEquals("edited summary", edited.toText())
        // 归档的原始消息不会被重新发给模型，也不会被计费口径读取
        assertEquals(listOf("edited summary"), after.currentMessages.limitContext(0).map { it.toText() })
        assertEquals(listOf("edited summary"), smartCompressionHistory(after).map { it.toText() })
    }

    @Test
    fun `editing a summary carries the retained old usage baselines forward`() = runBlocking {
        val tail = UIMessage.assistant("answer").copy(usage = TokenUsage(promptTokens = 899_000, completionTokens = 1_000))
        val before = compressed(listOf(UIMessage.user("question"), tail), keepRecentMessages = 1)
        val checkpoint = before.currentMessages.single { it.isContextCheckpoint }

        // 压缩本身不许改写原文与它的用量
        assertEquals(tail, before.currentMessages.last())

        val updated = editMessageNodes(before.messageNodes, checkpoint.id, listOf(UIMessagePart.Text("fixed wording")))!!
        val after = conversationOf(updated)
        val edited = after.currentMessages.single { it.isContextCheckpoint }

        assertTrue(isPreCheckpointUsage(tail, edited))
        assertNull(reportedConversationTokens(after))
        assertEquals(listOf("fixed wording", "answer"), after.currentMessages.limitContext(0).map { it.toText() })
    }

    @Test
    fun `switching to an older branch of a retained message does not restore its old usage`() = runBlocking {
        val older = UIMessage.assistant("older").copy(usage = TokenUsage(promptTokens = 899_000, completionTokens = 1_000))
        val current = UIMessage.assistant("newer").copy(usage = TokenUsage(promptTokens = 120_000, completionTokens = 40))
        val tailNode = MessageNode(messages = listOf(older, current), selectIndex = 1)
        val nodes = listOf(UIMessage.user("question").toMessageNode(), tailNode)
        val after = compressedNodes(nodes, keepRecentMessages = 1)

        assertNull(reportedConversationTokens(after))

        val switched = after.copy(
            messageNodes = after.messageNodes.map { node ->
                if (node.messages.any { it.id == older.id }) node.copy(selectIndex = 0) else node
            },
        )
        assertNull(reportedConversationTokens(switched))
    }

    @Test
    fun `message list renders the checkpoint card before any normal bubble`() {
        val chat = source("app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt")
        val anchor = "    val message = node.messages[node.selectIndex]"
        val normalRender = chat.indexOf("val settings = LocalSettings.current.displaySetting")
        val branchStart = chat.indexOf("if (message.isContextCheckpoint) {")
        val cardCall = chat.indexOf("ChatMessageContextCheckpoint(", branchStart)

        assertTrue("取到当前消息后必须立刻判检查点", branchStart in anchor.length until normalRender)
        assertTrue("检查点必须用官方卡片渲染", cardCall in branchStart until normalRender)
        val block = chat.substring(cardCall, normalRender)
        assertTrue("卡片分支必须直接结束本次渲染，不再走普通气泡", block.contains("return"))
        assertTrue("卡片必须带编辑入口", block.contains("onEdit = onEdit"))
        assertTrue("卡片必须带删除入口（删掉摘要即可恢复完整历史）", block.contains("onDelete = onDelete"))
        assertTrue("卡片组件必须在消息包内", File(repoRoot, "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageContextCheckpoint.kt").exists())
    }
}
