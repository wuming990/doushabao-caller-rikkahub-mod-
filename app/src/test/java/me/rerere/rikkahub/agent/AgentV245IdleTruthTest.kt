package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AGENT_ATTEMPT_HARD_DEADLINE_MILLIS
import me.rerere.rikkahub.agent.runtime.AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES
import me.rerere.rikkahub.agent.runtime.AGENT_TOOL_TIMEOUT_MAX_MINUTES
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.FakeAgentBackend
import me.rerere.rikkahub.agent.runtime.agentToolWaitCeilingMillis
import me.rerere.rikkahub.agent.runtime.clipAgentMessageContent
import me.rerere.rikkahub.agent.runtime.AGENT_MESSAGE_DISPLAY_MAX_CHARS
import me.rerere.rikkahub.agent.runtime.extractAgentReportPayload
import me.rerere.rikkahub.agent.runtime.looksLikeAgentReportJson
import me.rerere.rikkahub.agent.runtime.looksLikeUnclosedReport
import me.rerere.rikkahub.agent.runtime.parseAgentReport
import me.rerere.rikkahub.agent.runtime.shouldJudgeStuck
import me.rerere.rikkahub.ui.pages.chat.agentPendingToolCount
import me.rerere.rikkahub.ui.pages.chat.agentPhaseText
import me.rerere.rikkahub.ui.pages.chat.agentPhaseTextFrom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * v245 门禁：「多久没动静」这个数字必须是真的，而且不能把正常干活判成卡死。
 *
 * ## 本版修的四件真机故障
 *
 * 1. **界面「没动静」失真（用户真机确认）**：流式输出是同一条消息记录反复覆盖（v222 为了
 *    不刷屏刻意如此），而详情页算静默用的是消息的 `createdAt` —— 那是「第一次出现」的时刻，
 *    永不更新。于是模型在一条消息里持续写下去时，正文在增长、「没动静」也在增长。
 *    用户就是靠这个数字决定要不要干预，它一失真判断全错。
 * 2. **工具执行期间被算成没动静**：工具是同步执行的（GenerationLoop 在
 *    `toolsToProcess.forEach` 里逐个跑完才再 emit），那段时间一个数据块都不会来。
 *    于是耗时超过阈值的工具会被判卡死，而且此时 `producedOutput` 往往是 false，
 *    走的是「无产出 → 同模型重试 → 换备用模型」，和真卡死完全无法区分。
 * 3. **非流式必然误杀**：助手关掉「流式输出」后，一整趟只在结束的瞬间回调一次，
 *    静默判据在这种模式下没有任何意义，只会把正常生成反复判成卡死并烧掉重试额度。
 * 4. **半截报告漏检**：`<report>` 只开不闭时，报告提取会回退成整段原文当结论 →
 *    非空 → 不判截断 → 不能续写，主模型于是拿到一份「看着完整」的半截报告。
 */
class AgentV245IdleTruthTest {

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

    private val backendPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"
    private val managerPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt"
    private val toolsPath =
        "app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt"
    private val pagePath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt"
    private val panelPath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentActivityPanel.kt"

    // ------------------------------------------------------------ 工具执行不算「没动静」

    @Test
    fun `工具正在执行时不许按没动静判卡死`() {
        val fiveMin = 5 * 60_000L
        val ceiling = agentToolWaitCeilingMillis(AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES)

        // 没有工具在跑：老规矩，超过阈值就判卡
        assertTrue(
            "没有工具在跑、且确实超过阈值 → 判卡",
            shouldJudgeStuck(
                idleForMillis = fiveMin,
                idleTimeoutMillis = fiveMin,
                toolInFlight = false,
                toolRunningForMillis = 0L,
                toolWaitCeilingMillis = ceiling,
            ),
        )
        assertFalse(
            "没超过阈值不许判卡",
            shouldJudgeStuck(fiveMin - 1, fiveMin, false, 0L, ceiling),
        )

        // 有工具在跑：静默多久都不判，只看工具本身跑了多久
        assertFalse(
            "工具在跑就不算没动静 —— 这正是本版要修的误杀",
            shouldJudgeStuck(
                idleForMillis = 60 * 60_000L,
                idleTimeoutMillis = fiveMin,
                toolInFlight = true,
                toolRunningForMillis = fiveMin,
                toolWaitCeilingMillis = ceiling,
            ),
        )
        assertTrue(
            "但工具挂死也要有上限，不然永远判不了",
            shouldJudgeStuck(
                idleForMillis = 0L,
                idleTimeoutMillis = fiveMin,
                toolInFlight = true,
                toolRunningForMillis = ceiling,
                toolWaitCeilingMillis = ceiling,
            ),
        )
    }

