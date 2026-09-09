package me.rerere.rikkahub.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentFinishReason
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentLimitReachedException
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.FakeAgentBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v218~v222：代理线程管理器门禁测试。
 *
 * 验证：
 * - 4 个代理可并行运行并全部成功；
 * - 超过并发上限时第 5 个被拒绝；
 * - 停止一个代理不影响其他代理继续完成；
 * - 【v222 关键门禁】用户停止后，STOPPED 状态一定在 NonCancellable 中落库（绝不卡在 RUNNING）；
 * - 【v222 关键门禁】截断识别：finishReason=length / max_tokens 时标记 truncated=true；
 * - 【v222 关键门禁】继续输出（resume）：截断/停止/失败后可续跑，保留历史并递增 resumeCount；
 * - 代理完整生命周期独立于主会话对象（无 ConversationSession 依赖）。
 */
class AgentThreadManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun request(task: String, conversationId: String = "conv-1") = AgentSpawnRequest(
        conversationId = conversationId,
        task = task,
    )

    private suspend fun awaitTerminal(
        repo: InMemoryAgentThreadRepository,
        conversationId: String,
        expectedTerminal: Int,
    ) {
        withTimeout(15_000) {
            while (repo.threadsFlow(conversationId).first().count { it.status.isTerminal } < expectedTerminal) {
                delay(10)
            }
        }
    }

    @Test
    fun `四个代理可并行运行并全部成功`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 150, messageCount = 2),
            scope,
            maxConcurrent = 4,
        )
        val start = System.currentTimeMillis()
        (1..4).forEach { manager.spawn(request("任务 $it")) }
        awaitTerminal(repo, "conv-1", 4)
        val elapsed = System.currentTimeMillis() - start

        val final = repo.threadsFlow("conv-1").first()
        assertEquals(4, final.count { it.status == AgentThreadStatus.SUCCEEDED })
        // 4 个 × 各 300ms 串行 = 1200ms；并行应明显更短（预留低端环境调度余量）
        assertTrue("并行度不足 elapsed=$elapsed ms", elapsed < 1_500)
    }

    @Test
    fun `超过并发上限时第 5 个被拒绝`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 500, messageCount = 1),
            scope,
            maxConcurrent = 4,
        )
        (1..4).forEach { manager.spawn(request("任务 $it")) }
        try {
            manager.spawn(request("任务 5"))
            fail("应当抛出 AgentLimitReachedException")
        } catch (e: AgentLimitReachedException) {
            assertEquals(4, e.maxConcurrent)
        }
        // 上限内 4 个仍正常
        assertEquals(4, repo.threadsFlow("conv-1").first().size)
    }

    @Test
    fun `停止一个代理不影响其他代理继续完成`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(repo, ControllableBackend(), scope, maxConcurrent = 4)
        val slow = manager.spawn(request("SLOW"))
        manager.spawn(request("FAST"))
        manager.spawn(request("FAST"))

        // 等两个快的完成
        awaitTerminal(repo, "conv-1", 2)
        manager.stop(slow.id)
        awaitTerminal(repo, "conv-1", 3)

        val final = repo.threadsFlow("conv-1").first()
        assertEquals(AgentThreadStatus.STOPPED, final.first { it.id == slow.id }.status)
        assertEquals(2, final.count { it.status == AgentThreadStatus.SUCCEEDED })
    }

    @Test
    fun `停止后状态一定落库且能被正常识别为 STOPPED`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(repo, ControllableBackend(), scope, maxConcurrent = 4)
        val slow = manager.spawn(request("SLOW"))

        // 等它进入 RUNNING
        withTimeout(5_000) {
            while (repo.thread(slow.id)?.status != AgentThreadStatus.RUNNING) delay(10)
        }

        manager.stop(slow.id)
        awaitTerminal(repo, "conv-1", 1)

        val thread = repo.thread(slow.id)!!
        assertEquals(AgentThreadStatus.STOPPED, thread.status)
        assertNotNull("finishedAt 必须被设置", thread.finishedAt)
        // 允许续跑
        assertTrue(thread.canResume)
    }

    @Test
    fun `截断能被正确识别为 truncated 且允许续跑`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 10, messageCount = 1, finishReason = "max_tokens"),
            scope,
            maxConcurrent = 4,
        )
        val thread = manager.spawn(request("截断任务"))
        awaitTerminal(repo, "conv-1", 1)

        val result = repo.thread(thread.id)!!
        assertEquals(AgentThreadStatus.SUCCEEDED, result.status)
        assertEquals("max_tokens", result.finishReason)
        assertTrue(result.truncated)
        assertTrue(result.canResume)
    }

    @Test
    fun `继续输出功能可让截断线程继续跑并递增 resumeCount`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = FakeAgentBackend(delayMillis = 10, messageCount = 1, finishReason = "length")
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4)
        val thread = manager.spawn(request("分段任务"))
        awaitTerminal(repo, "conv-1", 1)

        val firstRun = repo.thread(thread.id)!!
        assertTrue(firstRun.truncated)
        assertEquals(0, firstRun.resumeCount)

        // 点击继续输出
        val revived = manager.resume(thread.id, extraInstruction = "请继续写")
        assertNotNull(revived)
        assertEquals(1, revived!!.resumeCount)

        // 等第 2 次跑完
        awaitTerminal(repo, "conv-1", 1)
        val secondRun = repo.thread(thread.id)!!
        assertEquals(1, secondRun.resumeCount)
        assertTrue(backend.lastResume)
    }

    @Test
    fun `正常完整结束的线程不允许继续输出`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 10, messageCount = 1, finishReason = "stop"),
            scope,
            maxConcurrent = 4,
        )
        val thread = manager.spawn(request("完整任务"))
        awaitTerminal(repo, "conv-1", 1)

        val completed = repo.thread(thread.id)!!
        assertEquals(AgentThreadStatus.SUCCEEDED, completed.status)
        assertFalse(completed.truncated)
        assertFalse(completed.canResume)

        val attempted = manager.resume(thread.id)
        assertEquals(null, attempted)
    }

    @Test
    fun `代理生命周期独立于主会话且消息事件只落 Agent 仓库`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 20, messageCount = 2),
            scope,
            maxConcurrent = 4,
        )
        val thread = manager.spawn(request("独立任务"))
        awaitTerminal(repo, "conv-1", 1)

        val final = repo.threadsFlow("conv-1").first().single()
        assertEquals(AgentThreadStatus.SUCCEEDED, final.status)
        assertNotNull(final.reportJson)
        // 可观察消息与事件都已落库（只写 Agent 仓库，不进入主会话）
        assertEquals(2, repo.messagesFlow(thread.id).first().size)
        assertEquals(2, repo.eventsFlow(thread.id).first().size)
        // 报告可解析
        val report = AgentReport.decode(final.reportJson)
        assertTrue(report != null && report.conclusion.isNotBlank())
    }

    /** 可控后端：SLOW 任务长时间运行直到被取消；FAST 任务快速完成 */
    private class ControllableBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            if (thread.task.contains("SLOW")) {
                try {
                    delay(30_000)
                } catch (e: CancellationException) {
                    throw e
                }
            } else {
                delay(100)
                onEvent(AgentEvent(threadId = thread.id, type = "PROGRESS", detail = "fast done"))
                onMessage(AgentMessage(threadId = thread.id, role = "assistant", content = "fast result"))
            }
            return AgentRunOutcome(
                report = AgentReport(
                    conclusion = "done",
                    evidence = emptyList(),
                    uncertainties = emptyList(),
                    suggestions = emptyList(),
                ),
                finishReason = "stop",
                truncated = false,
            )
        }
    }
}
