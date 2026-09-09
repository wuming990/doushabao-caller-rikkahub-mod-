package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.FakeAgentBackend
import me.rerere.rikkahub.agent.tools.AGENT_FULL_CONTEXT_UNAVAILABLE
import me.rerere.rikkahub.agent.tools.createAgentControlTools
import me.rerere.rikkahub.service.AgentContextFeed
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v239 门禁：把「完整主对话上文」真正下传给子代理。
 *
 * 用户原话：
 * > 「我不是让你像圆桌模式那样给全部上文么，打回去重做，
 * >  可以自己选择给不给全部上文，但不能做不到给不了全部上文。」
 *
 * 在 v238 之前 `context_summary` 只能由主模型手打转述 —— 它没有任何工具能导出
 * 自己的对话原文，所以「完整上文」在能力上根本不成立。这个测试守住三件事：
 * 1. 渲染器把原文照抄，但不把 base64 图片、几万字工具输出、思考过程一起塞进去；
 * 2. `spawn_agent` / `run_agent_pipeline` 真的有开关，开了就真的带上原文；
 * 3. 拿不到原文时**明确报错**，不悄悄降级成「没有上文」。
 */
class AgentFullContextFeedTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- 渲染器 ----

    private fun userText(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantParts(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    @Test
    fun `渲染器照抄原文并标出每条消息的角色`() {
        val rendered = AgentContextFeed.render(
            messages = listOf(
                userText("帮我修圆桌的停止按钮"),
                assistantParts(UIMessagePart.Text("已定位到 supervisorScope 等待子协程的问题")),
            )
        )
        assertTrue("必须有可辨认的开头", rendered.startsWith(AgentContextFeed.HEADER))
        assertTrue("必须标出用户", rendered.contains("· 用户 ---"))
        assertTrue("必须标出主代理", rendered.contains("· 主代理 ---"))
        assertTrue("用户原话必须逐字在内", rendered.contains("帮我修圆桌的停止按钮"))
        assertTrue("助手原话必须逐字在内", rendered.contains("supervisorScope"))
        assertTrue("必须写明共几条", rendered.contains("共 2 条消息"))
    }

    @Test
    fun `读法守则必须写进去`() {
        val rendered = AgentContextFeed.render(messages = listOf(userText("随便")))
        assertTrue("必须强调任务书优先", rendered.contains("任务书优先"))
        assertTrue("必须提醒有被推翻的旧结论", rendered.contains("推翻"))
    }

    @Test
    fun `base64 图片只留占位不进正文`() {
        val base64 = "data:image/png;base64," + "A".repeat(5_000)
        val rendered = AgentContextFeed.render(
            messages = listOf(assistantParts(UIMessagePart.Image(url = base64)))
        )
        assertFalse("base64 绝不能进上下文", rendered.contains("AAAAAAAAAA"))
        assertTrue("要留占位说明", rendered.contains("图片附件"))
    }

    @Test
    fun `工具调用只留摘要且超长会被截断`() {
        val hugeOutput = "结果行".repeat(5_000)
        val rendered = AgentContextFeed.render(
            messages = listOf(
                assistantParts(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "workspace_shell",
                        input = """{"command":"ls"}""",
                        output = listOf(UIMessagePart.Text(hugeOutput)),
                    )
                )
            )
        )
        assertTrue("要写出工具名", rendered.contains("workspace_shell"))
        assertTrue("要带入参", rendered.contains("ls"))
        assertTrue("超长结果要注明省了多少", rendered.contains("另有"))
        assertTrue(
            "单条工具输出不该把整段几万字带进来（实际 ${rendered.length} 字）",
            rendered.length < hugeOutput.length / 2,
        )
    }

    @Test
    fun `思考过程默认不下传开关打开才下传`() {
        val message = assistantParts(UIMessagePart.Reasoning(reasoning = "先看 RoundTableCoordinator"))
        val without = AgentContextFeed.render(messages = listOf(message))
        assertFalse("默认不带思考正文", without.contains("先看 RoundTableCoordinator"))
        assertTrue("但要告诉子代理省掉了", without.contains("未下传"))

        val with = AgentContextFeed.render(messages = listOf(message), includeReasoning = true)
        assertTrue("打开开关就要带上", with.contains("先看 RoundTableCoordinator"))
    }

    @Test
    fun `已派出的子代理结论会一起带上`() {
        val rendered = AgentContextFeed.render(
            messages = listOf(userText("继续")),
            threads = listOf(
                AgentContextFeed.ThreadDigest(
                    id = "t-1",
                    role = "EXPLORER",
                    status = "SUCCEEDED",
                    activeModelId = "model-x",
                    taskPreview = "查停止按钮为什么没反应",
                    conclusion = "根因是 supervisorScope 会等被取消的子协程",
                )
            ),
        )
        assertTrue(rendered.contains(AgentContextFeed.THREADS_HEADER))
        assertTrue(rendered.contains("EXPLORER"))
        assertTrue(rendered.contains("SUCCEEDED"))
        assertTrue(rendered.contains("model-x"))
        assertTrue(rendered.contains("查停止按钮为什么没反应"))
        assertTrue(rendered.contains("根因是 supervisorScope"))
    }

    @Test
    fun `超长时掐中间保头尾并写明省了多少`() {
        val head = "开头的任务目标必须留住"
        val tail = "结尾的最新指令必须留住"
        val messages = buildList {
            add(userText(head))
            repeat(400) { add(userText("中间第 $it 段" + "填充".repeat(50))) }
            add(userText(tail))
        }
        val limit = 6_000
        val rendered = AgentContextFeed.render(messages = messages, maxChars = limit)
        assertTrue("总长不得超过上限（实际 ${rendered.length}）", rendered.length <= limit)
        assertTrue("开头要留住", rendered.contains(head))
        assertTrue("结尾要留住", rendered.contains(tail))
        assertTrue("必须明写省略", rendered.contains(AgentContextFeed.OMISSION_PREFIX))
    }

    @Test
    fun `上限会被夹进安全区间`() {
        val messages = List(200) { userText("第 $it 条" + "字".repeat(200)) }
        val tiny = AgentContextFeed.render(messages = messages, maxChars = 10)
        assertTrue(
            "过小的上限要被抬到下限附近，不能渲染出一个空壳（实际 ${tiny.length}）",
            tiny.length >= 1_000,
        )
    }

    // ---- 工具层 ----

    private fun newManager(): AgentThreadManager =
        AgentThreadManager(
            InMemoryAgentThreadRepository(),
            FakeAgentBackend(delayMillis = 1, messageCount = 1),
            scope,
            maxConcurrent = 4,
        )

    private fun exec(
        toolName: String,
        manager: AgentThreadManager,
        args: String,
        provider: (suspend (Boolean, Int) -> String?)? = null,
    ): String {
        val tools = createAgentControlTools(
            conversationId = "conv-1",
            manager = manager,
            workspaceId = null,
            fullContextProvider = provider,
        )
        val tool = tools.first { it.name == toolName }
        return runBlocking { tool.execute(Json.parseToJsonElement(args)) }
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
    }

    private fun textOf(json: String, key: String): String? =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun `spawn_agent 暴露了完整上文的三个参数`() {
        val tools = createAgentControlTools("conv-1", newManager())
        val schema = tools.first { it.name == "spawn_agent" }.parameters().toString()
        assertTrue("要有开关", schema.contains("include_full_context"))
        assertTrue("要能限长", schema.contains("full_context_max_chars"))
        assertTrue("要能选择带不带思考", schema.contains("include_reasoning"))
    }

    @Test
    fun `run_agent_pipeline 也暴露了完整上文参数`() {
        val tools = createAgentControlTools("conv-1", newManager())
        val schema = tools.first { it.name == "run_agent_pipeline" }.parameters().toString()
        assertTrue(schema.contains("include_full_context"))
        assertTrue(schema.contains("context_summary"))
    }

    @Test
    fun `打开开关后线程真的拿到了完整上文`() = runBlocking {
        val manager = newManager()
        val full = AgentContextFeed.render(messages = listOf(userText("这是主对话原文")))
        val out = exec(
            "spawn_agent",
            manager,
            """{"task":"读一下背景","include_full_context":true}""",
            provider = { _, _ -> full },
        )
        val id = textOf(out, "thread_id")
        assertTrue("应该派发成功：$out", id != null)
        val thread = withTimeout(5_000) {
            manager.threadsFlow("conv-1").first { list -> list.any { it.id == id } }
                .first { it.id == id }
        }
        val summary = thread.contextSummary.orEmpty()
        assertTrue("必须带上原文开头标记", summary.contains(AgentContextFeed.HEADER))
        assertTrue("必须逐字带上原文", summary.contains("这是主对话原文"))
    }

    @Test
    fun `不打开开关时行为与以前完全一致`() = runBlocking {
        val manager = newManager()
        val out = exec(
            "spawn_agent",
            manager,
            """{"task":"照旧","context_summary":"只给一句话"}""",
            provider = { _, _ -> "不该被用到" },
        )
        val id = textOf(out, "thread_id")
        val thread = withTimeout(5_000) {
            manager.threadsFlow("conv-1").first { list -> list.any { it.id == id } }
                .first { it.id == id }
        }
        val summary = thread.contextSummary.orEmpty()
        assertTrue(summary.contains("只给一句话"))
        assertFalse("没开开关就不许自己带原文", summary.contains(AgentContextFeed.HEADER))
    }

    @Test
    fun `手写摘要与原文可以同时给且手写排在前面`() = runBlocking {
        val manager = newManager()
        val full = AgentContextFeed.render(messages = listOf(userText("原文正文")))
        val out = exec(
            "spawn_agent",
            manager,
            """{"task":"两个都要","context_summary":"划重点：只看第 3 条","include_full_context":true}""",
            provider = { _, _ -> full },
        )
        val id = textOf(out, "thread_id")
        val thread = withTimeout(5_000) {
            manager.threadsFlow("conv-1").first { list -> list.any { it.id == id } }
                .first { it.id == id }
        }
        val summary = thread.contextSummary.orEmpty()
        assertTrue(summary.contains("划重点：只看第 3 条"))
        assertTrue(summary.contains("原文正文"))
        assertTrue(
            "手写的划重点应排在原文之前",
            summary.indexOf("划重点") < summary.indexOf(AgentContextFeed.HEADER),
        )
    }

    @Test
    fun `拿不到原文时必须明确报错而不是悄悄降级`() {
        val out = exec(
            "spawn_agent",
            newManager(),
            """{"task":"要原文","include_full_context":true}""",
            provider = null,
        )
        val error = textOf(out, "error").orEmpty()
        assertTrue("必须回报错：$out", error.contains(AGENT_FULL_CONTEXT_UNAVAILABLE))
        assertTrue("不该返回 thread_id：$out", textOf(out, "thread_id") == null)
    }

    @Test
    fun `原文为空同样报错`() {
        val out = exec(
            "spawn_agent",
            newManager(),
            """{"task":"要原文","include_full_context":true}""",
            provider = { _, _ -> "   " },
        )
        assertTrue(textOf(out, "error").orEmpty().contains(AGENT_FULL_CONTEXT_UNAVAILABLE))
    }

    @Test
    fun `参数会被透传给渲染钩子`() {
        var seenReasoning: Boolean? = null
        var seenMax: Int? = null
        exec(
            "spawn_agent",
            newManager(),
            """{"task":"看参数","include_full_context":true,"include_reasoning":true,"full_context_max_chars":4321}""",
            provider = { reasoning, max ->
                seenReasoning = reasoning
                seenMax = max
                "原文"
            },
        )
        assertTrue("include_reasoning 要传下去", seenReasoning == true)
        assertTrue("上限要传下去，实际 $seenMax", seenMax == 4321)
    }

    @Test
    fun `流水线每一棒都拿到同一份上文`() = runBlocking {
        val manager = newManager()
        val full = AgentContextFeed.render(messages = listOf(userText("流水线背景")))
        exec(
            "run_agent_pipeline",
            manager,
            """{"stages":[{"task":"第一棒","role":"explorer"},{"task":"第二棒","role":"explorer"}],
                "include_full_context":true,"stage_timeout_seconds":30}""",
            provider = { _, _ -> full },
        )
        val threads = withTimeout(20_000) {
            manager.threadsFlow("conv-1").first { list ->
                list.size >= 2 && list.all { it.status == AgentThreadStatus.SUCCEEDED }
            }
        }
        assertTrue("两棒都要跑起来，实际 ${threads.size}", threads.size >= 2)
        threads.forEach { thread ->
            assertTrue(
                "每一棒都该拿到原文：${thread.task}",
                thread.contextSummary.orEmpty().contains("流水线背景"),
            )
        }
    }

    // ---- 接线（源码断言，防止以后被重构掉）----

    private fun source(relative: String): String {
        val moduleDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
    }

    @Test
    fun `ChatService 必须把主对话原文的取用钩子接上`() {
        val text = source("service/ChatService.kt")
        assertTrue(
            "createAgentControlTools 必须传 fullContextProvider，否则「完整上文」永远拿不到",
            text.contains("fullContextProvider = { includeReasoning, maxChars ->"),
        )
        assertTrue(
            "必须有真正组装原文的方法",
            text.contains("private suspend fun buildAgentFullContext("),
        )
        assertTrue(
            "必须每次重新读最新对话，而不是复用装配时的快照",
            text.contains("val latest = getConversationFlow(conversationId).value"),
        )
        assertTrue(
            "已派出的子代理结论也要一起带上",
            text.contains("AgentContextFeed.ThreadDigest("),
        )
    }

    @Test
    fun `子代理提示词必须教它怎么读这段上文`() {
        val text = source("agent/runtime/GenerationAgentBackend.kt")
        assertTrue("要强调任务书优先", text.contains("任务书优先"))
        assertTrue("要提醒别照抄被推翻的旧结论", text.contains("已经被推翻的旧结论"))
    }

    @Test
    fun `详情页不许把几万字上文整段渲染`() {
        val text = source("ui/pages/chat/AgentThreadPage.kt")
        assertTrue("要有折叠阈值", text.contains("CONTEXT_PREVIEW_CHARS"))
        assertTrue("要给展开入口", text.contains("展开全部上下文"))
        assertFalse(
            "不许再无脑整段渲染 contextSummary",
            text.contains("text = \"上下文摘要：\${thread?.contextSummary}\""),
        )
    }

    @Test
    fun `隔离红线仍然成立`() {
        val text = source("agent/tools/AgentControlTools.kt")
        assertFalse(
            "agent 包不许为了拿上文去引用主会话组件",
            text.lineSequence().any {
                it.trimStart().startsWith("import") &&
                    (it.contains("ChatService") ||
                        it.contains("ConversationSession") ||
                        it.contains("me.rerere.rikkahub.service"))
            },
        )
    }
}
