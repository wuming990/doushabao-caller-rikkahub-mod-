package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.service.GroupRunState
import me.rerere.rikkahub.service.GroupRunStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * v1023：真实摘要行为 + 明确标注的UI接线守卫。
 * 反射只用于让这些测试也能对改前已编译类运行并产生断言失败；被调的是生产函数，非替身。
 * 接线守卫不冒充实机布局测量；字号/窄屏/触摸仍须用户验收。
 */
class V1023CompactActivityTest {
    private val groupId = Uuid.parse("00000000-0000-0000-0000-000000000023")
    private fun thread(id: String, status: AgentThreadStatus) = AgentThread(
        id = id, conversationId = "compact-test", task = id,
        status = status, createdAt = Instant.EPOCH,
    )
    private fun group(status: GroupRunStatus = GroupRunStatus.IDLE) =
        GroupRunState(conversationId = groupId, status = status)

    private fun summaryFunction(name: String, vararg types: Class<*>): java.lang.reflect.Method {
        val type = try {
            Class.forName("me.rerere.rikkahub.ui.pages.chat.CompactActivitySummaryKt")
        } catch (_: ClassNotFoundException) {
            throw AssertionError("折叠后仍只有原大卡片：缺少共用紧凑摘要入口")
        }
        return type.getDeclaredMethod(name, *types)
    }
    private fun agent(threads: List<AgentThread>): Any? =
        summaryFunction("compactAgentActivitySummary", List::class.java).invoke(null, threads)
    private fun groupSummary(
        state: GroupRunState? = null,
        hasConfig: Boolean = true,
        paused: Boolean = false,
        membersEnough: Boolean = true,
    ): Any? = summaryFunction(
        "compactGroupActivitySummary", GroupRunState::class.java,
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
    ).invoke(null, state, hasConfig, paused, membersEnough)
    private fun field(value: Any?, name: String): Any? {
        assertNotNull("可见活动必须有入口", value)
        return value!!.javaClass.getDeclaredMethod("get$name").invoke(value)
    }
    private fun label(value: Any?) = field(value, "Label") as String
    private fun description(value: Any?) = field(value, "ContentDescription") as String
    private fun attention(value: Any?) = field(value, "NeedsAttention") as Boolean

    @Test fun `没有代理不留空入口`() { assertNull(agent(emptyList())) }
    @Test fun `全部已关闭不再留入口`() {
        assertNull(agent(listOf(thread("closed", AgentThreadStatus.CLOSED))))
    }
    @Test fun `运行与已结束计数放进一个短入口且不把关闭记录算进去`() {
        val threads = listOf(thread("a", AgentThreadStatus.RUNNING), thread("b", AgentThreadStatus.QUEUED)) +
            (1..26).map { thread("done$it", AgentThreadStatus.SUCCEEDED) } +
            thread("hidden", AgentThreadStatus.CLOSED)
        val value = agent(threads)
        assertEquals("代理 2运行·26结束", label(value))
        assertTrue(description(value).contains("2 个运行中"))
        assertTrue(description(value).contains("26 个已结束"))
        assertFalse(attention(value))
    }
    @Test fun `只有结束的代理也用短入口不显示零运行`() {
        assertEquals("代理 1结束", label(agent(listOf(thread("done", AgentThreadStatus.SUCCEEDED)))))
    }
    @Test fun `等审批不能伪装成普通运行`() {
        val value = agent(listOf(thread("wait", AgentThreadStatus.WAITING_APPROVAL), thread("run", AgentThreadStatus.RUNNING)))
        assertEquals("代理 1待批·1运行", label(value))
        assertTrue(description(value).contains("等待审批"))
        assertTrue(attention(value))
    }
    @Test fun `代理失败仍有提醒而非被折叠隐藏`() {
        val value = agent(listOf(thread("bad", AgentThreadStatus.FAILED)))
        assertEquals("代理 1结束", label(value))
        assertTrue(attention(value))
        assertTrue(description(value).contains("1 个失败"))
    }
    @Test fun `用户主动停止不当成错误提醒`() {
        assertFalse(attention(agent(listOf(thread("stop", AgentThreadStatus.STOPPED)))))
    }
    @Test fun `没建过群且没有运行场次就不留群入口`() {
        assertNull(groupSummary(hasConfig = false))
    }
    @Test fun `仅有群配置也能从就绪入口展开`() {
        assertEquals("讨论群·就绪", label(groupSummary()))
    }
    @Test fun `运行场次存在但配置不在时不能丢掉入口`() {
        assertEquals("讨论群·已停止", label(groupSummary(group(GroupRunStatus.STOPPED), hasConfig = false)))
    }
    @Test fun `等待用户回答时短入口明确提示且有提醒`() {
        val value = groupSummary(group(GroupRunStatus.WAITING_FOR_USER))
        assertEquals("讨论群·等你回答", label(value))
        assertTrue(attention(value))
        assertTrue(description(value).contains("等待你回答"))
    }
    @Test fun `发言者和批次进度不会因为缩小而消失`() {
        val state = group(GroupRunStatus.SPEAKING).copy(currentSpeakerName = "模型C", turnsInRun = 1, turnsBudget = 3)
        val value = groupSummary(state)
        assertEquals("讨论群·模型C 1/3", label(value))
        assertTrue(description(value).contains("模型C 正在发言"))
        assertTrue(description(value).contains("第 1/3 条"))
    }
    @Test fun `暂停配置优先于旧运行状态且不显示过时进度`() {
        val state = group(GroupRunStatus.SPEAKING).copy(turnsInRun = 1, turnsBudget = 3)
        assertEquals("讨论群·已暂停", label(groupSummary(state, paused = true)))
    }
    @Test fun `群报错提示保留实际原因`() {
        val value = groupSummary(group().copy(error = "服务暂不可用"))
        assertTrue(attention(value))
        assertTrue(description(value).contains("服务暂不可用"))
    }
    @Test fun `群成员不足时不能显示成无事发生`() {
        val value = groupSummary(membersEnough = false)
        assertTrue(attention(value))
        assertTrue(description(value).contains("成员不足两个"))
    }
    @Test fun `各群状态都有短且可读的入口不暴露枚举名`() {
        val labels = mapOf(
            GroupRunStatus.IDLE to "讨论群·就绪",
            GroupRunStatus.SPEAKING to "讨论群·发言中",
            GroupRunStatus.WAITING_FOR_USER to "讨论群·等你回答",
            GroupRunStatus.PAUSED to "讨论群·可继续",
            GroupRunStatus.STOPPED to "讨论群·已停止",
            GroupRunStatus.FINISHED to "讨论群·已结束",
        )
        labels.forEach { (status, want) -> assertEquals(want, label(groupSummary(group(status)))) }
    }

