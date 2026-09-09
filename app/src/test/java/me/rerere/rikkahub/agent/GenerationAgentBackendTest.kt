package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.runtime.buildAgentSystemPrompt
import me.rerere.rikkahub.agent.runtime.buildIsolatedAssistant
import me.rerere.rikkahub.agent.tools.createAgentReadOnlyTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * v218：第 2 阶段门禁测试（纯逻辑，不调用任何模型）。
 *
 * 验证上下文隔离：
 * - 独立助手副本清空记忆/MCP/技能/正则/模式注入/知识库/网络搜索；
 * - 系统提示词包含角色说明、任务、上下文摘要，不包含主助手内容；
 * - 只读工具：workspaceId 为空 → 空列表；源码级扫描确保工具集只含 4 个只读白名单工具，
 *   绝不包含 shell/写/删/执行/发布等危险能力。
 */
class GenerationAgentBackendTest {

    private fun thread(
        task: String = "检查登录模块",
        role: AgentRole = AgentRole.DEFAULT,
        contextSummary: String? = null,
    ) = AgentThread(
        id = "t1",
        conversationId = "c1",
        task = task,
        role = role,
        workspaceId = "ws-1",
        contextSummary = contextSummary,
        createdAt = Instant.now(),
    )

    private fun baseAssistant() = Assistant(
        id = Uuid.random(),
        name = "主助手",
        systemPrompt = "主助手系统提示词（不应出现在子代理上下文中）",
        enableMemory = true,
        useGlobalMemory = true,
        enableWebSearch = true,
        enableRecentChatsReference = true,
        reasoningLevel = ReasoningLevel.MAX,
        localTools = listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.TimeInfo),
        modeInjectionIds = setOf(Uuid.random()),
        lorebookIds = setOf(Uuid.random()),
        enabledSkills = setOf("skill-a"),
        regexes = listOf(
            AssistantRegex(
                id = Uuid.random(),
                name = "r1",
                findRegex = "x",
                replaceString = "y",
                affectingScope = setOf(AssistantAffectScope.USER),
            )
        ),
        presetMessages = listOf(me.rerere.ai.ui.UIMessage.user("预设消息")),
    )

    @Test
    fun `独立助手副本清空所有污染源字段`() {
        val isolated = buildIsolatedAssistant(baseAssistant(), thread())

        assertFalse(isolated.enableMemory)
        assertFalse(isolated.useGlobalMemory)
        assertFalse(isolated.enableWebSearch)
        assertFalse(isolated.enableRecentChatsReference)
        assertTrue(isolated.mcpServers.isEmpty())
        assertTrue(isolated.localTools.isEmpty())
        assertTrue(isolated.enabledSkills.isEmpty())
        assertTrue(isolated.regexes.isEmpty())
        assertTrue(isolated.presetMessages.isEmpty())
        assertTrue(isolated.modeInjectionIds.isEmpty())
        assertTrue(isolated.lorebookIds.isEmpty())
        // 主助手 system prompt 不进入子代理
        assertFalse(isolated.systemPrompt.contains("主助手系统提示词"))
        // 模型/推理档位继承主助手（不改动）
        assertEquals(ReasoningLevel.MAX, isolated.reasoningLevel)
    }

    @Test
    fun `系统提示词包含角色说明任务与上下文摘要`() {
        val prompt = buildAgentSystemPrompt(
            thread(task = "检查登录模块", contextSummary = "这是主代理给的背景")
        )
        assertTrue(prompt.contains("检查登录模块"))
        assertTrue(prompt.contains("这是主代理给的背景"))
        assertTrue(prompt.contains("结论、证据、不确定项、建议"))
        assertTrue(prompt.contains("不能修改任何文件"))
    }

    @Test
    fun `系统提示词按角色区分`() {
        val explorer = buildAgentSystemPrompt(thread(role = AgentRole.EXPLORER))
        assertTrue(explorer.contains("探索型子代理"))
        val reviewer = buildAgentSystemPrompt(thread(role = AgentRole.REVIEWER))
        assertTrue(reviewer.contains("审查型子代理"))
        val default = buildAgentSystemPrompt(thread(role = AgentRole.DEFAULT))
        assertFalse(default.contains("探索型子代理"))
        assertFalse(default.contains("审查型子代理"))
    }

    @Test
    fun `workspaceId 为空时返回空工具列表`() {
        assertTrue(createAgentReadOnlyTools(null, null).isEmpty())
        assertTrue(createAgentReadOnlyTools("", null).isEmpty())
    }

    @Test
    fun `只读工具源码只含白名单且无危险能力`() {
        val moduleDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/agent/tools/AgentReadOnlyTools.kt"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/agent/tools/AgentReadOnlyTools.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 AgentReadOnlyTools.kt（user.dir=${moduleDir.absolutePath}）")
        val source = file.readText()

        // 工具名只允许这 4 个白名单
        val allowed = setOf(
            "workspace_list_files",
            "workspace_find_files",
            "workspace_search_text",
            "workspace_read_file",
        )
        allowed.forEach { assertTrue("缺少白名单工具 $it", source.contains("\"$it\"")) }
        assertEquals("工具名数量应为 4", 4, allowed.size)

        // 危险能力零出现
        val dangerous = listOf(
            "workspace_shell",
            "workspace_write_file",
            "workspace_delete",
            "executeCommand",
            "publish",
            "writeText",
            "deleteFile",
            "moveFile",
            "importFile",
        )
        val hits = dangerous.filter { source.contains(it) }
        assertTrue("只读工具源码出现危险能力: $hits", hits.isEmpty())
    }
}
