package me.rerere.rikkahub.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.model.AgentVerdict
import me.rerere.rikkahub.agent.model.inferVerdictFromConclusion
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AGENT_ATTEMPT_HARD_DEADLINE_MILLIS
import me.rerere.rikkahub.agent.runtime.AGENT_AUTO_RETRY_BACKOFF_MILLIS
import me.rerere.rikkahub.agent.runtime.AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS
import me.rerere.rikkahub.agent.runtime.AGENT_PIPELINE_STAGE_WAIT_MAX_MILLIS
import me.rerere.rikkahub.agent.runtime.AGENT_THREAD_TIMEOUT_MAX_MINUTES
import me.rerere.rikkahub.agent.runtime.AgentAttemptTimeoutException
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.agentIdleTimeoutMillis
import me.rerere.rikkahub.agent.runtime.pipelineStageWaitMillis
import me.rerere.rikkahub.agent.runtime.shouldRejectTerminalWrite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v242 门禁：子代理的状态别再自己乱变，也别把「马上会自己接着跑」报成失败。
 *
 * ## 三个真机现象（本轮实测抓到，全部可复现）
 *
 * 1. **一条已经成功、报告完整的子代理，几十秒后状态自己变成「运行被意外中断」。**
 *    对应用户长期反馈的「跑完了自己变成中断」。能写终态的路有好几条，逐条堵既堵不干净
 *    也无法证明堵住了，所以改成在「收尾状态写入」这个唯一出口上设闸：
 *    终态一旦落定就不许被另一个终态覆盖，并写一条 TERMINAL_WRITE_REJECTED 事件留证。
 *
 * 2. **主模型把「马上会自己续跑」的失败当成了最终失败。**
 *    现场：只读子代理被判卡住 → 落 FAILED，`wait_agents` 立刻返回失败；可它随后自动
 *    续跑一次并**完整跑完**。根因是落 FAILED 与落 WAITING_AUTO_RETRY 之间有一条缝，
 *    500ms 轮询正好撞在缝里。后果是主模型白重派一次（重复花钱），用户以为子代理不行。
 *
 * 3. **换模型时传一个不存在的模型，回执是成功、事件写着「已换成 xxx」，其实根本没换。**
 *    候选链解析时找不到的 id 被静默跳过，回落到原模型。现在换之前先校验，
 *    并且校验放在「停掉当前这次生成」之前 —— 不能为了一个假模型白丢已有进度。
 */
class AgentV242StateGuardTest {

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
    private val backendPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"
    private val diPath = "app/src/main/java/me/rerere/rikkahub/di/AgentModule.kt"
    private val toolsPath =
        "app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt"
    private val pagePath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt"

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

    // ------------------------------------------------------------ 行为（真跑）

