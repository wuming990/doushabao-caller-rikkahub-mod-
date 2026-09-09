package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentPipelineStage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.model.AgentVerdict
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
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
 * v236 门禁：流水线（探测 → 编程 → 一审，不通过就交回主模型）。
 *
 * ## 这一条是用户提出的核心需求，原话逐字保留
 *
 * > 「换成对话主模型负责发布任务和最后审查，一个或者多个负责探测（子代理），
 * >  一个负责编程（固定位置子代理），一个负责一审和改错（子代理），
 * >  如果不通过，进入主代理接手后续工作。省额度的同时还可以让主模型上下文干净。」
 *
 * v235 只有手动工具，每一棒都得主模型自己派、自己等、自己把上一棒的报告转述给下一棒；
 * 中转本身就在烧主模型上下文，「上下文干净」其实没达成。这里把整条链交给管理器：
 * 阶段之间在数据库里直接交接，主模型只在结束或卡住时接手。
 */
class AgentPipelineTest {

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

    private fun manager(backend: AgentBackend, repo: InMemoryAgentThreadRepository) =
        AgentThreadManager(repo, backend, scope, maxConcurrent = 4)

    private fun stages(reviewerGate: Boolean = true) = listOf(
        AgentPipelineStage(task = "探测：找出拼写错误在哪几处", role = AgentRole.EXPLORER),
        AgentPipelineStage(
            task = "编程：把拼写改对",
            role = AgentRole.PROGRAMMER,
            writablePaths = listOf("app/src/main/Foo.kt"),
        ),
        AgentPipelineStage(task = "一审：检查改动是否正确", role = AgentRole.REVIEWER, gate = reviewerGate),
    )

    // ------------------------------------------------------------ 审查结论归一化