    @Test
    fun `静默阈值为零等于关掉看门狗`() {
        // 非流式走的就是这条路：一趟只有一次回调，静默判据毫无意义，只留硬兜底
        assertFalse(shouldJudgeStuck(99 * 60_000L, 0L, false, 0L, 10 * 60_000L))
        assertFalse(shouldJudgeStuck(99 * 60_000L, 0L, true, 99 * 60_000L, 10 * 60_000L))
    }

    @Test
    fun `工具等待上限由设置决定并且有天花板`() {
        // v246：上限从「静默阈值×2」改成用户可调的独立设置项（默认 10 分钟）。
        // 起因是用户原话：「我现在设置五分钟，为什么超过五分钟了没有停下」——
        // 当时它正卡在一次工具调用里，走的是这条闸，而界面上一个字都没提。
        assertEquals(
            "默认 10 分钟",
            AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES * 60_000L,
            agentToolWaitCeilingMillis(AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES),
        )
        assertEquals("填 5 就是 5 分钟", 5 * 60_000L, agentToolWaitCeilingMillis(5))
        assertEquals("填 60 就是 60 分钟", 60 * 60_000L, agentToolWaitCeilingMillis(60))
        assertEquals(
            "0 或负数按默认算（绝不能变成 0，那样工具挂死就永远判不了）",
            AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES * 60_000L,
            agentToolWaitCeilingMillis(0),
        )
        assertEquals(
            "负数同样按默认算",
            AGENT_TOOL_TIMEOUT_DEFAULT_MINUTES * 60_000L,
            agentToolWaitCeilingMillis(-7),
        )
        assertEquals(
            "超过可调上限要夹住，与设置界面保持一致",
            AGENT_TOOL_TIMEOUT_MAX_MINUTES * 60_000L,
            agentToolWaitCeilingMillis(9999),
        )
        assertTrue(
            "任何输入都不许超过硬兜底",
            agentToolWaitCeilingMillis(9999) <= AGENT_ATTEMPT_HARD_DEADLINE_MILLIS,
        )
        assertTrue(
            "任何合法输入都必须是正数",
            listOf(-1, 0, 1, 5, 10, 60, 9999).all { agentToolWaitCeilingMillis(it) > 0L },
        )
    }

    // ------------------------------------------------------------ 半截报告

    @Test
    fun `报告标记只开不闭必须判成没写完`() {
        assertTrue(
            "写到一半被切断",
            looksLikeUnclosedReport("<report>\n{\"conclusion\": \"我查了三处，第一处"),
        )
        assertFalse(
            "完整闭合的报告不许误判",
            looksLikeUnclosedReport("<report>{\"conclusion\":\"好了\"}</report>"),
        )
        assertFalse(
            "完全没用标记的（廉价模型不听话）保持旧行为，不误判",
            looksLikeUnclosedReport("结论：我查完了，没问题。"),
        )
        assertTrue(
            "先示范一次完整格式、再写正式报告时被切断 → 仍要判没写完",
            looksLikeUnclosedReport("示范：<report>格式</report>\n正式：<report>正文写到一半"),
        )
        assertFalse(
            "示范之后的正式报告也闭合了 → 不判",
            looksLikeUnclosedReport("示范：<report>格式</report>\n正式：<report>正文</report>"),
        )
        assertFalse("空串不许判", looksLikeUnclosedReport(""))
        assertTrue("大小写不敏感", looksLikeUnclosedReport("<REPORT>没闭合"))
    }

    // ------------------------------------------------------------ 阶段推导

