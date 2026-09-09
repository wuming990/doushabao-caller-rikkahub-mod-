package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.agent.model.AgentErrorKind
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentFinishReason
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AGENT_AUTO_RETRY_MAX
import me.rerere.rikkahub.agent.runtime.AGENT_RESUME_TOTAL_MAX
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.tools.createAgentControlTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v235 门禁：上游不稳定时子代理必须能自己爬起来，爬不起来必须让主模型知道。
 *
 * ## 背景
 *
 * 子代理常用便宜模型跑，上游限流 / 5xx / 连接被重置会时不时发生。
 * v222 起「续跑」机制本身是完整的（保留中断前内容 + 回灌历史 + 追加续写指令），
 * 但两个缺口让它在实际使用中派不上用场：
 *
 * 1. `resume()` 只挂在界面的「继续输出」按钮上，**主模型没有对应工具**，
 *    看得到 `can_resume: true` 却无法自救，整条流水线要停下来等人手点；
 * 2. 所有异常都只落成 `FAILED` + 一段文本，**主线无法分辨**
 *    「限流，再试一下就好」和「API Key 无效，试一百次也一样」。
 *
 * v235 补上：错误分类、可恢复错误自动续跑（有上限）、`resume_agent` 工具、
 * 以及把 `resume_count` / `error_kind` 回传给主模型。
 */
class AgentAutoResumeTest {

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

    // ------------------------------------------------------------ 错误分类

    @Test
    fun `限流与服务端错误判为可恢复`() {
        listOf(
            "429 Too Many Requests",
            "rate limit exceeded, please retry",
            "HTTP 503 Service Unavailable",
            "502 Bad Gateway",
            "500 internal server error",
            "The engine is currently overloaded",
        ).forEach {
            assertEquals("「$it」应判为可恢复", AgentErrorKind.RECOVERABLE, AgentErrorKind.classify(it))
        }
    }

    @Test
    fun `网络类错误判为可恢复`() {
        listOf(
            "java.net.SocketTimeoutException: timeout",
            "Connection reset by peer",
            "unexpected end of stream on https://example.com",
            "failed to connect to api.example.com",
            "Software caused connection abort",
            "stream was reset: CANCEL",
        ).forEach {
            assertEquals("「$it」应判为可恢复", AgentErrorKind.RECOVERABLE, AgentErrorKind.classify(it))
        }
    }

    @Test
    fun `认证与参数错误判为致命`() {
        listOf(
            "401 Unauthorized",
            "Incorrect API key provided",
            "403 permission denied",
            "insufficient_quota: You exceeded your current quota",
            "model not found: gpt-9",
            "400 invalid_request_error",
            "模型不可用（未配置或已删除）",
        ).forEach {
            assertEquals("「$it」应判为致命", AgentErrorKind.FATAL, AgentErrorKind.classify(it))
        }
    }

