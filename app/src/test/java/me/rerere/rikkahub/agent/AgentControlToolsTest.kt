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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.tools.createAgentControlTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v218：第 3 阶段门禁测试（控制工具，假后端，不调用任何模型）。
 * v235 起 7 个工具，v236 起 8 个（新增 run_agent_pipeline）。
 *
 * 验证：
 * - spawn_agent 返回 thread_id；缺 task 返回错误；并发超限返回错误（含上限值）；
 * - wait_agents 等待成功并返回报告结论；
 * - list_agents 列出线程；send_agent_message 仅对运行中有效；stop_agent 只停目标；
 * - close_agent 仅终态可关闭。
 */
class AgentControlToolsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newManager(maxConcurrent: Int = 4): AgentThreadManager =
        AgentThreadManager(
            InMemoryAgentThreadRepository(),
            FakeBackend(),
            scope,
            maxConcurrent = maxConcurrent,
        )

    private fun tools(manager: AgentThreadManager) = createAgentControlTools("conv-1", manager)

    private fun exec(toolName: String, manager: AgentThreadManager, args: String): String {
        val tool = tools(manager).first { it.name == toolName }
        val result = runBlocking {
            tool.execute(Json.parseToJsonElement(args))
        }
        val text = result.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        return text
    }

    private fun textOf(json: String, key: String): String? {
        val el = Json.parseToJsonElement(json).jsonObject[key]
        return el?.jsonPrimitive?.contentOrNull
    }

    @Test
    fun `spawn 返回 thread_id 且线程进入运行`() = runBlocking {
        val manager = newManager()
        val out = exec("spawn_agent", manager, """{"task":"检查登录模块","role":"explorer"}""")
        val id = textOf(out, "thread_id")
        assertTrue("应返回 thread_id: $out", id != null && id!!.isNotBlank())
        // 线程存在且非终态（FakeBackend 慢，仍在运行）
        assertTrue(manager.list("conv-1").any { it.id == id })
    }

    @Test
    fun `spawn 缺 task 返回错误`() {
        val manager = newManager()
        val out = exec("spawn_agent", manager, """{}""")
        assertEquals("task is required", textOf(out, "error"))
    }

    @Test
    fun `spawn 并发超限返回错误含上限`() = runBlocking {
        val manager = newManager(maxConcurrent = 2)
        exec("spawn_agent", manager, """{"task":"t1"}""")
        exec("spawn_agent", manager, """{"task":"t2"}""")
        val out = exec("spawn_agent", manager, """{"task":"t3"}""")
        assertEquals("并发代理已达上限 2", textOf(out, "error"))
        assertEquals(2, textOf(out, "max_concurrent")?.toInt())
    }

    @Test
    fun `wait_agents 等到成功并返回结论`() = runBlocking {
        val manager = newManager()
        val out1 = exec("spawn_agent", manager, """{"task":"检查A"}""")
        val out2 = exec("spawn_agent", manager, """{"task":"检查B"}""")
        val id1 = textOf(out1, "thread_id")!!
        val id2 = textOf(out2, "thread_id")!!
        val wait = exec(
            "wait_agents",
            manager,
            """{"thread_ids":["$id1","$id2"],"timeout_seconds":30}"""
        )
        val threads = Json.parseToJsonElement(wait).jsonObject["threads"]!!
            .let { Json.parseToJsonElement(it.toString()).jsonArray }
        assertEquals(2, threads.size)
        threads.forEach { el ->
            val obj = el.jsonObject
            assertEquals("SUCCEEDED", obj["status"]!!.jsonPrimitive.content)
            assertTrue(obj["conclusion"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `list_agents 列出全部线程`() = runBlocking {
        val manager = newManager()
        exec("spawn_agent", manager, """{"task":"t1"}""")
        exec("spawn_agent", manager, """{"task":"t2"}""")
        val out = exec("list_agents", manager, """{}""")
        val threads = Json.parseToJsonElement(out).jsonObject["threads"]!!
            .let { Json.parseToJsonElement(it.toString()).jsonArray }
        assertEquals(2, threads.size)
    }

    @Test
    fun `send_agent_message 仅对运行中有效`() = runBlocking {
        val manager = newManager()
        // 运行中（SLOW 长时间不结束）：成功
        val running = textOf(exec("spawn_agent", manager, """{"task":"SLOW"}"""), "thread_id")!!
        val ok = exec("send_agent_message", manager, """{"thread_id":"$running","content":"补充要求"}""")
        assertEquals("true", textOf(ok, "ok"))
        // 已完成（FAST 快速结束）：错误（不自动重跑）
        val done = textOf(exec("spawn_agent", manager, """{"task":"FAST"}"""), "thread_id")!!
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == done && it.status.isTerminal }) delay(20)
        }
        val err = exec("send_agent_message", manager, """{"thread_id":"$done","content":"晚了"}""")
        assertTrue(textOf(err, "error")!!.contains("already finished"))
    }

    @Test
    fun `stop_agent 只停目标线程`() = runBlocking {
        val manager = newManager()
        val slow = textOf(exec("spawn_agent", manager, """{"task":"SLOW"}"""), "thread_id")!!
        val fast = textOf(exec("spawn_agent", manager, """{"task":"FAST"}"""), "thread_id")!!
        exec("stop_agent", manager, """{"thread_id":"$slow"}""")
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == fast && it.status.isTerminal }) delay(20)
        }
        val threads = manager.list("conv-1")
        assertEquals(AgentThreadStatus.STOPPED, threads.first { it.id == slow }.status)
        assertEquals(AgentThreadStatus.SUCCEEDED, threads.first { it.id == fast }.status)
    }

    @Test
    fun `close_agent 仅终态可关闭`() = runBlocking {
        val manager = newManager()
        val running = textOf(exec("spawn_agent", manager, """{"task":"SLOW"}"""), "thread_id")!!
        // 运行中拒绝关闭
        val err = exec("close_agent", manager, """{"thread_id":"$running"}""")
        assertTrue(textOf(err, "error")!!.contains("still running"))
        // 等完成后再关
        val done = textOf(exec("spawn_agent", manager, """{"task":"FAST"}"""), "thread_id")!!
        withTimeout(15_000) {
            while (manager.list("conv-1").none { it.id == done && it.status.isTerminal }) delay(20)
        }
        val ok = exec("close_agent", manager, """{"thread_id":"$done"}""")
        assertEquals("true", textOf(ok, "ok"))
        assertEquals(AgentThreadStatus.CLOSED, manager.list("conv-1").first { it.id == done }.status)
    }

    private class FakeBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): me.rerere.rikkahub.agent.runtime.AgentRunOutcome {
            if (thread.task.contains("SLOW")) {
                try {
                    delay(30_000)
                } catch (e: CancellationException) {
                    throw e
                }
            } else {
                delay(50)
                onEvent(AgentEvent(threadId = thread.id, type = "PROGRESS", detail = "done"))
                onMessage(AgentMessage(threadId = thread.id, role = "assistant", content = "结果：${thread.task}"))
            }
            return me.rerere.rikkahub.agent.runtime.AgentRunOutcome(
                report = AgentReport(
                    conclusion = "完成 ${thread.task}",
                    evidence = listOf("证据1"),
                    uncertainties = listOf(),
                    suggestions = listOf(),
                ),
                finishReason = "stop",
                truncated = false,
            )
        }
    }
}
