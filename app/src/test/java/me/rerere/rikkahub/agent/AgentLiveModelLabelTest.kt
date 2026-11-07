package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentStageSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1028：子代理**运行中**实时显示「当前使用的模型」。
 *
 * 用户原话：「给子代理新增目前正在使用的模型名称，不要等到结束了再给我看目前所使用的模型了」。
 *
 * 为什么这条链要整段钉住：模型名从后端到界面要过四道手，少任何一道用户就还是看不到 ——
 * ① 实时信号里有字段（[AgentStageSignal.modelLabel]）；
 * ② 后端**每个**候选模型都发 MODEL_ACTIVE（第一个也发，不能只等换备用模型）；
 * ③ 管理器把事件里的名字写进内存信号（不是等收尾落库）；
 * ④ 聊天页面板 / 完整对话页都优先读这个实时值。
 *
 * 旧版只有 `AgentThread.activeModelId`，那是收尾才写的，所以运行期间界面永远是空的 ——
 * 这正是用户要改掉的行为，下面第 2 条断言专防「事件被挪进 if (index > 0)」这种回退。
 */
class AgentLiveModelLabelTest {
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, relative).readText()
            dir = dir.parentFile
        }
        error("找不到仓库根（没有 settings.gradle.kts）")
    }

    @Test
    fun `阶段信号必须带模型名字段，默认留空`() {
        assertEquals("默认必须为空：没开始跑之前不许瞎猜一个模型", null, AgentStageSignal().modelLabel)
        assertEquals("m", AgentStageSignal().copy(modelLabel = "m").modelLabel)
    }

    @Test
    fun `后端必须每个候选模型都发 MODEL_ACTIVE，第一个也要`() {
        val backend = source(
            "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt",
        )
        val eventAt = backend.indexOf("type = AGENT_EVENT_TYPE_MODEL_ACTIVE")
        assertTrue("后端必须真的发 MODEL_ACTIVE 事件", eventAt >= 0)
        val fallbackAt = backend.indexOf("if (index > 0) {")
        assertTrue("找不到备用模型判断，锚点已漂移，本条断言需要重新对位", fallbackAt >= 0)
        assertTrue(
            "MODEL_ACTIVE 必须排在 if (index > 0) 之前 —— 放进去就变成「只在换人时才发」，" +
                "第一个模型仍然看不到，等于没修",
            eventAt < fallbackAt,
        )
    }

    @Test
    fun `管理器必须把事件里的模型名写进实时信号`() {
        val manager = source(
            "app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt",
        )
        assertTrue(
            "管理器必须识别 MODEL_ACTIVE",
            manager.contains("if (it.type == AGENT_EVENT_TYPE_MODEL_ACTIVE)"),
        )
        assertTrue(
            "识别后必须把 detail（模型名）写进 noteStageModel",
            manager.contains("noteStageModel(threadId, it.detail)"),
        )
        assertTrue(
            "noteStageModel 必须真的更新 modelLabel",
            manager.contains("cur.copy(modelLabel = label)"),
        )
    }

    @Test
    fun `活动面板必须把实时模型名显示到每一行`() {
        val panel = source(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentActivityPanel.kt",
        )
        assertTrue("面板必须把信号里的模型名传给行", panel.contains("modelLabel = signal?.modelLabel"))
        assertTrue("行里必须真的画出来", panel.contains("模型：\$modelLabel"))
        assertTrue(
            "详情弹窗也必须收到实时值（否则出现「外面有、点进去没有」）",
            panel.contains("liveModelLabel = stage[thread.id]?.modelLabel"),
        )
    }

    @Test
    fun `完整对话页也必须优先用实时值而不是落库值`() {
        val page = source(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt",
        )
        assertTrue(
            "完整对话页必须订阅实时信号",
            page.contains("val liveModelLabel = stage[threadId]?.modelLabel"),
        )
        assertTrue(
            "必须实时值优先；落库值只能在**终态**兜底 —— 非终态回落会把上一轮的旧模型" +
                "说成「当前使用的模型」，那是撒谎",
            page.contains("val activeModelName = liveModelLabel") &&
                page.contains("thread?.takeIf { it.status.isTerminal }"),
        )
    }
}
