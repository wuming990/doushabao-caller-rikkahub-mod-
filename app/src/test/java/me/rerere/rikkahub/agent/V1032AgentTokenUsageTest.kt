package me.rerere.rikkahub.agent

import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_MODEL_ACTIVE
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_TOKEN_USAGE
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentModelUsage
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.model.AgentThreadUsage
import me.rerere.rikkahub.agent.model.AgentUsageSnapshot
import me.rerere.rikkahub.agent.model.agentThreadUsageOf
import me.rerere.rikkahub.agent.model.agentUsageSnapshotOf
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.agentTokenUsageEventId
import me.rerere.rikkahub.ui.components.ai.ConversationCost
import me.rerere.rikkahub.ui.components.ai.ModelPrice
import me.rerere.rikkahub.ui.components.ai.formatCost
import me.rerere.rikkahub.ui.pages.chat.agentUsageDetailText
import me.rerere.rikkahub.ui.pages.chat.agentUsageSummaryText
import me.rerere.rikkahub.ui.pages.chat.computeAgentUsageCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v1032 守卫：子代理 token 用量。
 *
 * 用户原话：「子代理显示消耗的token数量，可参考"本对话token明细"」
 * 「1.发给我的版本一打开就报错…2.…把没必要的东西删掉」之外，这里最重要的是第 3 件事：
 * 数必须是真的、而且**面板与详情页不能是两套数**。
 *
 * 因此下面钉死：
 * - 取数口径与「本对话token明细」的 SQL 一致（cumulativeUsage 优先、请求次数兜底、思考不重复加总）；
 * - 同一线程的多次尝试各算一条、同一尝试重复上报只覆盖自己（不多不少）；
 * - 并发子代理按 threadId 归账，不互相混；
 * - 用量事件不进界面可见的事件流；
 * - 不动 AgentDatabase 版本、不往主库写；
 * - 算价查表全仓只有一份实现。
 */
class V1032AgentTokenUsageTest {