    @Test
    fun `判不出来时必须返回未知而不是瞎猜`() {
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify(null))
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify(""))
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify("   "))
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify("something odd happened"))
    }

    @Test
    fun `致命判定必须优先于可恢复`() {
        // FATAL 关键词特异性更强；timeout 这种词可能出现在任何消息里。
        // 宁可少自动救一次，也不要在明确无救的错误上白烧配额。
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("401 unauthorized (connection timeout while validating key)"),
        )
    }

    // ------------------------------------------------------------ 上限

    @Test
    fun `重试上限必须存在且合理`() {
        assertTrue("自动重试上限必须为正", AGENT_AUTO_RETRY_MAX >= 1)
        assertTrue("自动重试上限不能太大，否则坏上游会反复烧配额", AGENT_AUTO_RETRY_MAX <= 3)
        assertTrue(
            "续跑总上限必须大于自动重试上限，否则手动续跑没有余量",
            AGENT_RESUME_TOTAL_MAX > AGENT_AUTO_RETRY_MAX,
        )
    }

    // ------------------------------------------------------------ resume_agent 工具

    private fun managerWith(backend: AgentBackend) =
        AgentThreadManager(InMemoryAgentThreadRepository(), backend, scope, maxConcurrent = 4)

    private fun exec(manager: AgentThreadManager, toolName: String, args: String): String {
        val tool = createAgentControlTools("conv-1", manager).first { it.name == toolName }
        return runBlocking { tool.execute(Json.parseToJsonElement(args)) }
            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString("\n") { it.text }
    }

    private fun field(json: String, key: String): String? =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun `resume_agent 必须被注册进控制工具`() {
        val names = createAgentControlTools("conv-1", managerWith(OkBackend())).map { it.name }
        assertTrue("必须提供 resume_agent，否则主模型无法自救", names.contains("resume_agent"))
        // v236：新增 run_agent_pipeline（一次跑完整条流水线）
        assertTrue("必须提供 run_agent_pipeline", names.contains("run_agent_pipeline"))
        assertEquals("控制工具应为 8 个", 8, names.size)
    }

    @Test
    fun `resume_agent 对不存在的线程必须明确报错`() {
        val out = exec(managerWith(OkBackend()), "resume_agent", """{"thread_id":"no-such-id"}""")
        assertNotNull("必须返回 error", field(out, "error"))
        assertTrue(field(out, "error")!!.contains("not found"))
    }

    @Test
    fun `resume_agent 缺少参数必须报错`() {
        val out = exec(managerWith(OkBackend()), "resume_agent", """{}""")
        assertTrue(field(out, "error")!!.contains("thread_id"))
    }

    @Test
    fun `完整成功的线程不得允许续跑`() = runBlocking {
        val manager = managerWith(OkBackend())
        val id = field(exec(manager, "spawn_agent", """{"task":"正常任务"}"""), "thread_id")!!
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == id && it.status == AgentThreadStatus.SUCCEEDED }) {
                delay(20)
            }
        }
        val out = exec(manager, "resume_agent", """{"thread_id":"$id"}""")
        val error = field(out, "error")
        assertNotNull("完整成功不该能续跑", error)
        assertTrue("错误必须说明原因与状态", error!!.contains("not resumable"))
    }

    @Test
    fun `致命失败的线程可以被主模型手动接回来`() = runBlocking {
        // v292：FATAL 错误也会自动续跑（全拆黑名单），改用 autoResumeMax=0 关闭自动续跑
        // 来隔离时序（顺带验证「设 0 = 完全不自动续」对 FATAL 同样成立）
        val manager = AgentThreadManager(
            InMemoryAgentThreadRepository(), FatalBackend(), scope, maxConcurrent = 4,
            autoResumeMax = { 0 },
        )
        val id = field(exec(manager, "spawn_agent", """{"task":"注定失败"}"""), "thread_id")!!
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == id && it.status == AgentThreadStatus.FAILED }) {
                delay(20)
            }
        }
        val failed = manager.list("conv-1").first { it.id == id }
        assertTrue("失败线程必须允许续跑", failed.canResume)
        assertEquals("致命错误不得自动重试", 0, failed.resumeCount)

        val out = exec(manager, "resume_agent", """{"thread_id":"$id"}""")
        assertEquals("true", field(out, "ok"))
        assertEquals("1", field(out, "resume_count"))
    }

    // ------------------------------------------------------------ 源码门禁

    @Test
    fun `自动重试不再按错误分类拦截且有上限(v292全拆黑名单)`() {
        val manager = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt")
        assertTrue(
            "失败分支必须先分类错误（分类仍用于事件标注与展示）",
            manager.contains("val kind = AgentErrorKind.classify(message)"),
        )
        assertTrue("必须调用自动续跑调度", manager.contains("scheduleAutoResume(threadId, kind)"))
        assertTrue(
            // v292：用户拍板「全拆黑名单」——任何错误（含 FATAL）都自动续跑到次数用尽。
            // 反向断言钉住：不许把 FATAL 拦截加回来。
            "FATAL 拦截又回来了（用户 v292 拍板全拆黑名单，不许复活）",
            !manager.contains("if (kind == AgentErrorKind.FATAL) return"),
        )
        assertTrue(
            "v239：看不出类型（UNKNOWN）不再直接放弃",
            !manager.contains("if (kind != AgentErrorKind.RECOVERABLE) return"),
        )
        assertTrue(
            // v241：上限改成用户可调（设置里的「自动续跑次数」），常量只作为默认值。
            // 门禁意图不变：必须有上限检查，不许无限自动续跑。
            "必须检查自动重试上限",
            manager.contains("if (used >= max) {") &&
                manager.contains("runCatching { autoResumeMax() }.getOrDefault(AGENT_AUTO_RETRY_MAX)"),
        )
        assertTrue(
            "v241：设成 0 必须完全不自动续跑",
            manager.contains("if (max <= 0) {"),
        )
        assertTrue(
            // v241：总上限跟着「自动续跑次数」放宽，否则用户设到 10 也会在第 8 次被挡下来
            "resume 必须检查续跑总上限",
            manager.contains("if (thread.resumeCount >= totalMax) return null") &&
                manager.contains("AGENT_RESUME_TOTAL_MAX,"),
        )
        assertTrue("成功后必须清零自动重试预算", manager.contains("autoRetryCount.remove(threadId)"))
    }

    @Test
    fun `进度查询必须暴露重试所需的信息`() {
        val control = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt")
        assertTrue("必须回传 resume_count", control.contains("put(\"resume_count\", t.resumeCount)"))
        assertTrue("必须回传 resume_limit", control.contains("put(\"resume_limit\", AGENT_RESUME_TOTAL_MAX)"))
        assertTrue(
            "必须回传 error_kind，主模型才能决定等重试还是自己接手",
            control.contains("put(\"error_kind\", AgentErrorKind.classify(t.error).name)"),
        )
    }

    @Test
    fun `自动重试不得偷偷绕过用户的停止操作`() {
        // 停止/关闭后 canResume 会变，resume() 内部的检查是唯一防线，不能被删。
        val manager = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt")
        assertTrue(
            "resume 必须仍然检查 canResume",
            manager.contains("if (!thread.canResume) return null"),
        )
        assertTrue(
            "resume 必须仍然检查是否已在运行",
            manager.contains("if (jobs.containsKey(threadId)) return null"),
        )
    }

    @Test
    fun `子代理权限边界不得因本轮改动被放开`() {
        val readOnly = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentReadOnlyTools.kt")
        listOf("workspace_write_file", "workspace_edit_file", "workspace_shell").forEach {
            assertFalse("子代理仍不得拥有 $it", readOnly.contains("name = \"$it\""))
        }
        // v236：写能力被挪到独立文件，且必须白名单非空才装配；
        // shell / 编译 / 删除 / 改名一律不给（编译独占 Gradle，只能留在主模型手里）。
        val writable = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentWritableTools.kt")
        assertTrue(
            "写工具必须在白名单为空时返回空列表（默认只读）",
            writable.contains("if (allowed.isEmpty()) return emptyList()"),
        )
        listOf("workspace_shell", "workspace_publish_file", "executeCommand", "deleteFile", "moveFile")
            .forEach {
                assertFalse("写工具集不得出现 $it", writable.contains(it))
            }
    }

    // ------------------------------------------------------------ 假后端

    private class OkBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            delay(30)
            onMessage(AgentMessage(threadId = thread.id, role = "assistant", content = "done"))
            return AgentRunOutcome(
                report = AgentReport(conclusion = "完成 ${thread.task}"),
                finishReason = "stop",
                truncated = false,
            )
        }
    }

    // ------------------------------------------------------------ v240：没交报告 = 没写完

    @Test
    fun `以工具调用收尾的结束原因必须判为没写完`() {
        listOf("tool_calls", "tool_use", "TOOL_CALLS", "function_call").forEach {
            assertTrue("「$it」应判为没写完", AgentFinishReason.isUnfinished(it))
        }
        listOf("stop", "end_turn", "length", "max_tokens", null, "", "   ").forEach {
            assertTrue("「$it」不该被误判成没写完", !AgentFinishReason.isUnfinished(it))
        }
    }

    @Test
    fun `截断的成功线程必须允许续跑`() {
        val thread = AgentThread(
            conversationId = "conv-1",
            task = "t",
            status = AgentThreadStatus.SUCCEEDED,
            truncated = true,
            createdAt = java.time.Instant.now(),
        )
        assertTrue("SUCCEEDED + 没写完 必须能接回来", thread.canResume)
    }

    @Test
    fun `没写完的线程会自动从断点接着写`() = runBlocking {
        val backend = TruncatedThenOkBackend()
        val manager = managerWith(backend)
        val id = field(exec(manager, "spawn_agent", """{"task":"会被切断的活"}"""), "thread_id")!!
        // 第一轮：空报告 + 没写完 → v239 起会自动接着写（退避 4 秒）
        withTimeout(30_000) {
            while (manager.list("conv-1").none { it.id == id && it.resumeCount >= 1 }) {
                delay(50)
            }
        }
        withTimeout(30_000) {
            while (manager.list("conv-1")
                    .none { it.id == id && it.status == AgentThreadStatus.SUCCEEDED && !it.truncated }
            ) {
                delay(50)
            }
        }
        val done = manager.list("conv-1").first { it.id == id }
        assertTrue("第二轮必须补齐报告", AgentReport.decode(done.reportJson)?.conclusion?.isNotBlank() == true)
        assertTrue("必须真的走了续跑而不是重头跑", backend.resumeCalls >= 1)
    }

    @Test
    fun `门禁：没交报告必须置位没写完并写事件`() {
        val backend = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        assertTrue(
            "必须识别「以工具调用收尾」",
            backend.contains("val unfinished = AgentFinishReason.isUnfinished(finishReason)"),
        )
        assertTrue(
            "必须识别「报告一个字都没有」",
            backend.contains("val reportEmpty = report.conclusion.isBlank()"),
        )
        assertTrue(
            "两种情况都要进入报告评估（占位/格式无效走修复，不再直接成功）",
            backend.contains("var assessment = assessAgentReport("),
        )
        assertTrue(
            "v251：truncated 由评估结果驱动",
            backend.contains("val truncated = when (assessment) {"),
        )
        assertTrue(
            "必须写事件告诉用户为什么没拿到报告",
            backend.contains("\"REPORT_INCOMPLETE\""),
        )
        assertTrue(
            "事件里要提示最可能的原因是步数用完",
            backend.contains("步数用完了"),
        )
    }

    /** v240：第一轮空报告 + 没写完，第二轮才交完整报告 —— 复现真机那次 tool_calls 空报告 */
    private class TruncatedThenOkBackend : AgentBackend {
        @Volatile
        var resumeCalls: Int = 0

        private var round = 0

        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            if (resume) resumeCalls++
            delay(20)
            round++
            return if (round == 1) {
                AgentRunOutcome(
                    report = AgentReport(conclusion = ""),
                    finishReason = "tool_calls",
                    truncated = true,
                )
            } else {
                AgentRunOutcome(
                    report = AgentReport(conclusion = "补齐后的结论：${thread.task}"),
                    finishReason = "stop",
                    truncated = false,
                )
            }
        }
    }

    // ------------------------------------------------------------ v241：次数可调 + 中途换模型

    @Test
    fun `自动续跑次数设为 0 时不许自己跑起来`() = runBlocking {
        val manager = AgentThreadManager(
            InMemoryAgentThreadRepository(),
            RecoverableBackend(),
            scope,
            maxConcurrent = 4,
            autoResumeMax = { 0 },
        )
        val id = field(exec(manager, "spawn_agent", """{"task":"必定报可恢复错误"}"""), "thread_id")!!
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == id && it.status == AgentThreadStatus.FAILED }) {
                delay(20)
            }
        }
        // 等过一整个退避窗口，确认它没有自己复活
        delay(6_000)
        val thread = manager.list("conv-1").first { it.id == id }
        assertEquals("设成 0 就一次都不许自动续跑", 0, thread.resumeCount)
        assertEquals(AgentThreadStatus.FAILED, thread.status)
    }

    @Test
    fun `致命错误也会自动续跑(v292全拆黑名单)`() = runBlocking {
        // v292 用户拍板「全拆黑名单」：FATAL（401 密钥错）不再被拦截，照样排自动续跑。
        val manager = AgentThreadManager(
            InMemoryAgentThreadRepository(), FatalBackend(), scope, maxConcurrent = 4,
            autoResumeMax = { 1 },
        )
        val id = field(exec(manager, "spawn_agent", """{"task":"密钥错误也续跑"}"""), "thread_id")!!
        // 同时等「续跑发生了」和「次数用尽后停在 FAILED」两个条件，避免读到中间态
        withTimeout(30_000) {
            while (manager.list("conv-1").none {
                it.id == id && it.resumeCount >= 1 && it.status == AgentThreadStatus.FAILED
            }) {
                delay(50)
            }
        }
        val thread = manager.list("conv-1").first { it.id == id }
        assertEquals("FATAL 错误也必须自动续跑 1 次（全拆黑名单）", 1, thread.resumeCount)
        assertEquals("续跑也失败后停在 FAILED", AgentThreadStatus.FAILED, thread.status)
    }

    @Test
    fun `自动续跑次数设为 1 时只续一次`() = runBlocking {
        val manager = AgentThreadManager(
            InMemoryAgentThreadRepository(),
            RecoverableBackend(),
            scope,
            maxConcurrent = 4,
            autoResumeMax = { 1 },
        )
        val id = field(exec(manager, "spawn_agent", """{"task":"必定报可恢复错误"}"""), "thread_id")!!
        withTimeout(30_000) {
            while (manager.list("conv-1").none { it.id == id && it.resumeCount >= 1 }) {
                delay(50)
            }
        }
        // 再等两个退避窗口，确认不会续第二次
        delay(12_000)
        assertEquals(
            "上限是 1 就只能续一次",
            1,
            manager.list("conv-1").first { it.id == id }.resumeCount,
        )
    }

    @Test
    fun `运行中的线程也能直接换模型接着跑`() = runBlocking {
        val backend = SlowBackend()
        val manager = managerWith(backend)
        val id = field(exec(manager, "spawn_agent", """{"task":"跑很久的活"}"""), "thread_id")!!
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == id && it.status == AgentThreadStatus.RUNNING }) {
                delay(20)
            }
        }
        val newModel = "model-b"
        val revived = withTimeout(40_000) { manager.switchModelAndResume(id, newModel) }
        assertTrue("换模型续跑必须真的启动了", revived != null)
        assertEquals(
            "模型必须被钉成新指定的那个",
            newModel,
            manager.list("conv-1").first { it.id == id }.modelId,
        )
        withTimeout(15_000) {
            while (backend.runs < 2) delay(20)
        }
        assertTrue("新模型必须真的又跑了一轮", backend.runs >= 2)
        assertTrue("第二轮必须是续跑而不是从头来", backend.lastResume)
        manager.stop(id)
    }

    @Test
    fun `门禁：两个新设置项必须五环节齐全`() {
        val store = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        listOf(
            "val AGENT_AUTO_RESUME_MAX = intPreferencesKey(\"agent_auto_resume_max\")",
            "agentAutoResumeMax = (preferences[AGENT_AUTO_RESUME_MAX] ?: 2).coerceIn(0, 10)",
            "preferences[AGENT_AUTO_RESUME_MAX] = settings.agentAutoResumeMax.coerceIn(0, 10)",
            "val agentAutoResumeMax: Int = 2",
            "val AGENT_MAX_STEPS = intPreferencesKey(\"agent_max_steps_readonly\")",
            "agentMaxSteps = (preferences[AGENT_MAX_STEPS] ?: 32).coerceIn(16, 96)",
            "preferences[AGENT_MAX_STEPS] = settings.agentMaxSteps.coerceIn(16, 96)",
            "val agentMaxSteps: Int = 32",
        ).forEach { needle ->
            assertTrue("PreferencesStore 缺少：$needle", store.contains(needle))
        }
        val ui = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue("设置界面必须有「自动续跑次数」调节器", ui.contains("agent_tools_resume_max_title"))
        assertTrue("设置界面必须有「单趟步数」调节器", ui.contains("agent_tools_max_steps_title"))
        assertTrue("续跑次数必须能改", ui.contains("settings.copy(agentAutoResumeMax ="))
        assertTrue("步数必须能改", ui.contains("settings.copy(agentMaxSteps ="))
    }

    @Test
    fun `门禁：中途换模型必须接线到界面与工具`() {
        val manager = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt")
        assertTrue(
            "必须有中途换模型的入口",
            manager.contains("suspend fun switchModelAndResume("),
        )
        assertTrue(
            "换模型前必须先把当前那次生成停下来",
            manager.contains("emitEvent(\n                threadId,\n                \"MODEL_SWITCH_PENDING\","),
        )
        assertTrue(
            "换模型是用户明确意图，必须清掉停止闩锁",
            manager.contains("stopLatched.remove(threadId)\n            stopRequested.remove(threadId)"),
        )
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt")
        assertTrue(
            "详情页必须走 switchModelAndResume（否则运行中换不了）",
            page.contains("manager.switchModelAndResume(threadId, model.id.toString())"),
        )
        val tools = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt")
        assertTrue("resume_agent 必须支持 model_id", tools.contains("put(\"model_id\", buildJsonObject {"))
        assertTrue(
            "带 model_id 时不许因为「线程还在跑」被拒",
            tools.contains("!switching && !target.canResume ->"),
        )
    }

    @Test
    fun `门禁：续跑必须把前后内容接起来`() {
        val backend = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        assertTrue(
            "续跑时必须留住中断前已经写出来的内容",
            backend.contains("val carriedText = if (resume) {"),
        )
        assertTrue(
            "本次运行内的多条产出也要全部算上，不能只取最后一条",
            backend.contains("val freshText = latestMessages"),
        )
        assertTrue(
            "v251：合并走语义化的 mergeResumeText（去重 + 去掉半截 JSON 残片）",
            backend.contains("val rawFinalText = mergeResumeText(carriedText, freshText)"),
        )
        assertTrue(
            "步数上限必须改成可调",
            backend.contains("private fun agentMaxSteps(\n        thread: AgentThread,\n        settings:"),
        )
    }

    /** v241：每次都报可恢复错误，用来测「自动续跑次数」这个上限 */
    private class RecoverableBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            delay(20)
            throw IllegalStateException("429 Too Many Requests: rate limit exceeded, please retry")
        }
    }

    /** v241：一直跑不停，用来测「运行中直接换模型」 */
    private class SlowBackend : AgentBackend {
        @Volatile
        var runs: Int = 0

        @Volatile
        var lastResume: Boolean = false

        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            runs++
            lastResume = resume
            onMessage(
                AgentMessage(
                    threadId = thread.id,
                    role = "assistant",
                    content = "第 $runs 轮开始（模型=${thread.modelId}）",
                )
            )
            delay(120_000)
            return AgentRunOutcome(report = AgentReport(conclusion = "不该走到这里"))
        }
    }

    private class FatalBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            delay(20)
            throw IllegalStateException("401 Unauthorized: incorrect api key provided")
        }
    }
}
