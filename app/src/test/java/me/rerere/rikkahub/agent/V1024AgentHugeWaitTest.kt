package me.rerere.rikkahub.agent

import kotlinx.coroutines.*
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import org.junit.Assert.*
import org.junit.Test

/** v1024二审回归：真实管理器的等待路径，模型边界仅用永不请求网络的可取消任务替代。 */
class V1024AgentHugeWaitTest {
    private class WaitingBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome = awaitCancellation()
    }
    @Test fun `极大等待值进入真实等待方法后不能立刻溢出返回`() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val manager = AgentThreadManager(InMemoryAgentThreadRepository(), WaitingBackend(), app,
                maxConcurrent = 4, autoResumeMax = { 0 })
            val thread = manager.spawn(AgentSpawnRequest(conversationId = "huge-wait", task = "local cancellation probe"))
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                manager.awaitTerminal(listOf(thread.id), Long.MAX_VALUE)
            }
            delay(30)
            assertFalse("不能把Long.MAX_VALUE加到时间戳后溢出成已经到期", waiting.isCompleted)
            manager.stop(thread.id)
            val result = withTimeout(3000) { waiting.await() }
            assertEquals(AgentThreadStatus.STOPPED, result.single().status)
        } finally { app.cancel() }
    }
}