    private fun toolEvent(threadId: String, callId: String, executed: Boolean) = AgentEvent(
        id = "$threadId:$callId:" + if (executed) "RESULT" else "CALL",
        threadId = threadId,
        type = if (executed) "TOOL_RESULT" else "TOOL_CALL",
        detail = "workspace_read_file",
        createdAt = Instant.now(),
    )

    private fun thread(status: AgentThreadStatus) = AgentThread(
        id = "t1",
        conversationId = "conv-1",
        task = "任务",
        status = status,
        createdAt = Instant.now(),
    )

    @Test
    fun `阶段必须能分清等待模型、工具执行和重来`() {
        assertNull("没有线程就不显示", agentPhaseText(null, emptyList(), false))
        assertNull(
            "终态不显示阶段",
            agentPhaseText(thread(AgentThreadStatus.SUCCEEDED), emptyList(), true),
        )
        assertEquals(
            "还没吐字",
            "等待模型返回",
            agentPhaseText(thread(AgentThreadStatus.RUNNING), emptyList(), false),
        )
        assertEquals(
            "已经在写了",
            "正在输出",
            agentPhaseText(thread(AgentThreadStatus.RUNNING), emptyList(), true),
        )
        assertEquals(
            "工具发出去还没回来 —— 这时候「没动静」在涨是正常的",
            "工具执行中",
            agentPhaseText(
                thread(AgentThreadStatus.RUNNING),
                listOf(toolEvent("t1", "call-1", executed = false)),
                true,
            ),
        )
        assertEquals(
            "工具回来了就不再算执行中",
            "正在输出",
            agentPhaseText(
                thread(AgentThreadStatus.RUNNING),
                listOf(
                    toolEvent("t1", "call-1", executed = false),
                    toolEvent("t1", "call-1", executed = true),
                ),
                true,
            ),
        )
        assertEquals(
            "多个工具只要有一个没回来就算执行中",
            "工具执行中",
            agentPhaseText(
                thread(AgentThreadStatus.RUNNING),
                listOf(
                    toolEvent("t1", "call-1", executed = false),
                    toolEvent("t1", "call-1", executed = true),
                    toolEvent("t1", "call-2", executed = false),
                ),
                true,
            ),
        )
        assertEquals(
            "等待自动重试要说清，别让人以为它死了",
            "等待自动重试",
            agentPhaseText(thread(AgentThreadStatus.WAITING_AUTO_RETRY), emptyList(), true),
        )
        listOf(
            "MODEL_FALLBACK" to "正在换备用模型",
            "MODEL_RETRY" to "同一个模型再试一次",
            "TIMEOUT" to "刚判过卡死，正在重来",
        ).forEach { (type, expected) ->
            assertEquals(
                expected,
                agentPhaseText(
                    thread(AgentThreadStatus.RUNNING),
                    listOf(
                        AgentEvent(
                            threadId = "t1",
                            type = type,
                            detail = "d",
                            createdAt = Instant.now(),
                        )
                    ),
                    true,
                ),
            )
        }
    }

    // ------------------------------------------------------------ 真实活动时间（真跑）

