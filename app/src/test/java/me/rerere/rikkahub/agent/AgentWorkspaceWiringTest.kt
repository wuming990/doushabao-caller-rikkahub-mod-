package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.FakeAgentBackend
import me.rerere.rikkahub.agent.tools.createAgentControlTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v220 门禁：spawn_agent 必须把工作区透传给子代理线程。
 *
 * 已核实的 v219 缺陷：createSpawnTool 构造 AgentSpawnRequest 时没有传 workspaceId，
 * 于是 GenerationAgentBackend 调 createAgentReadOnlyTools(thread.workspaceId, ...) 时
 * 命中 `if (workspaceId.isNullOrBlank()) return emptyList()`，
 * 子代理拿到的是**空工具列表**，任何源码核验任务都只能回答“无法核验”。
 */
class AgentWorkspaceWiringTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newManager(): AgentThreadManager = AgentThreadManager(
        InMemoryAgentThreadRepository(),
        FakeAgentBackend(delayMillis = 5_000, messageCount = 1),
        scope,
        maxConcurrent = 4,
    )

    private fun spawn(manager: AgentThreadManager, workspaceId: String?): String {
        val tool = createAgentControlTools("conv-1", manager, workspaceId)
            .first { it.name == "spawn_agent" }
        val out = runBlocking {
            tool.execute(Json.parseToJsonElement("""{"task":"核验版本号","role":"explorer"}"""))
        }.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        return Json.parseToJsonElement(out).jsonObject["thread_id"]!!.jsonPrimitive.content
    }

    @Test
    fun `spawn_agent 把工作区 id 写入线程`() = runBlocking {
        val manager = newManager()
        val id = spawn(manager, "ws-1")

        assertEquals("ws-1", manager.thread(id)?.workspaceId)
    }

    @Test
    fun `没有绑定工作区时线程 workspaceId 为空且工具描述如实提示`() = runBlocking {
        val manager = newManager()
        val id = spawn(manager, null)
        assertNull(manager.thread(id)?.workspaceId)

        val description = createAgentControlTools("conv-1", manager, null)
            .first { it.name == "spawn_agent" }.description
        assertTrue(
            "未绑定工作区时必须如实告知主模型子代理读不到文件: $description",
            description.contains("no workspace is bound"),
        )
    }

    @Test
    fun `绑定工作区时描述不再出现读不到文件的提示`() {
        val description = createAgentControlTools("conv-1", newManager(), "ws-1")
            .first { it.name == "spawn_agent" }.description

        assertTrue(description.contains("read-only workspace tools"))
        assertTrue(!description.contains("no workspace is bound"))
    }

    @Test
    fun `spawn_agent 描述里的并发上限跟随实际设置而不是硬编码 4`() {
        val manager = newManager()
        manager.maxConcurrent = 7
        val description = createAgentControlTools("conv-1", manager, "ws-1")
            .first { it.name == "spawn_agent" }.description

        assertTrue("描述应反映实际上限 7: $description", description.contains("currently 7"))
        assertTrue(
            "不得继续硬编码 never spawn more than 4: $description",
            !description.contains("more than 4"),
        )
    }

    @Test
    fun `wait_agents 的数组参数仍然声明 items`() {
        val schema = createAgentControlTools("conv-1", newManager(), "ws-1")
            .first { it.name == "wait_agents" }
            .parameters()
            .toString()

        assertTrue("thread_ids 必须声明 items，否则 Gemini 会报 items: missing field", schema.contains("items"))
    }
}