    @Test
    fun `审查结论必须能识别常见写法`() {
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("pass"))
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("PASS"))
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("通过"))
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("检查完毕，合格"))
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("fail"))
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("不通过"))
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("需修改"))
    }

    @Test
    fun `不通过的判定必须优先于通过`() {
        // 「not pass」「未通过」里都含 pass / 通过，反过来判会误判成通过 —— 那是最危险的方向
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("not pass"))
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("未通过"))
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("not ok"))
        // 含糊的「没有问题」会落到保守一侧（判 FAIL），这是刻意接受的偏差：
        // 宁可多喊主模型看一眼，也不要把没审明白的东西放过去
        assertEquals(AgentVerdict.FAIL, AgentVerdict.parse("没有问题"))
    }

    @Test
    fun `干净地写 pass 必须被认成通过`() {
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("pass"))
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse(" PASS "))
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("passed"))
        assertEquals(AgentVerdict.PASS, AgentVerdict.parse("通过"))
    }

    @Test
    fun `没表态一律算未定而不是默认通过`() {
        assertEquals(AgentVerdict.UNSET, AgentVerdict.parse(null))
        assertEquals(AgentVerdict.UNSET, AgentVerdict.parse(""))
        assertEquals(AgentVerdict.UNSET, AgentVerdict.parse("   "))
        assertEquals(AgentVerdict.UNSET, AgentVerdict.parse("我看了一遍，写了些发现"))
    }

    @Test
    fun `verdict 字段必须能被报告编解码带上`() {
        val encoded = AgentReport.encode(AgentReport(conclusion = "审完了", verdict = "fail"))
        assertEquals("fail", AgentReport.decode(encoded)?.verdict)
        // 老报告没有这个字段，必须仍然能解析（向后兼容，不需要数据迁移）
        assertEquals("", AgentReport.decode("{\"conclusion\":\"老报告\"}")?.verdict)
    }

    // ------------------------------------------------------------ 流水线行为

    @Test
    fun `三棒全过时流水线跑完并按顺序记录每一棒`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = manager(ScriptedBackend(reviewerVerdict = "pass"), repo)

        val outcome = mgr.runPipeline(
            conversationId = "conv-1",
            workspaceId = "ws-1",
            stages = stages(),
            stageTimeoutMillis = 20_000,
        )

        assertTrue("全过时必须是 COMPLETED：${outcome.detail}", outcome.completed)
        assertEquals("COMPLETED", outcome.reason)
        assertEquals(3, outcome.threads.size)
        assertEquals(3, outcome.stoppedAtIndex)
        assertEquals(
            listOf(AgentRole.EXPLORER, AgentRole.PROGRAMMER, AgentRole.REVIEWER),
            outcome.threads.map { it.role },
        )
        assertTrue("每一棒都必须成功", outcome.threads.all { it.status == AgentThreadStatus.SUCCEEDED })
    }

    @Test
    fun `审查不通过时必须停在审查那一棒并交回主模型`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = manager(ScriptedBackend(reviewerVerdict = "fail"), repo)

        val outcome = mgr.runPipeline(
            conversationId = "conv-1",
            workspaceId = "ws-1",
            stages = stages(),
            stageTimeoutMillis = 20_000,
        )

        assertFalse(outcome.completed)
        assertEquals("GATE_FAILED", outcome.reason)
        assertEquals("必须停在第 3 棒（下标 2）", 2, outcome.stoppedAtIndex)
        assertEquals("三棒都跑过了，只是最后一棒判不通过", 3, outcome.threads.size)
        assertTrue("必须说明是交回主模型", outcome.detail.contains("交回主模型"))
    }

    @Test
    fun `审查位没明确表态也算不通过`() = runBlocking {
        // 保守设计：宁可多问主模型一次，也不要把「其实没审」当成审过了
        val repo = InMemoryAgentThreadRepository()
        val mgr = manager(ScriptedBackend(reviewerVerdict = ""), repo)

        val outcome = mgr.runPipeline(
            conversationId = "conv-1",
            workspaceId = "ws-1",
            stages = stages(),
            stageTimeoutMillis = 20_000,
        )

        assertEquals("GATE_FAILED", outcome.reason)
        assertTrue(outcome.detail.contains("未明确表态"))
    }

    @Test
    fun `某一棒失败时立刻停下不再往下派活`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        // 编程位（第 2 棒）直接抛致命错误
        val mgr = manager(ScriptedBackend(failRole = AgentRole.PROGRAMMER), repo)

        val outcome = mgr.runPipeline(
            conversationId = "conv-1",
            workspaceId = "ws-1",
            stages = stages(),
            stageTimeoutMillis = 20_000,
        )

        assertEquals("STAGE_FAILED", outcome.reason)
        assertEquals("必须停在第 2 棒（下标 1）", 1, outcome.stoppedAtIndex)
        assertEquals("第 3 棒绝不该被派出去", 2, outcome.threads.size)
        assertEquals(
            "库里也不该出现第 3 棒的线程记录",
            2,
            repo.threadsFlow("conv-1").first().size,
        )
    }

    @Test
    fun `上一棒的结论会作为上下文交给下一棒`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(reviewerVerdict = "pass")
        val mgr = manager(backend, repo)

        mgr.runPipeline(
            conversationId = "conv-1",
            workspaceId = "ws-1",
            stages = stages(),
            stageTimeoutMillis = 20_000,
        )

        // 第一棒没有上文；第二、三棒必须收到上一棒的结论
        assertEquals(3, backend.seenContexts.size)
        assertTrue("第一棒不该有上文", backend.seenContexts[0].isNullOrBlank())
        assertNotNull("第二棒必须收到上一棒结论", backend.seenContexts[1])
        assertTrue(backend.seenContexts[1]!!.contains("上一棒"))
        assertTrue(
            "必须把上一棒的证据也带过去",
            backend.seenContexts[1]!!.contains("可核对证据"),
        )
        assertNotNull("第三棒必须收到上一棒结论", backend.seenContexts[2])
    }

    @Test
    fun `只有被点名的那一棒才拿到写权限`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(reviewerVerdict = "pass")
        val mgr = manager(backend, repo)

        val outcome = mgr.runPipeline(
            conversationId = "conv-1",
            workspaceId = "ws-1",
            stages = stages(),
            stageTimeoutMillis = 20_000,
        )

        assertFalse("探测位必须只读", outcome.threads[0].canWrite)
        assertTrue("编程位必须可写", outcome.threads[1].canWrite)
        assertEquals(listOf("app/src/main/Foo.kt"), outcome.threads[1].writablePaths)
        assertFalse("审查位必须只读", outcome.threads[2].canWrite)
    }

    // ------------------------------------------------------------ 工具层

    @Test
    fun `run_agent_pipeline 必须被注册且缺少阶段时明确报错`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = manager(ScriptedBackend(reviewerVerdict = "pass"), repo)
        val tool = createAgentControlTools("conv-1", mgr, "ws-1").first { it.name == "run_agent_pipeline" }

        val out = tool.execute(Json.parseToJsonElement("""{"stages":[]}"""))
            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        assertTrue(out.contains("stages is required"))
    }

    @Test
    fun `run_agent_pipeline 返回体必须让主模型看清停在哪一棒`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = manager(ScriptedBackend(reviewerVerdict = "fail"), repo)
        val tool = createAgentControlTools("conv-1", mgr, "ws-1").first { it.name == "run_agent_pipeline" }

        val args = """
            {"stages":[
              {"task":"探测一下","role":"explorer"},
              {"task":"改一下","role":"programmer","writable_paths":["app/src/main/Foo.kt"]},
              {"task":"审一下","role":"reviewer","gate":true}
            ],"stage_timeout_seconds":30}
        """.trimIndent()
        val out = tool.execute(Json.parseToJsonElement(args))
            .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        val json = Json.parseToJsonElement(out).jsonObject

        assertEquals("false", json["completed"]?.jsonPrimitive?.contentOrNull)
        assertEquals("GATE_FAILED", json["reason"]?.jsonPrimitive?.contentOrNull)
        assertEquals("3", json["stopped_at_stage"]?.jsonPrimitive?.contentOrNull)
        val stageList = json["stages"]!!.jsonArray
        assertEquals(3, stageList.size)
        val reviewer = stageList[2].jsonObject
        assertEquals("REVIEWER", reviewer["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("FAIL", reviewer["verdict_normalized"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            "必须告诉主模型接下来该干什么",
            json["next_step_hint"]?.jsonPrimitive?.contentOrNull?.contains("take over") == true,
        )
    }

    // ------------------------------------------------------------ 源码门禁

    @Test
    fun `流水线绝不能给子代理编译或执行能力`() {
        val control = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt")
        val pipeline = control.substringAfter("name = \"run_agent_pipeline\"", "")
        assertTrue("找不到 run_agent_pipeline", pipeline.isNotBlank())
        assertTrue(
            "工具描述必须写明子代理不编译、编译由主模型自己跑",
            pipeline.contains("never compile"),
        )
        listOf("workspace_shell", "gradlew", "assembleRelease", "executeCommand").forEach {
            assertFalse("流水线不得引入 $it", pipeline.contains(it))
        }
    }

    @Test
    fun `关卡不通过必须停下而不是继续往下走`() {
        val mgr = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt")
        assertTrue(
            "只有 PASS 才继续",
            mgr.contains("if (verdict != AgentVerdict.PASS)"),
        )
        assertTrue("必须返回 GATE_FAILED", mgr.contains("reason = \"GATE_FAILED\""))
        assertTrue(
            "每一棒之间转交的上下文必须有长度上限",
            mgr.contains("AGENT_PIPELINE_CARRY_MAX_CHARS"),
        )
    }

    // ------------------------------------------------------------ 假后端

    /**
     * 按角色返回固定结果的假后端。
     * - 记录每一棒收到的 contextSummary，用于验证阶段之间真的在交接；
     * - reviewer 的 verdict 可配置，用于验证关卡行为。
     */
    private class ScriptedBackend(
        private val reviewerVerdict: String = "pass",
        private val failRole: AgentRole? = null,
    ) : AgentBackend {

        val seenContexts = mutableListOf<String?>()

        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            synchronized(seenContexts) { seenContexts.add(thread.contextSummary) }
            delay(20)
            if (thread.role == failRole) {
                // 用致命错误，避免自动重试干扰阶段判定
                throw IllegalStateException("401 Unauthorized: incorrect api key provided")
            }
            onMessage(
                AgentMessage(threadId = thread.id, role = "assistant", content = "${thread.role} 干完了")
            )
            return AgentRunOutcome(
                report = AgentReport(
                    conclusion = "${thread.role.name.lowercase()} 的结论",
                    evidence = listOf("app/src/main/Foo.kt:12 —— 原文片段"),
                    verdict = if (thread.role == AgentRole.REVIEWER) reviewerVerdict else "",
                ),
                finishReason = "stop",
                truncated = false,
                modelId = "model-for-${thread.role.name.lowercase()}",
            )
        }
    }
}