    @Test
    fun `活动时间必须在跑的时候有、结束之后清掉`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 500, messageCount = 4),
            scope,
            maxConcurrent = 4,
        )
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "边写边看"))

        // 跑的时候必须拿得到活动时间戳（界面靠它算「多久没动静」）
        withTimeout(10_000) {
            while (manager.idleSecondsOrNull(thread.id) == null) delay(20)
        }
        assertNotNull("运行中必须有实时活动时间", manager.idleSecondsOrNull(thread.id))
        assertTrue(
            "刚有产出，静默秒数必须很小",
            (manager.idleSecondsOrNull(thread.id) ?: 999L) < 30L,
        )

        withTimeout(15_000) {
            while (repo.thread(thread.id)?.status?.isTerminal != true) delay(20)
        }
        // 终态之后不该再残留（否则界面会显示成「刚刚还在动」，而且会堆内存）
        withTimeout(5_000) {
            while (manager.idleSecondsOrNull(thread.id) != null) delay(20)
        }
        assertNull("结束后必须清掉活动记录", manager.idleSecondsOrNull(thread.id))
        Unit
    }

    @Test
    fun `停止之后不许再显示刚刚还在动`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val manager = AgentThreadManager(
            repo,
            FakeAgentBackend(delayMillis = 3_000, messageCount = 5),
            scope,
            maxConcurrent = 4,
            autoResumeMax = { 0 },
        )
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "跑久一点"))
        withTimeout(10_000) {
            while (manager.idleSecondsOrNull(thread.id) == null) delay(20)
        }
        manager.stop(thread.id)
        assertNull("停止的那一刻就要清掉", manager.idleSecondsOrNull(thread.id))
        Unit
    }

    // ------------------------------------------------------------ 接线门禁

    @Test
    fun `后端接线必须在位`() {
        val backend = source(backendPath)
        assertTrue(
            "静默阈值必须仍然走可单测的顶层函数",
            backend.contains("val idleTimeoutMillis = agentIdleTimeoutMillis(idleLimitMinutes)"),
        )
        assertTrue(
            "非流式必须关掉静默看门狗，否则正常生成会被反复判卡死",
            backend.contains("val streamingOutput = settings.getCurrentAssistant().streamOutput") &&
                backend.contains("val watchdogIdleMillis = if (streamingOutput) idleTimeoutMillis else 0L"),
        )
        assertTrue(
            "看门狗必须真的用放大后的那个值，而不是原始阈值",
            backend.contains("idleTimeoutMillis = watchdogIdleMillis,"),
        )
        assertTrue(
            "判卡必须走可单测的纯函数",
            backend.contains("if (shouldJudgeStuck("),
        )
        assertTrue(
            "工具开始/结束都要打点，否则工具时间又会被算成没动静",
            backend.contains("progress.markToolCall(tool.toolCallId)") &&
                backend.contains("progress.markToolResult(tool.toolCallId)"),
        )
        assertTrue(
            "截断必须由新的报告评估状态机驱动（v251），不再直接拼布尔表达式",
            backend.contains("val truncated = when (assessment) {") &&
                backend.contains("AgentReportAssessment.REPAIR_REPLACE"),
        )
        assertTrue(
            "半截报告也要写事件说明原因",
            backend.contains("val unclosedReport = looksLikeUnclosedReport(rawFinalText)"),
        )
        assertFalse(
            "旧的「只看静默」写法必须已经删掉",
            backend.contains("if (idleFor >= idleTimeoutMillis) {"),
        )
    }

    @Test
    fun `管理器与工具层接线必须在位`() {
        val manager = source(managerPath)
        val tools = source(toolsPath)
        assertTrue(
            "活动时间必须是内存态（不许为此升数据库版本）",
            manager.contains("private val lastActivityAt = MutableStateFlow<Map<String, Long>>(emptyMap())"),
        )
        assertTrue(
            "消息和事件两条路都要打点",
            manager.contains("repository.insertMessage(it)") &&
                manager.contains("repository.insertEvent(it)") &&
                Regex("touchActivity\\(threadId\\)").findAll(manager).count() >= 3,
        )
        assertTrue(
            "运行结束、停止、关闭三处都要清理",
            Regex("clearActivity\\(threadId\\)").findAll(manager).count() >= 3,
        )
        assertTrue(
            "工具层必须能查到「多久没动静」",
            manager.contains("fun idleSecondsOrNull(threadId: String): Long?"),
        )
        assertTrue(
            "wait_agents 必须回传 idle_seconds，否则主模型分不清在干活还是卡住",
            tools.contains("put(\"idle_seconds\", idle)"),
        )
        assertTrue(
            "还要明确告诉主模型「没结束不等于失败」",
            tools.contains("put(\"still_running\", stillRunning.size)") &&
                tools.contains("还有线程没结束，这不等于失败"),
        )
        assertTrue(
            "也要回传「它马上会自己接着跑」",
            tools.contains("put(\"auto_resume_planned\", manager.isAutoResumePlanned(t.id))"),
        )
        // AgentDatabase 版本是禁区，这里顺手守住
        val db = source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentDatabase.kt")
        assertTrue("AgentDatabase 版本必须保持 3", db.contains("version = 3,"))
    }

    @Test
    fun `界面必须用真实活动时间并显示阶段`() {
        val page = source(pagePath)
        val panel = source(panelPath)
        assertTrue(
            "详情页必须把管理器的实时打点算进去（这是本版修的核心）",
            page.contains("val liveActivity by manager.activityFlow.collectAsStateWithLifecycle()") &&
                page.contains("liveActivity[threadId] ?: 0L,"),
        )
        assertTrue(
            "数据库时间仍作为退路保留（刚进页面/已结束时用）",
            page.contains("messages.maxOfOrNull { it.createdAt.toEpochMilli() }") &&
                page.contains("events.maxOfOrNull { it.createdAt.toEpochMilli() }"),
        )
        assertTrue(
            "详情页要显示当前阶段",
            page.contains("val livePhaseText = agentPhaseText("),
        )
        assertTrue(
            "同一条消息内容变长也要跟到最新，而且必须节流",
            page.contains("snapshotFlow { messages.sumOf { it.content.length } }") &&
                page.contains("if (now - lastScrollAtMillis < 700L) return@collect"),
        )
        assertTrue(
            "聊天页面板也要显示时长与静默，且沿用同一套变色判据",
            panel.contains("已跑 \${formatAgentDuration(ranSeconds)}") &&
                panel.contains("没动静 \${formatAgentDuration(idleSeconds)}") &&
                panel.contains("idleSeconds >= 300L -> MaterialTheme.colorScheme.error"),
        )
        assertTrue(
            "面板的每秒计时只在有活动线程时开，别让整页每秒重组",
            panel.contains("val ticking = activeCount > 0") &&
                panel.contains("LaunchedEffect(ticking)"),
        )
    }

    // ==================== v246（报告提取取错位置 + 工具上限可调 + 面板说原因）====================

    @Test
    fun `报告正文里引用 report 标记时不许把报告切坏`() {
        // 真机事故复现：子代理的任务正好是核实报告标记相关的代码，于是它在报告正文里
        // 同时引用了开标记和闭标记。非贪婪正则的第一个匹配会在**被引用的闭标记**处收尾，
        // 于是取到的是一段半截 JSON —— 主模型拿到的报告开头是从中间开始的碎片。
        val raw = buildString {
            appendLine("好的，我来核实这四处改动。")
            appendLine("<report>")
            appendLine(
                "{\"conclusion\":\"四处改动全部在位\"," +
                    "\"evidence\":[\"backend.kt:900 —— 判据是「最后一个 <report> 之后还有没有 </report>」\"," +
                    "\"manager.kt:257 —— lastActivityAt 是内存态\"]," +
                    "\"uncertainties\":[],\"suggestions\":[]}"
            )
            appendLine("</report>")
        }

        // 先证明这个用例真的踩到了旧行为：只取「最后一个非贪婪块」会截出半截 JSON
        val lastBlockOnly = Regex(
            "<report>(.*?)</report>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).findAll(raw).last().groupValues[1].trim()
        assertFalse(
            "旧行为在这个用例下必须是坏的，否则这条测试没有意义",
            looksLikeAgentReportJson(lastBlockOnly),
        )

        val payload = extractAgentReportPayload(raw)
        assertTrue(
            "提取结果必须是完整 JSON，不能在被引用的闭标记处截断",
            payload.trim().startsWith("{"),
        )
        assertTrue("提取出来的必须能解析成结构化报告", looksLikeAgentReportJson(payload))
        assertEquals("四处改动全部在位", parseAgentReport(payload).conclusion)
        assertEquals("两条证据都要在，不能丢", 2, parseAgentReport(payload).evidence.size)
        // 既然能解析出完整报告，就绝不能再判成「写到一半被切断」，
        // 否则白触发一次续跑、多花一趟钱（v246 的组合判据就是为这个加的）
        assertFalse(
            "能解析出报告时不许判截断",
            looksLikeUnclosedReport(raw) && !looksLikeAgentReportJson(payload),
        )
    }

    @Test
    fun `先示范格式再写正文时仍然取正文`() {
        // v235 的原始意图不能被 v246 改坏：两个块都不是 JSON 时，仍取最后一块
        val raw = "格式示范：<report>这里放报告</report>\n下面是正式的：<report>最终结论</report>"
        assertEquals("最终结论", extractAgentReportPayload(raw))
    }

    @Test
    fun `真正被切断的报告仍然要判成没写完`() {
        val cut = "<report>\n{\"conclusion\":\"我查了三处，第一处"
        assertTrue("没有闭标记 → 判没写完", looksLikeUnclosedReport(cut))
        val payload = extractAgentReportPayload(cut)
        assertFalse(
            "没有完整块时提取会回退成全文，全文不是合法报告",
            looksLikeAgentReportJson(payload),
        )
        // 两个条件同时成立才算截断（这正是 v246 在后端里的组合判据）
        assertTrue(looksLikeUnclosedReport(cut) && !looksLikeAgentReportJson(payload))
    }

    @Test
    fun `能解析成报告的文本才算报告`() {
        assertTrue(looksLikeAgentReportJson("{\"conclusion\":\"好了\"}"))
        assertTrue(
            "带 json 代码围栏也要认",
            looksLikeAgentReportJson("```json\n{\"conclusion\":\"好了\"}\n```"),
        )
        assertFalse("纯文字不算", looksLikeAgentReportJson("结论：好了"))
        assertFalse("JSON 碎片不算", looksLikeAgentReportJson("标记没有闭合\"],\"suggestions\":[]}"))
        assertFalse("空串不算", looksLikeAgentReportJson(""))
    }

    @Test
    fun `待返回工具数必须按调用与结果配对`() {
        assertEquals(0, agentPendingToolCount(emptyList()))
        assertEquals(
            1,
            agentPendingToolCount(listOf(toolEvent("t1", "c1", executed = false))),
        )
        assertEquals(
            0,
            agentPendingToolCount(
                listOf(
                    toolEvent("t1", "c1", executed = false),
                    toolEvent("t1", "c1", executed = true),
                )
            ),
        )
        assertEquals(
            "两个发出去、一个回来 → 还欠 1 个",
            1,
            agentPendingToolCount(
                listOf(
                    toolEvent("t1", "c1", executed = false),
                    toolEvent("t1", "c2", executed = false),
                    toolEvent("t1", "c1", executed = true),
                )
            ),
        )
    }

    @Test
    fun `阶段判定的两个入口必须给出同一个答案`() {
        // 详情页走事件轨迹、聊天页面板走内存里的阶段信号，两边必须说同一句话
        val events = listOf(
            toolEvent("t1", "c1", executed = false),
        )
        assertEquals(
            agentPhaseText(thread(AgentThreadStatus.RUNNING), events, hasMessages = true),
            agentPhaseTextFrom(
                status = AgentThreadStatus.RUNNING,
                lastEventType = events.last().type,
                pendingTools = agentPendingToolCount(events),
                hasMessages = true,
            ),
        )
        assertEquals(
            "工具执行中",
            agentPhaseTextFrom(AgentThreadStatus.RUNNING, "TOOL_CALL", 1, true),
        )
        assertNull(
            "终态不显示阶段",
            agentPhaseTextFrom(AgentThreadStatus.STOPPED, "TOOL_CALL", 1, true),
        )
        assertNull("没有状态就不显示", agentPhaseTextFrom(null, null, 0, false))
    }

    @Test
    fun `工具上限设置项必须五环节齐全`() {
        val store = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        listOf(
            "val AGENT_TOOL_TIMEOUT = intPreferencesKey(\"agent_tool_timeout_minutes\")",
            "agentToolTimeoutMinutes = (preferences[AGENT_TOOL_TIMEOUT] ?: 10).coerceIn(1, 60)",
            "preferences[AGENT_TOOL_TIMEOUT] = settings.agentToolTimeoutMinutes.coerceIn(1, 60)",
            "val agentToolTimeoutMinutes: Int = 10,",
        ).forEach { needle ->
            // 少了「写入」那一环，用户配好的值会自己变回默认（v219 真踩过）
            assertTrue("PreferencesStore 缺少：$needle", store.contains(needle))
        }
        val ui = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue("设置界面必须有这一项", ui.contains("agent_tools_tool_timeout_title"))
        assertTrue("必须能改", ui.contains("settings.copy(agentToolTimeoutMinutes ="))
        val backend = source(backendPath)
        assertTrue(
            "后端必须真的用这个设置，而不是继续按静默阈值折算",
            backend.contains("agentToolWaitCeilingMillis(settings.agentToolTimeoutMinutes)"),
        )
        assertTrue(
            "卡住判定的说明里要写清「工具执行另算」，否则用户会以为设置没生效",
            source("app/src/main/res/values-zh/strings.xml").contains("工具执行时间另算"),
        )
    }

    @Test
    fun `工具在跑时两个界面都不许把没动静标红`() {
        val page = source(pagePath)
        val panel = source(panelPath)
        assertTrue(
            "详情页：工具在跑时不标红",
            page.contains("livePendingTools > 0 -> MaterialTheme.colorScheme.primary"),
        )
        assertTrue(
            "面板：工具在跑时不标红",
            panel.contains("toolRunning -> MaterialTheme.colorScheme.primary"),
        )
        assertTrue(
            "面板还要把阶段显示出来，否则用户看不出「为什么没动静」",
            panel.contains("phaseText = agentPhaseTextFrom(") &&
                panel.contains("toolRunning = (signal?.pendingTools ?: 0) > 0"),
        )
        assertTrue(
            "面板要读管理器给的阶段信号",
            panel.contains("val stage by manager.stageFlow.collectAsStateWithLifecycle()"),
        )
        val manager = source(managerPath)
        assertTrue(
            "阶段信号必须是内存态，且新一段运行要归零",
            manager.contains("private val liveStage = MutableStateFlow<Map<String, AgentStageSignal>>(emptyMap())") &&
                manager.contains("resetStage(threadId)"),
        )
        assertTrue(
            "工具调用与结果都要更新阶段信号",
            manager.contains("noteStageEvent(threadId, it.type)") &&
                manager.contains("noteStageMessage(threadId)"),
        )
        assertTrue(
            "结束、停止、关闭三处都要清理阶段信号",
            Regex("clearStage\\(threadId\\)").findAll(manager).count() >= 3,
        )
    }

    // ---------------------------------------------------------------- v249

    @Test
    fun `子代理消息超长必须保留尾部而不是砍掉尾部`() {
        val head = "开头".repeat(20000)
        val tail = "最新在写这一段"
        val long = head + tail
        assertTrue("构造的样本必须真的超过上限", long.length > AGENT_MESSAGE_DISPLAY_MAX_CHARS)

        val clipped = clipAgentMessageContent(long)
        assertTrue("不得超过上限", clipped.length <= AGENT_MESSAGE_DISPLAY_MAX_CHARS)
        assertTrue(
            "尾部必须留住 —— 用户要看的正是「它最新在写什么」",
            clipped.endsWith(tail),
        )
        assertTrue("开头也要留一点，便于看它开局干了什么", clipped.startsWith("开头"))
        assertTrue("必须明示省略了多少，不许静默丢内容", clipped.contains("中间省略"))
    }

    @Test
    fun `没超上限的消息必须一字不改`() {
        val short = "短消息，没超上限"
        assertEquals(short, clipAgentMessageContent(short))
        assertEquals("", clipAgentMessageContent("随便", maxChars = 0))
    }

    @Test
    fun `详情页必须有底部锚点否则到最新会停在长回复开头`() {
        val page = source(pagePath)
        assertTrue(
            "列表末尾必须有底部锚点，否则 scrollToItem(total - 1) 会把长回复的顶部对齐屏幕顶部",
            page.contains("item(key = \"scroll_bottom_anchor\")"),
        )
        assertTrue(
            "旧的「只留前 16KB」写法不许再出现",
            !source(backendPath).contains("content.take(16 * 1024)"),
        )
        assertTrue(
            "后端必须改用保留头尾的裁剪",
            source(backendPath).contains("content = clipAgentMessageContent(content),"),
        )
    }
}
