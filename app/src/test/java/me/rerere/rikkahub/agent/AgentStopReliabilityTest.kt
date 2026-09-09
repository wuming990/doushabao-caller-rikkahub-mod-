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
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AGENT_STOP_FINALIZE_TIMEOUT_MILLIS
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v236 门禁：点了停止就必须真的停下来，而且状态必须落地。
 *
 * ## 背景（用户真机反馈原话）
 *
 * > 「上条回复的子代理我停止后一直处于正在停止状态」
 * > 「现在子代理全部停止了，但是太慢了，中途我不知道有没有额外继续在跑什么的，观感也不好。」
 *
 * 定位到的三个真实缺陷：
 *
 * 1. **[AgentThreadManager.runThread] 的前几行在 try 之外**：用户在「刚派发、还没进入生成」
 *    的窗口点停止时，取消异常直接逃出函数 —— catch 不执行（STOPPED 永远写不进库，
 *    界面永久停在「正在停止…」），finally 也不执行（jobs / stopRequested 不清理，
 *    而 activeCount 把 STOPPING 当活动态，这条死线程永久占用并发额度）。
 *    主模型那边表现为 wait_agents 一直等到超时 —— 就是「太慢了」。
 * 2. **spawn 先启动协程后登记 job**：停止落在这条缝里时 cancel 打空拳，
 *    线程会在后台一路跑完，而界面已经显示「正在停止」。
 * 3. **退避中的自动重试掐不掉**：退避那几秒 `jobs` 是空的，停止无效，
 *    几秒后线程自己复活 —— 就是「不知道有没有额外继续在跑什么」。
 */
class AgentStopReliabilityTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }

    private val managerPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt"

    private fun request(task: String) = AgentSpawnRequest(conversationId = "conv-1", task = task)

    private suspend fun awaitStatus(
        repo: InMemoryAgentThreadRepository,
        threadId: String,
        vararg expected: AgentThreadStatus,
    ): AgentThread = withTimeout(20_000) {
        var found: AgentThread? = null
        while (found == null) {
            val current = repo.thread(threadId)
            if (current != null && expected.contains(current.status)) {
                found = current
            } else {
                delay(20)
            }
        }
        found!!
    }

    // ------------------------------------------------------------ 行为

    @Test
    fun `运行中的线程停止后必须落到已停止而不是卡在正在停止`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(repo, NeverEndingBackend(), scope, maxConcurrent = 4)
        val thread = manager.spawn(request("长时间运行"))
        awaitStatus(repo, thread.id, AgentThreadStatus.RUNNING)

        manager.stop(thread.id)
        val settled = awaitStatus(repo, thread.id, AgentThreadStatus.STOPPED)

        assertEquals(AgentThreadStatus.STOPPED, settled.status)
        assertTrue("必须设置结束时间", settled.finishedAt != null)
        assertTrue("停止后允许继续输出", settled.canResume)
        assertEquals("并发额度必须被释放", 0, repo.activeCount())
    }

    @Test
    fun `刚派发就立刻停止也必须落到终态并释放额度`() = runBlocking {
        // 这是 v235 卡死的那个窗口：取消发生在 runThread 进入 try 之前。
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(repo, NeverEndingBackend(), scope, maxConcurrent = 4)
        val thread = manager.spawn(request("刚派发就停"))
        // 不等它进入 RUNNING，直接停
        manager.stop(thread.id)

        val settled = awaitStatus(repo, thread.id, AgentThreadStatus.STOPPED)
        assertEquals(AgentThreadStatus.STOPPED, settled.status)
        assertEquals("绝不允许留下永久占额度的僵尸线程", 0, repo.activeCount())
    }

    @Test
    fun `停止过的线程不得被自动重试偷偷复活`() = runBlocking {
        // 可恢复错误 → v235 会排一次自动续跑；用户停止后，绝不能再复活。
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(repo, RecoverableFailBackend(), scope, maxConcurrent = 4)
        val thread = manager.spawn(request("上游抖动"))

        // 等它进入失败或退避等待
        awaitStatus(
            repo,
            thread.id,
            AgentThreadStatus.FAILED,
            AgentThreadStatus.WAITING_AUTO_RETRY,
        )
        manager.stop(thread.id)

        // 退避基数 4 秒；等得比它长，确认它没有偷偷起来。
        // 状态允许是 STOPPED（停在退避期间）或 FAILED（停止时已经落了失败），
        // 但**绝不允许**回到活动态或变成成功 —— 那才是用户抱怨的「不知道还在跑什么」。
        delay(6_500)
        val after = repo.thread(thread.id)!!
        assertTrue(
            "停止后必须停在终态，实际=${after.status}",
            after.status == AgentThreadStatus.STOPPED || after.status == AgentThreadStatus.FAILED,
        )
        assertTrue("绝不允许回到活动态", after.status.isTerminal)
        assertEquals("不得产生额外的续跑", 0, after.resumeCount)
        assertEquals("并发额度必须已释放", 0, repo.activeCount())
    }

    @Test
    fun `等待自动重试是活动态且占用并发额度`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(repo, RecoverableFailBackend(), scope, maxConcurrent = 4)
        val thread = manager.spawn(request("等待重试"))
        val waiting = awaitStatus(repo, thread.id, AgentThreadStatus.WAITING_AUTO_RETRY)

        assertTrue("退避等待必须算活动态", waiting.status.isActive)
        assertFalse("退避等待不是终态", waiting.status.isTerminal)
        assertFalse("退避等待期间不该亮出继续输出", waiting.canResume)
        assertEquals("必须占用并发额度", 1, repo.activeCount())
        manager.stop(thread.id)
    }

    @Test
    fun `可恢复错误在没人干预时确实会自动续跑`() = runBlocking {
        // 反向验证：加固停止之后，自动恢复本身不能被搞坏。
        val repo = InMemoryAgentThreadRepository()
        val backend = FailOnceThenOkBackend()
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4)
        val thread = manager.spawn(request("先挂一次再成功"))

        val settled = withTimeout(30_000) {
            var current = repo.thread(thread.id)
            while (current == null || current.status != AgentThreadStatus.SUCCEEDED) {
                delay(50)
                current = repo.thread(thread.id)
            }
            current
        }
        assertEquals(AgentThreadStatus.SUCCEEDED, settled.status)
        assertEquals("应当自动续跑了一次", 1, settled.resumeCount)
        assertTrue("第二次运行必须是续跑（保留中断前内容）", backend.lastResume)
    }

    // ------------------------------------------------------------ 源码门禁

    @Test
    fun `runThread 必须整体包在 try 中`() {
        val text = source(managerPath)
        val body = text.substringAfter("private suspend fun runThread(", "")
        assertTrue("找不到 runThread", body.isNotBlank())
        val head = body.substringBefore("} catch (e: CancellationException)", "")
        assertTrue("runThread 里必须有取消分支", head.isNotBlank())
        // try 必须是函数体的第一条语句：否则取消发生在它之前时 catch/finally 全都不执行
        val firstStatement = head.lines()
            .drop(1)
            .map { it.trim() }
            .first { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
        assertEquals("runThread 的第一条语句必须是 try {", "try {", firstStatement)
        assertTrue(
            "开跑前必须检查是否已被要求停止（省掉注定要丢弃的那次请求）",
            head.contains("if (threadId in stopRequested)"),
        )
    }

    @Test
    fun `spawn 与 resume 必须先登记 job 再启动`() {
        val text = source(managerPath)
        assertEquals(
            "spawn / resume 都必须用 CoroutineStart.LAZY 先登记后启动",
            2,
            // v242：正则放宽成允许换行 —— 断言的意图是「必须 LAZY 先登记再启动」，
            // 与 `{ runThread` 是否写在同一行无关。v242 给 runThread 加了运行代次参数，
            // 调用换成多行写法，旧正则会假失败。
            Regex("scope\\.launch\\(start = CoroutineStart\\.LAZY\\)\\s*\\{\\s*runThread")
                .findAll(text).count(),
        )
        assertTrue("必须显式 start", text.contains("job.start()"))
    }

    @Test
    fun `停止必须掐掉退避中的自动重试并兜底落终态`() {
        val text = source(managerPath)
        assertTrue(
            "停止必须取消退避中的自动续跑，否则几秒后线程自己复活",
            text.contains("autoRetryJobs.remove(threadId)?.cancel()"),
        )
        assertTrue(
            "必须等收尾并兜底强制落终态",
            text.contains("forceStopIfStillActive(threadId)"),
        )
        assertTrue(
            "兜底等待必须有上限",
            text.contains("withTimeoutOrNull(AGENT_STOP_FINALIZE_TIMEOUT_MILLIS)"),
        )
        assertTrue("兜底上限必须是有限正数", AGENT_STOP_FINALIZE_TIMEOUT_MILLIS in 1_000L..30_000L)
        assertTrue(
            "退避结束后必须再确认一次用户有没有停止",
            text.contains("threadId in stopLatched"),
        )
        assertTrue(
            "长效停止标记只能由 resume 清除，不能被 finally 清掉",
            text.contains("stopLatched.remove(threadId)"),
        )
    }

    @Test
    fun `并发额度统计必须把退避等待算进去`() {
        val dao = source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentDao.kt")
        // 两条按状态筛选的 SQL 必须都带上新状态：
        //   activeCount           —— 退避等待仍占并发额度（几秒后还会发请求）
        //   markInterruptedOnBoot —— App 重启时把退避中的线程回收成「已中断」
        // 直接数这个片段最稳：注释里不会出现带引号的 SQL 片段。
        assertEquals(
            "两条状态 SQL 都必须包含 WAITING_AUTO_RETRY",
            2,
            Regex("'STOPPING', 'WAITING_AUTO_RETRY'\\)").findAll(dao).count(),
        )
        assertTrue("必须有并发计数 SQL", dao.contains("SELECT COUNT(*) FROM agent_threads"))
        assertTrue(
            "必须有重启回收 SQL",
            dao.contains("UPDATE agent_threads SET status = 'INTERRUPTED'"),
        )
    }

    // ------------------------------------------------------------ 假后端

    /** 一直跑到被取消为止 */
    private class NeverEndingBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            try {
                delay(60_000)
            } catch (e: CancellationException) {
                throw e
            }
            return AgentRunOutcome(AgentReport(conclusion = "never"))
        }
    }

    /** 每次都抛可恢复错误（限流） */
    private class RecoverableFailBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            delay(20)
            throw IllegalStateException("429 Too Many Requests")
        }
    }

    /** 第一次抛可恢复错误，之后成功 */
    private class FailOnceThenOkBackend : AgentBackend {
        @Volatile
        var lastResume: Boolean = false
            private set

        private var calls = 0

        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            lastResume = resume
            calls++
            delay(20)
            if (calls == 1) throw IllegalStateException("503 Service Unavailable")
            onMessage(AgentMessage(threadId = thread.id, role = "assistant", content = "done"))
            return AgentRunOutcome(
                report = AgentReport(conclusion = "第 $calls 次终于成功"),
                finishReason = "stop",
                truncated = false,
            )
        }
    }
}