    @Test
    fun `等待终态时不许把马上要自动续跑的失败当成最终结果`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = FailOnceThenSucceedBackend()
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4)
        val thread = manager.spawn(request("先失败一次，再成功"))

        val settled = withTimeout(60_000) { manager.awaitTerminal(listOf(thread.id), 45_000) }

        assertEquals(1, settled.size)
        assertEquals(
            "必须等到自动续跑真的跑完，而不是在 FAILED 那一瞬间就报失败",
            AgentThreadStatus.SUCCEEDED,
            settled.first().status,
        )
        assertEquals("应当自动续跑了一次", 1, settled.first().resumeCount)
        assertTrue("第二次必须是续跑（保留中断前内容）", backend.lastResume)
    }

    @Test
    fun `自动续跑关掉时等待终态必须立刻如实返回失败`() = runBlocking {
        // 反向门禁：不能因为加了「等它自己复活」的判据，就把不会复活的失败也白等到超时。
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            AlwaysFailBackend(),
            scope,
            maxConcurrent = 4,
            autoResumeMax = { 0 },
        )
        val thread = manager.spawn(request("永远失败，且不许自动续"))

        val startedAt = System.currentTimeMillis()
        val settled = manager.awaitTerminal(listOf(thread.id), 20_000)
        val cost = System.currentTimeMillis() - startedAt

        assertEquals(AgentThreadStatus.FAILED, settled.first().status)
        assertTrue("不该白等到超时，实际等了 ${cost}ms", cost < 15_000)
    }

    @Test
    fun `模型不存在时不许换模型也不许打断正在跑的线程`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            NeverEndingBackend(),
            scope,
            maxConcurrent = 4,
            modelExists = { it == "真模型" },
        )
        val thread = manager.spawn(request("正在跑，别打断我"))
        awaitStatus(repo, thread.id, AgentThreadStatus.RUNNING)

        val result = manager.switchModelAndResume(thread.id, "不存在的模型")

        assertNull("模型不存在必须明确失败，不能假装换成功", result)
        val after = repo.thread(thread.id)!!
        assertEquals(
            "不许为了一个假模型就把正在跑的这一次生成打断",
            AgentThreadStatus.RUNNING,
            after.status,
        )
        assertEquals("线程上的模型不许被改成假的", thread.modelId, after.modelId)

        manager.stop(thread.id)
        awaitStatus(repo, thread.id, AgentThreadStatus.STOPPED)
        Unit
    }

    @Test
    fun `模型确实存在时换模型续跑必须生效`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            AlwaysFailBackend(),
            scope,
            maxConcurrent = 4,
            autoResumeMax = { 0 },
            modelExists = { it == "真模型" },
        )
        val thread = manager.spawn(request("先失败，等着被换模型"))
        awaitStatus(repo, thread.id, AgentThreadStatus.FAILED)

        val revived = manager.resumeWithModel(thread.id, "真模型")

        assertNotNull("存在的模型必须换得上", revived)
        assertEquals("模型必须被钉到线程上", "真模型", repo.thread(thread.id)!!.modelId)
        Unit
    }

    @Test
    fun `终态一旦落定就不许被另一个终态覆盖`() {
        // 必须拒绝：这些组合就是「跑完了自己变状态」的真实形状
        assertTrue(
            "成功不许被改成中断（真机实测到的那一次）",
            shouldRejectTerminalWrite(AgentThreadStatus.SUCCEEDED, AgentThreadStatus.INTERRUPTED),
        )
        assertTrue(
            shouldRejectTerminalWrite(AgentThreadStatus.SUCCEEDED, AgentThreadStatus.STOPPED),
        )
        assertTrue(
            shouldRejectTerminalWrite(AgentThreadStatus.SUCCEEDED, AgentThreadStatus.FAILED),
        )
        assertTrue(
            shouldRejectTerminalWrite(AgentThreadStatus.STOPPED, AgentThreadStatus.INTERRUPTED),
        )
        assertTrue(
            shouldRejectTerminalWrite(AgentThreadStatus.FAILED, AgentThreadStatus.STOPPED),
        )

        // 必须放行
        assertFalse(
            "同一个终态重复写是幂等的",
            shouldRejectTerminalWrite(AgentThreadStatus.SUCCEEDED, AgentThreadStatus.SUCCEEDED),
        )
        assertFalse(
            "关闭是用户/主模型的显式动作，必须放行",
            shouldRejectTerminalWrite(AgentThreadStatus.SUCCEEDED, AgentThreadStatus.CLOSED),
        )
        assertFalse(
            "非终态写终态是正常收尾",
            shouldRejectTerminalWrite(AgentThreadStatus.RUNNING, AgentThreadStatus.SUCCEEDED),
        )
        assertFalse(
            shouldRejectTerminalWrite(AgentThreadStatus.QUEUED, AgentThreadStatus.STOPPED),
        )
        assertFalse(
            "退避等待失败后落 FAILED 是正常的",
            shouldRejectTerminalWrite(
                AgentThreadStatus.WAITING_AUTO_RETRY,
                AgentThreadStatus.FAILED,
            ),
        )
        assertFalse(
            shouldRejectTerminalWrite(AgentThreadStatus.STOPPING, AgentThreadStatus.STOPPED),
        )
    }

    @Test
    fun `卡死文案必须区分「一直不吐字」和「总时长到了」`() {
        val idle = AgentAttemptTimeoutException(4, idle = true).message
        val hard = AgentAttemptTimeoutException(10).message
        assertNotNull(idle)
        assertNotNull(hard)
        assertTrue("静默判据的文案必须说「没有任何新内容」", idle!!.contains("没有任何新内容"))
        assertTrue("总时长判据的文案必须说「没有结束」", hard!!.contains("没有结束"))
        assertTrue("两种文案都必须带 timeout（错误分类靠它判可恢复）", idle.contains("timeout"))
        assertTrue(hard.contains("timeout"))
        assertTrue(
            "静默卡死也必须被判成可恢复，否则不会自动续跑",
            AgentErrorKindProbe.recoverable(idle),
        )
    }

    // ------------------------------------------------------------ 源码门禁

    @Test
    fun `运行代次保护必须在位`() {
        val text = source(managerPath)
        assertTrue("必须有运行代次表", text.contains("private val runEpoch"))
        assertTrue("必须有开新代次的入口", text.contains("private fun nextRunEpoch"))
        assertTrue("必须有判断是否过期的方法", text.contains("private fun isStaleRun"))
        assertEquals(
            "spawn 与 resume 都必须给本次运行分配代次",
            2,
            Regex("nextRunEpoch\\(").findAll(text).count() - 1,
        )
        assertTrue(
            "取消分支必须先确认自己不是过期的一代（否则会覆盖新一代的结果）",
            text.contains("if (isStaleRun(threadId, epoch)) throw e"),
        )
        assertTrue(
            "finally 里的句柄清理必须被代次保护包住（否则会删掉新一代的句柄）",
            text.contains("if (!isStaleRun(threadId, epoch)) {"),
        )
    }

    @Test
    fun `收尾写入必须走终态保护`() {
        val text = source(managerPath)
        assertTrue(
            "finalize 必须调用终态保护判定",
            text.contains("shouldRejectTerminalWrite(current.status, next.status)"),
        )
        assertTrue(
            "被拒绝时必须留下可排查的事件",
            text.contains("\"TERMINAL_WRITE_REJECTED\""),
        )
        assertTrue(
            "判定必须是可单测的顶层函数",
            text.contains("internal fun shouldRejectTerminalWrite("),
        )
    }

    @Test
    fun `等待终态必须考虑马上会自己接着跑`() {
        val text = source(managerPath)
        assertTrue(
            "awaitTerminal 必须排除「马上会自动续跑」的线程",
            text.contains("!willAutoResume(it.id)"),
        )
        assertTrue("必须有这个判据", text.contains("private fun willAutoResume"))
        assertTrue(
            "判据必须看句柄是否还活着，而不是只看有没有登记过",
            text.contains("autoRetryJobs[threadId]?.isActive == true"),
        )
        assertTrue(
            "失败分支必须先立旗再落 FAILED，否则仍有一条缝",
            text.contains("autoResumePlanned.add(threadId)"),
        )
        assertTrue(
            "两条自动续跑通道都必须在 finally 里落旗",
            Regex("autoResumePlanned\\.remove\\(threadId\\)").findAll(text).count() >= 4,
        )
        assertTrue(
            "用户喊停后必须落旗（退避协程可能还没 start，它的 finally 不会执行）",
            text.contains("autoResumePlanned.remove(threadId)\n        autoRetryJobs.remove(threadId)?.cancel()"),
        )
    }

    @Test
    fun `换模型必须先确认模型真的存在`() {
        val text = source(managerPath)
        val di = source(diPath)
        assertTrue("必须有模型存在性检查口", text.contains("private val modelExists"))
        assertEquals(
            "resumeWithModel 与 switchModelAndResume 两处都要校验",
            2,
            // v243：校验收口到 isModelAvailable 这一个入口（工具层也要用它给出精确原因），
            // 所以断言改成数这两处调用，而不是数原来内联的 runCatching 写法。
            Regex("!isModelAvailable\\(modelId\\)").findAll(text).count(),
        )
        assertTrue(
            "校验失败必须发事件说清原因，不能静默",
            text.contains("\"MODEL_SWITCH_FAILED\""),
        )
        assertTrue(
            "真实实现必须查设置里的模型表",
            di.contains("modelExists = { id ->") && di.contains("findModelById"),
        )
    }

    @Test
    fun `静默看门狗文案必须按判据分岔`() {
        val backend = source(backendPath)
        assertTrue(
            "异常类必须能表达「是静默卡死还是总时长到了」",
            backend.contains("class AgentAttemptTimeoutException(minutes: Int, idle: Boolean = false)"),
        )
        assertTrue(
            "调用处必须把判据传进去",
            backend.contains("idle = progress.idleTimedOut"),
        )
    }

    // ------------------------------------------------------------ v243（同一轮真机复测抓到）

    @Test
    fun `审查位只说人话不写字段时必须能从结论里兜底识别`() {
        // 下面两条是真机实测抓到的原文，必须能识别，否则整条流水线白跑
        assertEquals(
            AgentVerdict.PASS,
            inferVerdictFromConclusion("上次结论表明两项核对均满足，判为通过"),
        )
        assertEquals(
            AgentVerdict.PASS,
            inferVerdictFromConclusion(
                "理由：STATUS 行的值为 patched-v242 且包含 PATCHED_BY: pipeline-stage2 这一行，两项核对均满足。"
            ),
        )
        // 明确的否定必须判 FAIL，而且要先于肯定判（「不通过」里含「通过」）
        assertEquals(AgentVerdict.FAIL, inferVerdictFromConclusion("第二项不满足，判为不通过"))
        assertEquals(AgentVerdict.FAIL, inferVerdictFromConclusion("STATUS 行与要求不一致"))
        assertEquals(
            "同时出现肯定与否定时必须保守判 FAIL",
            AgentVerdict.FAIL,
            inferVerdictFromConclusion("第一项均满足，但第二项不满足"),
        )
        // 认不出来就老实交回主模型，绝不许瞎猜成通过
        assertEquals(AgentVerdict.UNSET, inferVerdictFromConclusion(null))
        assertEquals(AgentVerdict.UNSET, inferVerdictFromConclusion("   "))
        assertEquals(
            "只是描述过程不算表态",
            AgentVerdict.UNSET,
            inferVerdictFromConclusion("我通过读取文件的方式检查了这两处"),
        )
        assertEquals(
            "光说自己看了也不算表态",
            AgentVerdict.UNSET,
            inferVerdictFromConclusion("我看了一遍，记录了一些发现"),
        )
    }

    @Test
    fun `卡住判定必须只看多久没有新内容`() {
        val minute = 60_000L
        // v244：设置项的语义就是静默阈值本身，不再从总时长折算
        assertEquals(
            "填 0 用内置默认（10 分钟）",
            AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS,
            agentIdleTimeoutMillis(0),
        )
        assertEquals("填 5 就是连续 5 分钟没动静算卡住", 5 * minute, agentIdleTimeoutMillis(5))
        assertEquals(
            "填 20 就是 20 分钟（v243 会先折半再夹到 4~10 分钟，等于调不动）",
            20 * minute,
            agentIdleTimeoutMillis(20),
        )
        assertEquals(
            "不许超过设置项自身的上限",
            AGENT_THREAD_TIMEOUT_MAX_MINUTES * minute,
            agentIdleTimeoutMillis(999),
        )
        assertEquals("负数按 0 处理", AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS, agentIdleTimeoutMillis(-3))
        assertTrue(
            "任何合法输入都必须是正数（否则看门狗会被整个关掉，真机为此吃过亏）",
            listOf(0, 1, 5, 10, 60, 120, 999).all { agentIdleTimeoutMillis(it) > 0L },
        )
    }

    @Test
    fun `模型存在性必须能被工具层查到`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            AlwaysFailBackend(),
            scope,
            maxConcurrent = 4,
            autoResumeMax = { 0 },
            modelExists = { it == "真模型" },
        )
        assertTrue(manager.isModelAvailable("真模型"))
        assertFalse("不存在的模型必须能被查出来，工具层要靠它给出准确原因", manager.isModelAvailable("假模型"))
        Unit
    }

    @Test
    fun `verdict 兜底与工具层精确报错必须在位`() {
        val manager = source(managerPath)
        val tools = source(toolsPath)
        val backend = source(backendPath)
        assertTrue(
            "补问之后必须再兜底识别一次，否则审查位说人话就等于白跑",
            manager.contains("inferVerdictFromConclusion(spoken)"),
        )
        assertTrue(
            "兜底采纳必须留证，说明 verdict 是推断出来的",
            manager.contains("\"VERDICT_INFERRED\""),
        )
        assertTrue(
            "兜底只能在补问之后用，不能变成常规路径",
            manager.indexOf("\"VERDICT_MISSING\"") < manager.indexOf("inferVerdictFromConclusion(spoken)"),
        )
        assertTrue(
            "换模型时模型不存在，工具回执必须说清是模型的问题",
            tools.contains("switching && !manager.isModelAvailable(modelId!!)") &&
                tools.contains("model_id not found in current settings"),
        )
        assertTrue(
            "回执还要说清「没有打断正在跑的那一次」",
            tools.contains("the running attempt was NOT interrupted"),
        )
        assertTrue(
            "静默阈值必须走可单测的顶层函数，而且直接吃用户设置的分钟数",
            backend.contains("internal fun agentIdleTimeoutMillis(idleMinutes: Int)") &&
                backend.contains("val idleTimeoutMillis = agentIdleTimeoutMillis(idleLimitMinutes)"),
        )
        assertFalse(
            "旧的「4 分钟当上限」写法必须已经删掉",
            backend.contains("minOf(AGENT_IDLE_TIMEOUT_MILLIS, attemptTimeoutMillis / 2)"),
        )
    }

    // ------------------------------------------------------------ v244（界面可见性 + 时限对齐）

    @Test
    fun `流水线每棒的等待上限必须够那一棒把自愈机制走完`() {
        val fiveMinutes = 5 * 60_000L
        // 真机故障复现：调用方只给 600 秒，而那一棒光「判卡住」就要 5 分钟、还允许续跑 2 次
        val fixed = pipelineStageWaitMillis(
            requestedMillis = 600_000L,
            idleTimeoutMillis = fiveMinutes,
            autoResumeMax = 2,
        )
        val expectedFloor = fiveMinutes * 3 * 3 + AGENT_AUTO_RETRY_BACKOFF_MILLIS * 3 + 60_000L
        assertEquals("必须放大到够跑三趟 + 退避 + 余量", expectedFloor, fixed)
        assertTrue("必须明显大于调用方给的 600 秒，否则等于没修", fixed > 600_000L * 4)

        assertTrue(
            "调用方给得多就按多的来",
            pipelineStageWaitMillis(80 * 60_000L, fiveMinutes, 2) >= 80 * 60_000L,
        )
        assertEquals(
            "超过硬上限要夹住，一棒挂死不能拖住整条流水线",
            AGENT_PIPELINE_STAGE_WAIT_MAX_MILLIS,
            pipelineStageWaitMillis(600 * 60_000L, fiveMinutes, 2),
        )
        assertEquals(
            "关掉自动续跑就只留一趟的量",
            fiveMinutes * 3 + 60_000L,
            pipelineStageWaitMillis(0L, fiveMinutes, 0),
        )
        assertEquals(
            "阈值传 0 要按内置默认估，不能算成只等一分钟",
            AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS * 3 + 60_000L,
            pipelineStageWaitMillis(0L, 0L, 0),
        )
        assertTrue(
            "任何组合下都不许小于一趟的预算",
            listOf(0L, 1L, 60_000L).all { req ->
                pipelineStageWaitMillis(req, fiveMinutes, 1) >= fiveMinutes * 3
            },
        )
    }

    @Test
    fun `详情页必须把「多久没动静」单独显示出来并按时长变色`() {
        val page = source(pagePath)
        assertTrue(
            "「已跑多久」和「多久没动静」必须拆开算，挤在一行会被省略号吃掉（真机截图证据）",
            page.contains("val liveProgressText = liveRanSeconds?.let") &&
                page.contains("val liveIdleText = liveIdleSeconds?.let"),
        )
        assertTrue("文案要短，长了照样被截断", page.contains("\"没动静 \${formatAgentDuration(it)}\""))
        assertTrue(
            "超过 5 分钟没动静必须用报错色，一眼能看出卡住了",
            page.contains("idle >= 300L -> MaterialTheme.colorScheme.error"),
        )
        assertTrue(
            "超过 2 分钟先给提醒色",
            page.contains("idle >= 120L -> MaterialTheme.colorScheme.tertiary"),
        )
    }

    @Test
    fun `时限对齐的接线必须在位`() {
        val manager = source(managerPath)
        val di = source(diPath)
        assertTrue(
            "必须有可单测的时限计算函数",
            manager.contains("internal fun pipelineStageWaitMillis("),
        )
        assertTrue(
            "流水线必须真的用它算出的时限",
            manager.contains("val stageWaitMillis = pipelineStageWaitMillis(") &&
                manager.contains("awaitTerminal(listOf(spawned.id), stageWaitMillis)"),
        )
        assertTrue(
            "补问那一棒也要用同一个时限",
            manager.contains("awaitTerminal(listOf(followUp.id), stageWaitMillis)"),
        )
        assertTrue(
            "超时说明必须告诉用户内容还留着、以及该调哪个设置",
            manager.contains("可以用 resume_agent 接着跑") &&
                manager.contains("子代理单次超时"),
        )
        assertTrue(
            "卡住判定的阈值必须从设置里现读现用",
            di.contains("idleTimeoutMillis = {") && di.contains("agentThreadTimeoutMinutes"),
        )
    }

    @Test
    fun `只有探测位能联网搜索`() {
        val backend = source(backendPath)
        assertTrue(
            "必须按角色判定（用户明确要求只给探测位开）",
            backend.contains("thread.role == AgentRole.EXPLORER &&"),
        )
        assertTrue(
            "还必须跟随用户助手的联网总闸，否则等于绕过用户的全局设置",
            backend.contains("settings.getCurrentAssistant().enableWebSearch"),
        )
        assertTrue("必须真的把搜索工具装进去", backend.contains("addAll(createSearchTools(settings))"))
        assertEquals(
            "搜索工具只能在这一处装配，别的角色不许有第二条路",
            1,
            Regex("createSearchTools\\(settings\\)").findAll(backend).count(),
        )
        assertTrue(
            "工具装配必须能看到设置（否则拿不到搜索服务配置）",
            backend.contains("settings: me.rerere.rikkahub.data.datastore.Settings,") &&
                backend.contains("buildAgentTools(thread, settings)"),
        )
        assertFalse(
            "子代理仍然不许有终端、编译、删除能力",
            backend.contains("createShellTools") || backend.contains("createBuildTools"),
        )
    }

    @Test
    fun `总运行时长只剩防死循环的兜底`() {
        val backend = source(backendPath)
        assertTrue(
            "硬兜底必须是个远得碰不到的固定值，而且要明显大于静默阈值的上限",
            backend.contains("AGENT_ATTEMPT_HARD_DEADLINE_MILLIS = 4 * 60 * 60 * 1000L"),
        )
        assertTrue(
            "兜底必须至少是静默阈值上限的两倍，否则拉满阈值时又变成按总时长砍",
            AGENT_ATTEMPT_HARD_DEADLINE_MILLIS >= AGENT_THREAD_TIMEOUT_MAX_MINUTES * 60_000L * 2,
        )
        assertTrue(
            "单趟的死线必须换成硬兜底，不再用用户设置的分钟数",
            backend.contains("val attemptTimeoutMillis = AGENT_ATTEMPT_HARD_DEADLINE_MILLIS"),
        )
        assertFalse(
            "旧写法（把用户设置当总时长上限）必须已经删掉",
            backend.contains("val attemptTimeoutMillis = timeoutMinutes * 60_000L"),
        )
        assertTrue(
            "撞到兜底时的文案要说清这是防死循环的兜底，别让人以为是正常超时",
            backend.contains("防死循环的兜底上限"),
        )
    }

    // ------------------------------------------------------------ 小工具与假后端

    /** 只是为了在测试里表达「这条错误消息会被判成可恢复」 */
    private object AgentErrorKindProbe {
        fun recoverable(message: String): Boolean =
            me.rerere.rikkahub.agent.model.AgentErrorKind.classify(message) ==
                me.rerere.rikkahub.agent.model.AgentErrorKind.RECOVERABLE
    }

    /** 第一次抛可恢复错误，第二次成功 —— 用来验证「自动续跑真的会跑完」 */
    private class FailOnceThenSucceedBackend : AgentBackend {
        private var calls = 0
        var lastResume = false
            private set

        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            calls++
            lastResume = resume
            delay(20)
            if (calls == 1) throw IllegalStateException("429 Too Many Requests")
            return AgentRunOutcome(AgentReport(conclusion = "续跑之后跑完了"))
        }
    }

    /** 每次都抛可恢复错误 */
    private class AlwaysFailBackend : AgentBackend {
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
}
