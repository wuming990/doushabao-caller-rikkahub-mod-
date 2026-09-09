package me.rerere.rikkahub.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v220 门禁（源码级扫描）：锁死两个已经真实发生过的接线缺陷，防止再次回归。
 *
 * 1) ChatService 装配 spawn 工具时必须透传 workspaceId，否则子代理读不到任何文件；
 * 2) 子代理开关与默认模型必须在 DataStore 里**既读又写**，否则设置会被默认值覆盖
 *    （用户实际反馈：设置过的子代理模型自己消失、恢复默认）。
 */
class AgentWiringRegressionGuardTest {

    private val moduleDir = File(System.getProperty("user.dir"))

    private fun source(relative: String): String {
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    @Test
    fun `ChatService 装配子代理工具时必须传 workspaceId`() {
        val text = source("service/ChatService.kt")
        val call = text.substringAfter("createAgentControlTools(", "")
        assertTrue("ChatService 未调用 createAgentControlTools", call.isNotBlank())

        val snippet = call.take(400)
        assertTrue(
            "createAgentControlTools 调用必须显式传 workspaceId：\n$snippet",
            snippet.contains("workspaceId"),
        )
        assertTrue(
            "workspaceId 应取自助手绑定的工作区：\n$snippet",
            snippet.contains("assistant.workspaceId"),
        )
    }

    @Test
    fun `子代理开关与默认模型必须既读又写 DataStore`() {
        val text = source("data/datastore/PreferencesStore.kt")

        listOf("AGENT_ENABLED", "AGENT_MODEL").forEach { key ->
            val occurrences = Regex(Regex.escape(key)).findAll(text).count()
            // 1 次定义 + 至少 1 次读 + 至少 1 次写
            assertTrue(
                "$key 出现次数不足（需定义/读取/写入各至少一次），实际 $occurrences 次",
                occurrences >= 3,
            )
        }

        assertTrue(
            "settingsFlowRaw 必须读回 enableAgentTools",
            text.contains("enableAgentTools = preferences[AGENT_ENABLED]"),
        )
        assertTrue(
            "settingsFlowRaw 必须读回 agentModelId",
            text.contains("agentModelId = preferences[AGENT_MODEL]"),
        )
        assertTrue(
            "update 必须写入 enableAgentTools",
            text.contains("preferences[AGENT_ENABLED] = settings.enableAgentTools"),
        )
        assertTrue(
            "update 必须写入 agentModelId（含清除分支）",
            text.contains("preferences[AGENT_MODEL] = it.toString()") &&
                text.contains("preferences.remove(AGENT_MODEL)"),
        )
    }

    @Test
    fun `角色模型表必须走容错编解码而不是直接序列化可空表`() {
        val text = source("data/datastore/PreferencesStore.kt")

        assertTrue(
            "读取角色模型必须使用 decodeAgentRoleModelOverrides",
            text.contains("decodeAgentRoleModelOverrides(preferences[AGENT_ROLE_MODELS])"),
        )
        assertTrue(
            "写入角色模型必须使用 encodeAgentRoleModelOverrides",
            text.contains("encodeAgentRoleModelOverrides(settings.agentRoleModelOverrides)"),
        )
        assertTrue(
            "不得再把可空表直接交给序列化（v219 会导致整表丢失）",
            !text.contains("agentRoleModelOverrides.mapValues { (_, v) -> v?.toString() }"),
        )
    }

    @Test
    fun `代理面板必须提供关闭渠道`() {
        val panel = source("ui/pages/chat/AgentActivityPanel.kt")
        val vm = source("ui/pages/chat/ChatVM.kt")
        val page = source("ui/pages/chat/ChatPage.kt")

        assertTrue("面板必须有 onCloseFinished 入口", panel.contains("onCloseFinished"))
        assertTrue("面板必须有单个关闭入口", panel.contains("onClose"))
        assertTrue("面板必须过滤 CLOSED 线程", panel.contains("visibleAgentThreads"))
        assertTrue("ChatVM 必须暴露 closeAgent", vm.contains("fun closeAgent("))
        assertTrue("ChatVM 必须暴露 closeFinishedAgents", vm.contains("fun closeFinishedAgents("))
        assertTrue("ChatPage 必须接线 onCloseFinished", page.contains("onCloseFinished = { vm.closeFinishedAgents() }"))
        assertTrue("ChatPage 必须接线 onClose", page.contains("onClose = { vm.closeAgent(it) }"))
    }
}
