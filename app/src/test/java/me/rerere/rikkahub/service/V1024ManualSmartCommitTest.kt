package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.uuid.Uuid

/** v1024审查回归：真实ConversationSession，只替换储存边界和模型已给出的摘要，不触及真实数据。 */
class V1024ManualSmartCommitTest {
    private class Store(var persisted: Conversation) : ConversationStore {
        var beforeWrite: (suspend () -> Unit)? = null
        var writes = 0
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) {
            beforeWrite?.invoke()
            persisted = current
            writes++
        }
    }
    private suspend fun fixture(block: suspend (ConversationSession, Store, Conversation, List<MessageNode>) -> Unit) {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("question").toMessageNode(), UIMessage.assistant("answer").toMessageNode(),
        ))
        val store = Store(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(source.id, source, scope, store, {})
        try {
            session.initialize(load = { source })
            block(session, store, source, listOf(UIMessage.user("summary").toMessageNode()))
        } finally { scope.cancel() }
    }
    private suspend fun commit(
        session: ConversationSession,
        source: Conversation,
        nodes: List<MessageNode>,
        sourceStillExists: suspend () -> Boolean = { true },
    ) {
        val type = try { Class.forName("me.rerere.rikkahub.service.ManualSmartCompressionCommitKt") }
            catch (_: ClassNotFoundException) { throw AssertionError("后台智能压缩缺少可回退的提交处理，flush失败仍会留下摘要内存态") }
        val method = type.getDeclaredMethod(
            "commitManualSmartCompression", ConversationSession::class.java, Conversation::class.java,
            List::class.java, Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java,
            Continuation::class.java,
        )
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = try { method.invoke(null, session, source, nodes, 0, sourceStillExists, continuation) }
                catch (e: InvocationTargetException) { throw (e.cause ?: e) }
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
    }
    @Test fun `源内容未变时正常保存摘要`() = runBlocking {
        fixture { session, store, source, nodes ->
            commit(session, source, nodes)
            assertEquals(nodes, store.persisted.messageNodes)
            assertEquals(nodes, session.state.value.messageNodes)
            assertEquals(1, store.writes)
        }
    }
    @Test fun `写盘失败后恢复原记录而非继续显示未保存的摘要`() = runBlocking {
        fixture { session, store, source, nodes ->
            store.beforeWrite = { throw IOException("storage failed") }
            try { commit(session, source, nodes); fail("must report storage failure") } catch (_: IOException) { }
            assertEquals(source.messageNodes, store.persisted.messageNodes)
            assertEquals(source.messageNodes, session.state.value.messageNodes)
            assertEquals(0, store.writes)
        }
    }
    @Test fun `失败回退不能抹掉保存期间新来的用户消息`() = runBlocking {
        fixture { session, store, source, nodes ->
            val arrived = UIMessage.user("arrived during save").toMessageNode()
            store.beforeWrite = {
                session.update { it.copy(messageNodes = it.messageNodes + arrived) }
                throw IOException("storage failed")
            }
            try { commit(session, source, nodes); fail("must report storage failure") } catch (_: IOException) { }
            assertEquals(source.messageNodes + arrived, session.state.value.messageNodes)
            assertEquals(source.messageNodes, store.persisted.messageNodes)
        }
    }
    @Test fun `压缩期间原记录改变就不能让旧摘要覆盖新记录`() = runBlocking {
        fixture { session, store, source, nodes ->
            val arrived = UIMessage.user("new message").toMessageNode()
            session.update { it.copy(messageNodes = it.messageNodes + arrived) }
            try { commit(session, source, nodes); fail("must reject stale source") } catch (_: IllegalStateException) { }
            assertEquals(source.messageNodes + arrived, session.state.value.messageNodes)
            assertEquals(0, store.writes)
        }
    }
    @Test fun `原对话已经删除时不能再提交摘要`() = runBlocking {
        fixture { session, store, source, nodes ->
            try { commit(session, source, nodes) { false }; fail("must reject deleted source") } catch (_: IllegalStateException) { }
            assertEquals(source.messageNodes, session.state.value.messageNodes)
            assertEquals(0, store.writes)
        }
    }
    @Test fun `保存期间用户主动清空时失败恢复不能复活旧记录`() = runBlocking {
        fixture { session, store, source, nodes ->
            store.beforeWrite = {
                session.update { it.copy(messageNodes = emptyList()) }
                throw IOException("storage failed")
            }
            try { commit(session, source, nodes); fail("must report storage failure") } catch (_: IOException) { }
            assertTrue(session.state.value.messageNodes.isEmpty())
        }
    }

}