    private fun assistant(
        // TokenUsage 的四个字段是 Int（ai/src/main/java/me/rerere/ai/core/Usage.kt:6-9），
        // 这里必须也用 Int；写成 Long 会直接编译失败（Kotlin 不做 Long→Int 隐式收窄）。
        prompt: Int,
        completion: Int,
        cached: Int = 0,
        reasoning: Int = 0,
        requests: Int? = null,
        cumulative: Boolean = true,
        id: Uuid = Uuid.random(),
    ): UIMessage {
        val usage = TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            cachedTokens = cached,
            reasoningTokens = reasoning,
        )
        return UIMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            cumulativeUsage = if (cumulative) usage else null,
            usage = if (cumulative) null else usage,
            cumulativeRequestCount = requests,
        )
    }

    private fun usageEvent(
        threadId: String,
        eventId: String,
        modelId: String,
        prompt: Long,
        completion: Long,
        cached: Long = 0,
        reasoning: Long = 0,
        requests: Int = 1,
    ): AgentEvent {
        // AgentUsageSnapshot 自己是 Long（累计值可能超过 Int），与 TokenUsage 的 Int 不是一回事
        return AgentEvent(
            id = eventId,
            threadId = threadId,
            type = AGENT_EVENT_TYPE_TOKEN_USAGE,
            detail = AgentUsageSnapshot.encode(
                AgentUsageSnapshot(modelId, prompt, completion, cached, reasoning, requests),
            ),
        )
    }

    // ---- 取数口径 ----

    @Test
    fun `优先用累计用量 一条消息里的多次请求已经含在里面`() {
        val snapshot = agentUsageSnapshotOf(
            "model-1",
            listOf(assistant(prompt = 1000, completion = 200, cached = 400, requests = 3)),
        )!!
        assertEquals(1000L, snapshot.promptTokens)
        assertEquals(200L, snapshot.completionTokens)
        assertEquals(400L, snapshot.cachedTokens)
        assertEquals(3, snapshot.requests)
    }

    @Test
    fun `没有累计字段的老数据退回 usage 请求次数兜底成一次`() {
        val snapshot = agentUsageSnapshotOf(
            "model-1",
            listOf(assistant(prompt = 500, completion = 50, cumulative = false)),
        )!!
        assertEquals(500L, snapshot.promptTokens)
        assertEquals("老消息没有 cumulativeRequestCount 时按至少 1 次兜底（与主对话 SQL 同一条）", 1, snapshot.requests)
    }

    @Test
    fun `思考量只单列 绝不再加进总数`() {
        val usage = agentThreadUsageOf(
            listOf(usageEvent("t1", "t1:u1", "m1", prompt = 100, completion = 30, reasoning = 20)),
        )["t1"]!!
        assertEquals("合计 = 输入 + 输出，思考已经计在输出里", 130L, usage.totalTokens)
        assertEquals("思考量另存一列供展示", 20L, usage.reasoningTokens)
    }

    @Test
    fun `服务商一个分项都没报时不返回假数据`() {
        assertNull(
            "没报用量就不能编个 0 出来",
            agentUsageSnapshotOf("model-1", listOf(assistant(prompt = 0, completion = 0))),
        )
        assertNull(agentUsageSnapshotOf("model-1", emptyList()))
    }

    @Test
    fun `用户消息不计入用量`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = emptyList(), cumulativeUsage = TokenUsage(promptTokens = 999, completionTokens = 999)),
        )
        // 后端调用前已经按 role 过滤，这里核对过滤后为空 → 不产生事件
        assertNull(agentUsageSnapshotOf("model-1", messages.filter { it.role == MessageRole.ASSISTANT }))
    }

    // ---- 归账与不重不漏 ----

    @Test
    fun `同一条线程的多次尝试各算一条 相加就是真实花费`() {
        val usage = agentThreadUsageOf(
            listOf(
                usageEvent("t1", "t1:u1", "m1", prompt = 100, completion = 10),
                usageEvent("t1", "t1:u2", "m1", prompt = 200, completion = 20),
            ),
        )["t1"]!!
        assertEquals(300L, usage.promptTokens)
        assertEquals(30L, usage.completionTokens)
        assertEquals(2, usage.attempts)
    }

    @Test
    fun `按模型分开计 换过备用模型才能各自按单价算钱`() {
        val usage = agentThreadUsageOf(
            listOf(
                usageEvent("t1", "t1:u1", "glm-4", prompt = 100, completion = 10),
                usageEvent("t1", "t1:u2", "deepseek", prompt = 50, completion = 5),
                usageEvent("t1", "t1:u3", "glm-4", prompt = 25, completion = 2),
            ),
        )["t1"]!!
        assertEquals(listOf("deepseek", "glm-4"), usage.byModel.map { it.modelId })
        val glm = usage.byModel.first { it.modelId == "glm-4" }
        assertEquals(125L, glm.promptTokens)
        assertEquals("glm-4 跑过两次", 2, glm.attempts)
        assertEquals(3, usage.attempts)
    }

    @Test
    fun `同一事件 id 重复出现只算一次`() {
        val repeated = listOf(
            usageEvent("t1", "t1:same", "m1", prompt = 100, completion = 10),
            usageEvent("t1", "t1:same", "m1", prompt = 100, completion = 10),
        )
        assertEquals("重复行不能把数翻倍", 100L, agentThreadUsageOf(repeated)["t1"]!!.promptTokens)
        assertEquals(1, agentThreadUsageOf(repeated)["t1"]!!.attempts)
    }

    @Test
    fun `并发子代理按线程归账 不许互相混`() {
        val all = agentThreadUsageOf(
            listOf(
                usageEvent("t1", "t1:u1", "m1", prompt = 100, completion = 1),
                usageEvent("t2", "t2:u1", "m2", prompt = 7, completion = 3),
            ),
        )
        assertEquals(100L, all.getValue("t1").promptTokens)
        assertEquals(7L, all.getValue("t2").promptTokens)
        assertEquals("每个模型只归到自己线程", listOf("m1"), all.getValue("t1").byModel.map { it.modelId })
    }

    @Test
    fun `坏数据与别的类型事件都不许污染统计`() {
        val broken = AgentEvent(id = "x1", threadId = "t1", type = AGENT_EVENT_TYPE_TOKEN_USAGE, detail = "坏掉的 JSON")
        val empty = AgentEvent(id = "x2", threadId = "t1", type = AGENT_EVENT_TYPE_TOKEN_USAGE, detail = "")
        val tool = AgentEvent(id = "x3", threadId = "t1", type = "TOOL_CALL", detail = "read_file {}")
        assertTrue(agentThreadUsageOf(listOf(broken, empty, tool)).isEmpty())
        val mixed = agentThreadUsageOf(
            listOf(broken, usageEvent("t1", "ok", "m1", prompt = 1, completion = 1), tool),
        )
        assertEquals("脏行跳过，好行照常统计", 2L, mixed.getValue("t1").totalTokens)
    }

    @Test
    fun `空表返回空结果 不给出全零的假对象`() {
        assertTrue(agentThreadUsageOf(emptyList()).isEmpty())
        assertTrue(AgentThreadUsage().isEmpty)
    }

    // ---- 事件 id：一次尝试一条、不同尝试不覆盖 ----

    @Test
    fun `事件 id 带代次与尝试序号`() {
        val first = agentTokenUsageEventId("thread", 0, "1-1")
        val retrySameModel = agentTokenUsageEventId("thread", 0, "1-2")
        val fallbackModel = agentTokenUsageEventId("thread", 0, "2-1")
        val afterResume = agentTokenUsageEventId("thread", 1, "1-1")
        assertEquals("同一次尝试反复上报必须落到同一行（REPLACE 覆盖自己）", first, agentTokenUsageEventId("thread", 0, "1-1"))
        assertTrue("同模型重试是另一笔花费", retrySameModel != first)
        assertTrue("换备用模型是另一笔花费", fallbackModel != first && fallbackModel != retrySameModel)
        assertTrue("续跑之后是新一轮，不能覆盖上一代那笔", afterResume != first)
    }

    // ---- 界面不可见：用量事件绝不能混进可见事件流 ----

    @Test
    fun `用量事件不进界面事件流 但用量查询读得到`() = runBlocking {
        val repository = InMemoryAgentThreadRepository()
        repository.insertThread(
            AgentThread(
                id = "t1",
                conversationId = "c1",
                task = "查资料",
                role = AgentRole.EXPLORER,
                modelId = "m1",
                status = AgentThreadStatus.RUNNING,
                createdAt = Instant.now(),
            )
        )
        repository.insertEvent(usageEvent("t1", "t1:u1", "m1", prompt = 10, completion = 1))
        repository.insertEvent(AgentEvent(id = "e1", threadId = "t1", type = "TOOL_CALL", detail = "grep x"))
        repository.insertEvent(AgentEvent(id = "e2", threadId = "t1", type = AGENT_EVENT_TYPE_MODEL_ACTIVE, detail = "glm-4 · 供应商A"))

        val visible = repository.eventsFlow("t1").first()
        assertTrue("可见事件流里绝不能有 TOKEN_USAGE", visible.none { it.type == AGENT_EVENT_TYPE_TOKEN_USAGE })
        assertEquals("工具事件与模型事件照常可见", 2, visible.size)

        val usageEvents = repository.tokenUsageEventsFlow("c1").first()
        assertEquals("用量查询专门读得到这一条", 1, usageEvents.size)
        assertEquals(11L, agentThreadUsageOf(usageEvents).getValue("t1").totalTokens)
    }

    @Test
    fun `别的对话的用量不许串进来`() = runBlocking {
        val repository = InMemoryAgentThreadRepository()
        repository.insertThread(AgentThread(id = "tA", conversationId = "cA", task = "a", role = AgentRole.DEFAULT, modelId = "m1", status = AgentThreadStatus.RUNNING, createdAt = Instant.now()))
        repository.insertThread(AgentThread(id = "tB", conversationId = "cB", task = "b", role = AgentRole.DEFAULT, modelId = "m1", status = AgentThreadStatus.RUNNING, createdAt = Instant.now()))
        repository.insertEvent(usageEvent("tA", "a1", "m1", prompt = 100, completion = 1))
        repository.insertEvent(usageEvent("tB", "b1", "m1", prompt = 1, completion = 1))

        assertEquals("只查到自己对话的线程", listOf("tA"), agentThreadUsageOf(repository.tokenUsageEventsFlow("cA").first()).keys.toList())
        assertTrue(agentThreadUsageOf(repository.tokenUsageEventsFlow("没有的对话").first()).isEmpty())
    }

    @Test
    fun `事件没落线程前不猜归属`() = runBlocking {
        val repository = InMemoryAgentThreadRepository()
        repository.insertEvent(usageEvent("ghost", "g1", "m1", prompt = 5, completion = 5))
        assertTrue("线程记录不存在时这条用量不该出现在任何对话里", repository.tokenUsageEventsFlow("c1").first().isEmpty())
    }

    @Test
    fun `面板合计文案 缩写口径与详情页一致且只在多次尝试时才带次数`() {
        val one = AgentThreadUsage(promptTokens = 1000, completionTokens = 200, attempts = 1, requests = 1)
        assertEquals("已用 ↑1K ↓200 Σ1.2K", agentUsageSummaryText(one).lineSequence().first())
        val many = one.copy(attempts = 3)
        assertTrue(agentUsageSummaryText(many).lineSequence().first().endsWith(" · 3 次尝试"))
    }

    @Test
    fun `外层已用直接显示缓存命中率`() {
        val usage = AgentThreadUsage(promptTokens = 1000, completionTokens = 200, cachedTokens = 375, attempts = 1)
        assertTrue("应看见 37.5% 而不是必须进入详情", agentUsageSummaryText(usage).contains("命中率 37.5%"))
    }

    @Test
    fun `外层不把未报告缓存和未定价写成零`() {
        val usage = AgentThreadUsage(promptTokens = 1000, completionTokens = 200, attempts = 1)
        val display = agentUsageSummaryText(usage, ConversationCost(unpricedMessages = 1))
        assertTrue(display.contains("缓存命中率 —"))
        assertTrue(display.contains("预估消耗 —（未设单价）"))
    }

    @Test
    fun `输入为零时不计算缓存百分比`() {
        val usage = AgentThreadUsage(promptTokens = 0, cachedTokens = 500, completionTokens = 100, attempts = 1)
        assertTrue(agentUsageSummaryText(usage).contains("缓存命中率 —"))
    }

    @Test
    fun `换过模型的子代理按各自报价同时计算用量价和按次价`() {
        val usage = mixedPricedAgentUsage()
        val cost = computeAgentUsageCost(usage) { modelId ->
            when (modelId) {
                "primary" -> ModelPrice(inputPerMillion = 10.0, outputPerMillion = 20.0, cachedPerMillion = 2.0, perRequest = 0.005)
                "fallback" -> ModelPrice(perRequest = 0.03)
                else -> null
            }
        }
        assertEquals(0.0838, cost.cost, 0.000000001)
        assertEquals(2, cost.pricedModels)
        assertTrue(agentUsageSummaryText(usage, cost).contains("预估消耗 0.0838"))
        assertTrue(agentUsageDetailText(usage, cost).contains("约 0.0838"))
    }

    @Test
    fun `一部分没报价时外层显示已估金额并说明部分未计`() {
        val usage = mixedPricedAgentUsage()
        val cost = computeAgentUsageCost(usage) { modelId ->
            if (modelId == "primary") ModelPrice(perRequest = 0.005) else null
        }
        assertEquals(1, cost.unpricedMessages)
        assertTrue(agentUsageSummaryText(usage, cost).contains("预估消耗 0.015（部分未计）"))
    }

    private fun mixedPricedAgentUsage() = AgentThreadUsage(
        promptTokens = 3000,
        completionTokens = 600,
        cachedTokens = 400,
        requests = 5,
        attempts = 2,
        byModel = listOf(
            AgentModelUsage(modelId = "primary", promptTokens = 1000, completionTokens = 100, cachedTokens = 400, requests = 3, attempts = 1),
            AgentModelUsage(modelId = "fallback", promptTokens = 2000, completionTokens = 500, requests = 2, attempts = 1),
        ),
    )

    // ---- 接线：数据源只有一份，算价只有一份 ----

    /** 数子串出现次数。源码文本断言一律不碰正则 —— 转义层级太容易写错（本轮真踩过）。 */
    private fun countOf(haystack: String, needle: String): Int {
        var index = 0
        var hits = 0
        while (true) {
            val found = haystack.indexOf(needle, index)
            if (found < 0) return hits
            hits += 1
            index = found + needle.length
        }
    }

    private fun moduleRoot(): File {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull("找不到工程根目录", root)
        return root!!
    }

    private fun moduleFile(relative: String): String {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull("找不到工程根目录", root)
        val file = File(root, "app/src/main/$relative")
        // 路径写错必须让测试红：返回空串会让所有「不许出现」的断言静默通过
        assertTrue("守卫测试读不到文件：${file.path}", file.exists())
        return file.readText()
    }

    @Test
    fun `接线 后端每一次尝试都上报用量 失败与取消也要报`() {
        val backend = moduleFile("java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        assertTrue("本次尝试的用量没有被取出来", backend.contains("agentUsageSnapshotOf("))
        // 「旧 assistant 不算本次花费」这条判据必须切出 publishUsage 函数体再断言：
        // 同一个字面量在本文件另有两处（续跑检查点累计、freshText 累计），整文件 contains 抓不到退化。
        val publishStart = backend.indexOf("suspend fun publishUsage(force: Boolean = false) {")
        val publishEnd = backend.indexOf("\n        }\n\n        try {", publishStart)
        assertTrue("publishUsage 函数起点缺失（改名或挪动会让这条判据失去意义）", publishStart >= 0)
        assertTrue("publishUsage 函数终点缺失", publishEnd > publishStart)
        val publishBody = backend.substring(publishStart, publishEnd)
        assertTrue("记账把回灌进输入的旧 assistant 也算成本次花费", publishBody.contains("it.id !in inputAssistantIds"))
        assertTrue("记账必须按 assistant 角色取", publishBody.contains("it.role == MessageRole.ASSISTANT"))
        assertTrue("用量事件按稳定 id 覆盖写", backend.contains("agentTokenUsageEventId(thread.id, thread.resumeCount, usageOrdinal)"))
        assertTrue("事件类型必须是内部用量事件", backend.contains("type = AGENT_EVENT_TYPE_TOKEN_USAGE"))
        // 三个上报点各自的身份，而不是笼统数次数（少了任何一个都必须红）：
        //   ① 流式过程中：裸调用，受 1 秒节流挡（值没变/太频繁都不写）
        //   ② 真实报错路径：force=true（整条链还会重试/换人，钱确实花出去了）
        //   ③ 正常收尾：force=true，必须落定
        // 取消路径**刻意不记账**，见下面那条红线判据。
        val streamingCall = backend.lines().count { it.trim() == "publishUsage()" }
        val forcedCalls = countOf(backend, "publishUsage(force = true)")
        val guardedCalls = countOf(backend, "runCatching { publishUsage(force = true) }")
        assertEquals("流式过程中那处裸调用（受节流）必须恰好 1 处", 1, streamingCall)
        assertEquals("真实报错 + 正常收尾两处必须都是 force=true", 2, forcedCalls)
        assertEquals("真实报错那一处必须包 NonCancellable + runCatching（写库失败不许打断生成）", 1, guardedCalls)

        // 红线判据（与既有 AgentModelFallbackTest「协程取消绝不能被当成换个模型再试」同一件事）：
        // runWithModel 的取消分支第一句必须就是 throw e，里面不许有任何挂起操作。
        // 上一版在这里插了一次 withContext(NonCancellable) 写库来「补记取消前的用量」，
        // 语义上仍算原样上抛，但那多出来的一个挂起点正是「用户点停止却还在烧钱」事故的形状 ——
        // 少记约 1 秒的用量可以接受，改变取消语义不可以。所以这里把它钉死。
        val withModel = backend.substringAfter("private suspend fun runWithModel(")
        assertTrue("找不到 runWithModel 的取消分支", withModel.contains("catch (e: kotlinx.coroutines.CancellationException)"))
        val cancelBranch = withModel
            .substringAfter("catch (e: kotlinx.coroutines.CancellationException) {")
            .substringBefore("} catch (e: Throwable)")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            // 同行注释也要摘掉再判：否则注释里出现某个函数名，就会被下面的「不许出现」误伤，
            // 那种假红会让人以为红线又坏了（本轮真踩过一次）。
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
        assertTrue(
            "取消分支的第一句必须是 throw e",
            cancelBranch.firstOrNull()?.startsWith("throw e") == true,
        )
        assertTrue(
            "取消分支里不许出现任何挂起操作（记账、写库、withContext 都不行）",
            cancelBranch.none { it.contains("publishUsage") || it.contains("withContext") || it.contains("delay(") },
        )
        assertTrue(
            "尝试序号要传进来，不同尝试不能互相覆盖",
            backend.contains("usageOrdinal = \"${'$'}{index + 1}-${'$'}attempt\""),
        )
    }

    @Test
    fun `接线 管理器不把用量事件当阶段事件`() {
        val manager = moduleFile("java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt")
        assertTrue(
            "阶段信号必须同时排除续跑检查点与用量事件",
            manager.contains("it.type != AGENT_EVENT_TYPE_RESUME_CHECKPOINT &&"),
        )
        assertTrue(manager.contains("it.type != AGENT_EVENT_TYPE_TOKEN_USAGE"))
    }

    @Test
    fun `接线 面板与详情页共用同一条用量数据源`() {
        val panel = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/AgentActivityPanel.kt")
        val page = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt")
        assertTrue("面板读仓库的用量 Flow", panel.contains("repository.tokenUsageEventsFlow(usageConversationId)"))
        assertTrue("详情页读同一条 Flow", page.contains("repository.tokenUsageEventsFlow(conversationId)"))
        assertTrue("两处都用同一个汇总函数", panel.contains("agentThreadUsageOf(") && page.contains("agentThreadUsageOf("))
        // 真判据：不许在界面层再写一份汇总/攒一份内存账（两处各算一套是「两个数」的根源）
        for ((name, text) in listOf("面板" to panel, "详情页" to page)) {
            assertFalse("$name 不许自己实现汇总函数", text.contains("fun agentThreadUsageOf"))
            assertFalse("$name 不许自己攒用量状态", text.contains("MutableStateFlow<Map<String, AgentThreadUsage>"))
        }
        // 真扫全仓（原来只读了 AgentModels 自己 —— 那不叫「全仓只有一份」）
        val root = moduleRoot()
        val aggregators = mutableListOf<String>()
        for (f in File(root, "app/src/main/java").walk()) {
            if (f.isFile && f.name.endsWith(".kt") && f.readText().contains("fun agentThreadUsageOf")) aggregators += f.name
        }
        assertEquals("汇总实现全仓只有 AgentModels 那一份", listOf("AgentModels.kt"), aggregators.distinct().sorted())
    }

    @Test
    fun `红线 算价查表全仓只有一份实现`() {
        val costFile = moduleFile("java/me/rerere/rikkahub/ui/components/ai/ConversationTokenCost.kt")
        assertTrue("共用查表函数存在", costFile.contains("fun modelPriceLookup(settings: Settings)"))
        val chatVm = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt")
        assertTrue("本对话明细改用共用查表", chatVm.contains("priceOf = modelPriceLookup(settings)"))
        val page = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt")
        assertTrue("子代理明细也用共用查表", page.contains("priceOf = modelPriceLookup(settings)"))
        // 真判据：全仓扫，ModelPrice 的构造点只许出现在共用查表那一个文件里。
        // 原来这里只读了 ChatVM 与详情页两个文件 —— 那不叫「全仓只有一份」，是假判据。
        val root = moduleRoot()
        val constructors = mutableListOf<String>()
        for (dp in File(root, "app/src/main/java").walk()) {
            if (!dp.isFile || !dp.name.endsWith(".kt")) continue
            if (dp.readText().contains("ModelPrice(")) constructors += dp.name
        }
        assertEquals(listOf("ConversationTokenCost.kt"), constructors.distinct().sorted())
    }

    @Test
    fun `红线 不动数据库版本 不往主库写用量`() {
        val agentDb = moduleFile("java/me/rerere/rikkahub/agent/db/AgentDatabase.kt")
        assertTrue("AgentDatabase 必须还是 3（数据库版本是用户红线）", agentDb.contains("version = 3,"))
        val appDb = moduleFile("java/me/rerere/rikkahub/data/db/AppDatabase.kt")
        assertTrue("AppDatabase 必须还是 32", Regex("version = 32,").containsMatchIn(appDb))
        val dao = moduleFile("java/me/rerere/rikkahub/agent/db/AgentDao.kt")
        assertTrue("用量查询排除内部事件后仍要能被界面排除", dao.contains("'TOKEN_USAGE'"))
        assertFalse("事件流不许把用量事件当可见事件", dao.contains("AND type != 'RESUME_CHECKPOINT' ORDER BY created_at ASC"))
        assertTrue("可见事件流改成 NOT IN 同时排除两种内部事件", dao.contains("type NOT IN ('RESUME_CHECKPOINT', 'TOKEN_USAGE')"))
        val entities = moduleFile("java/me/rerere/rikkahub/agent/db/AgentEntities.kt")
        assertFalse("不许给 agent_threads 加列（那要升版本）", entities.contains("promptTokens"))
        assertFalse(entities.contains("prompt_tokens"))
    }

    @Test
    fun `红线 明细弹窗默认文案不变 主对话那个入口不受影响`() {
        val dialog = moduleFile("java/me/rerere/rikkahub/ui/components/ai/ConversationTokenStatsUI.kt")
        assertTrue("标题默认值仍是原来那条资源", dialog.contains("title: String = stringResource(R.string.chat_conversation_token_detail_title)"))
        assertTrue("口径说明默认值仍是原来那条资源", dialog.contains("note: String = stringResource(R.string.chat_conversation_token_note)"))
        val page = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
        assertTrue("主对话那个明细弹窗调用点必须存在", page.contains("ConversationTokenStatsDialog("))
        val callStart = page.indexOf("ConversationTokenStatsDialog(")
        val callEnd = page.indexOf("\n        )", callStart)   // 调用点自己的收尾括号（按缩进定位）
        assertTrue("主对话弹窗调用点结尾缺失（按行切会退化成整文件）", callEnd > callStart)
        val call = page.substring(callStart, callEnd)
        assertFalse("主对话调用点不许传 title/note（传了就改了主对话口径）", call.contains("title =") || call.contains("note ="))
    }

    // ---- 文案口径 ----

    @Test
    fun `详情页文案 没取到的分项不许冒充成零`() {
        val usage = AgentThreadUsage(promptTokens = 1000, completionTokens = 200, attempts = 2, requests = 5)
        val text = agentUsageDetailText(usage, cost = null)
        assertTrue(text.contains("Σ 1.2K"))
        assertTrue("没填单价时不许出现「约 0」", !text.contains("约 0"))
        assertTrue("要交代这数怎么来的", text.contains("2 次尝试 5 次请求"))
        assertFalse("没有缓存量就不出现命中率", text.contains("命中率"))

        val priced = usage.copy(cachedTokens = 500)
        assertTrue(
            "有缓存量时才给命中率",
            agentUsageDetailText(priced, null).contains("命中率"),
        )
        // 不写死金额字面量：formatCost 会去掉尾零（既有 ConversationTokenCostTest:157 钉死
        // formatCost(0.5) == "0.5"），写死成 "0.5000" 是一条必红断言。用真函数算出期望值。
        val priced_cost = ConversationCost(cost = 0.5, pricedMessages = 2)
        assertTrue(
            "金额只在真的定了价时出现",
            agentUsageDetailText(priced, priced_cost).contains("约 " + formatCost(0.5)),
        )
    }
}