    private fun source(name: String): String {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull("没有找到源码根", root)
        val file = File(root, "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/$name.kt")
        return if (file.isFile) file.readText() else ""
    }

    @Test fun `接线守卫 两种摘要真的进入同一排且单项不强制铺满`() {
        val bar = source("CompactActivityBar")
        assertTrue("共用条不能缺失", bar.contains("fun CompactActivityBar("))
        assertTrue("入口应共享Row", bar.contains("Row("))
        assertTrue("单项和双项都要按内容收紧", bar.contains("weight(1f, fill = false)"))
        assertTrue("长名字不挤成多行", bar.contains("maxLines = 1"))
        assertTrue("大字号不应被固定高度裁掉", bar.contains("defaultMinSize(") && !bar.contains(".height("))
        assertFalse("不沿用大按钮强占的高度", bar.contains("IconButton(") || bar.contains("TextButton("))
    }
    @Test fun `接线守卫 页面提供真实摘要和各自展开回调`() {
        val page = source("ChatPage")
        assertTrue(page.contains("CompactActivityBar("))
        assertTrue(page.contains("compactAgentActivitySummary(agentThreads)"))
        assertTrue(page.contains("compactGroupActivitySummary("))
        assertTrue(page.contains("onExpandAgents = { agentPanelExpanded = true }"))
        assertTrue(page.contains("onExpandGroup = onToggleGroupPanel"))
        assertTrue(page.contains("if (setting.enableAgentTools)"))
        assertTrue(page.contains("if (assistant.enableAgentTools)"))
    }
    @Test fun `接线守卫 代理收起时不再组合整块大底板`() {
        val panel = source("AgentActivityPanel")
        assertTrue(panel.contains("expanded: Boolean,"))
        assertTrue(panel.contains("onToggleExpanded: () -> Unit,"))
        val start = panel.indexOf("if (!expanded) return")
        val surface = panel.indexOf("Surface(")
        assertTrue("整个大底板必须在收起返回之后", start >= 0 && surface > start)
        assertFalse("展开状态不能内外各存一份", panel.contains("var expanded by remember"))
    }
    @Test fun `接线守卫 群正常收起无旧标题占位但报错与缺成员不能被隐藏`() {
        val panel = source("GroupChatPanel")
        assertTrue(panel.contains("showCollapsedHeader: Boolean = true,"))
        assertTrue(panel.contains("collapsed && !showCollapsedHeader"))
        assertTrue(panel.contains("state?.error == null && membersEnough"))
        assertTrue("坏状态仍有直接可读提示", panel.contains("if (!membersEnough) {") && panel.contains("text = state.error"))
        assertTrue("页面明确使用共用入口而非旧折叠标题", source("ChatPage").contains("showCollapsedHeader = false,"))
    }
}
