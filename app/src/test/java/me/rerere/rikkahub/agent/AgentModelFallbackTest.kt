package me.rerere.rikkahub.agent

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
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
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
 * v236 门禁：一个模型崩了要有保底，自动换下一个。
 *
 * ## 用户真机反馈原话
 *
 * > 「在刚刚的测试中子代理没有备用模型切换功能，一个模型崩溃之后没有保底机制。」
 *
 * v235 的 `resolveModel` 用 `?:` 一路取第一个非空 id，拿到就用完事；模型真崩的时候
 * 自动重试也只是拿**同一个模型**再撞两次，撞完彻底停在失败。
 *
 * v236 把「四层模型优先级」摊平成一条候选链（显式指定 → 角色覆盖 → 子代理默认 → 主助手），
 * 前面的跑不动就往后换人。这条链不需要任何新设置：只要给子代理配了便宜模型，
 * 主助手模型天然就是它的保底。
 *
 * 只在「这次尝试还没吐出任何内容」时才换人 —— 吐了半截再换模型会把两个模型的输出
 * 拼在一起，报告直接错乱，那种情况交给续跑机制更安全。
 */
class AgentModelFallbackTest {

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

    // ------------------------------------------------------------ 候选链构造

    @Test
    fun `候选链必须保留全部四层而不是只取第一个`() {
        val backend = source(backendPath)
        assertTrue(
            "必须有 resolveModelChain",
            backend.contains("internal fun resolveModelChain("),
        )
        // 四层来源都必须进候选链
        listOf(
            "thread.modelId?.let { add(it) }",
            "settings.agentRoleModelOverrides[thread.role.name.lowercase()]",
            "settings.agentModelId?.let { add(it.toString()) }",
            "add(settings.chatModelId.toString())",
        ).forEach {
            assertTrue("候选链缺少来源：$it", backend.contains(it))
        }
        assertTrue("必须按模型去重，避免同一个模型白撞好几遍", backend.contains("distinctBy { it.id }"))
    }

    @Test
    fun `绝不能再用问号冒号一次性收敛成单个模型`() {
        val backend = source(backendPath)
        assertFalse(
            "v235 的 `threadModelId ?: roleModelId ?: settings.agentModelId` 写法必须消失",
            backend.contains("threadModelId ?: roleModelId ?: settings.agentModelId"),
        )
    }

    @Test
    fun `换模型必须只在还没吐出内容时发生`() {
        val backend = source(backendPath)
        assertTrue(
            "必须包装成带 producedOutput 的失败",
            backend.contains("class AgentAttemptFailure("),
        )
        assertTrue(
            "已经产出内容就不许换模型（否则报告会被拼错）",
            backend.contains("if (e.producedOutput) throw e.failure"),
        )
        assertTrue(
            "producedOutput 必须由真实产出决定",
            backend.contains("producedOutput = messageFirstSeen.isNotEmpty() || emittedToolEvents.isNotEmpty()"),
        )
    }

    @Test
    fun `协程取消绝不能被当成换个模型再试`() {
        val backend = source(backendPath)
        val attempt = backend.substringAfter("private suspend fun runWithModel(", "")
        assertTrue("找不到 runWithModel", attempt.isNotBlank())
        assertTrue(
            "取消必须原样上抛，否则用户点停止会变成换模型重跑（继续烧钱）",
            attempt.contains("catch (e: kotlinx.coroutines.CancellationException)") &&
                attempt.substringAfter("catch (e: kotlinx.coroutines.CancellationException)")
                    .take(40).contains("throw e"),
        )
    }

    @Test
    fun `换模型必须发出可观察事件`() {
        val backend = source(backendPath)
        assertTrue("必须发 MODEL_FALLBACK 事件", backend.contains("\"MODEL_FALLBACK\""))
        assertTrue(
            "事件必须说明换成了哪个模型",
            backend.contains("自动换用备用模型"),
        )
    }

    @Test
    fun `全链都失败时必须抛出最后一个真实错误`() {
        val backend = source(backendPath)
        assertTrue(
            "不能把真实错误吞掉换成一句笼统的失败",
            backend.contains("throw lastFailure ?: AgentModelUnavailableException("),
        )
    }

    // ------------------------------------------------------------ 实际模型落库

    @Test
    fun `真正干活的模型必须落库并回传给主模型`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = AgentThreadManager(repo, FixedModelBackend("backup-model-2"), scope, maxConcurrent = 4)
        val thread = mgr.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "随便查一下"))

        val settled = withTimeout(15_000) {
            var current = repo.thread(thread.id)
            while (current == null || current.status != AgentThreadStatus.SUCCEEDED) {
                delay(20)
                current = repo.thread(thread.id)
            }
            current
        }
        assertEquals(
            "换过模型之后，报告到底是谁跑出来的必须看得见",
            "backup-model-2",
            settled.activeModelId,
        )

        val control = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt")
        assertTrue(
            "wait_agents 必须回传 active_model_id",
            control.contains("put(\"active_model_id\", m)"),
        )
    }

    @Test
    fun `实际模型必须落进数据库列而不是只存在内存里`() {
        val entities = source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentEntities.kt")
        assertTrue(entities.contains("@ColumnInfo(\"active_model_id\")"))
        val db = source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentDatabase.kt")
        assertTrue(db.contains("ADD COLUMN active_model_id TEXT DEFAULT NULL"))
    }

    /** 固定返回某个模型 id 的假后端 */
    private class FixedModelBackend(private val modelId: String) : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            delay(20)
            onMessage(AgentMessage(threadId = thread.id, role = "assistant", content = "ok"))
            return AgentRunOutcome(
                report = AgentReport(conclusion = "完成"),
                finishReason = "stop",
                truncated = false,
                modelId = modelId,
            )
        }
    }
}
