package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.ui.pages.chat.agentStatusText
import me.rerere.rikkahub.ui.pages.chat.closableAgentThreadIds
import me.rerere.rikkahub.ui.pages.chat.visibleAgentThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * v220 门禁：「代理活动」面板必须有关闭渠道。
 *
 * 用户实际反馈：代理线程堆积后面板点开根本关不上，没有关闭入口。
 * 修复语义：
 * - CLOSED 线程不再出现在面板上（记录仍保留在 AgentDatabase 中）；
 * - 「清空已结束」只关闭终态线程，运行中的绝不受影响；
 * - 全部线程都被关闭后面板整体消失。
 */
class AgentActivityPanelLogicTest {

    private fun thread(id: String, status: AgentThreadStatus, error: String? = null) = AgentThread(
        id = id,
        conversationId = "conv-1",
        task = "任务 $id",
        status = status,
        error = error,
        createdAt = Instant.now(),
    )

    @Test
    fun `已关闭的线程不再显示在面板上`() {
        val threads = listOf(
            thread("a", AgentThreadStatus.RUNNING),
            thread("b", AgentThreadStatus.CLOSED),
            thread("c", AgentThreadStatus.SUCCEEDED),
        )

        assertEquals(listOf("a", "c"), visibleAgentThreads(threads).map { it.id })
    }

    @Test
    fun `全部关闭后面板没有任何可显示内容`() {
        val threads = listOf(
            thread("a", AgentThreadStatus.CLOSED),
            thread("b", AgentThreadStatus.CLOSED),
        )

        assertTrue(visibleAgentThreads(threads).isEmpty())
    }

    @Test
    fun `清空已结束只挑终态线程且跳过已关闭的`() {
        val threads = listOf(
            thread("queued", AgentThreadStatus.QUEUED),
            thread("running", AgentThreadStatus.RUNNING),
            thread("succeeded", AgentThreadStatus.SUCCEEDED),
            thread("failed", AgentThreadStatus.FAILED),
            thread("stopped", AgentThreadStatus.STOPPED),
            thread("interrupted", AgentThreadStatus.INTERRUPTED),
            thread("closed", AgentThreadStatus.CLOSED),
        )

        assertEquals(
            listOf("succeeded", "failed", "stopped", "interrupted"),
            closableAgentThreadIds(threads),
        )
    }

    @Test
    fun `运行中的线程永远不会被清空已结束波及`() {
        val threads = listOf(
            thread("running", AgentThreadStatus.RUNNING),
            thread("queued", AgentThreadStatus.QUEUED),
            thread("waiting", AgentThreadStatus.WAITING_APPROVAL),
        )

        assertTrue(closableAgentThreadIds(threads).isEmpty())
    }

    @Test
    fun `失败线程状态文案带出错误原因`() {
        val text = agentStatusText(thread("f", AgentThreadStatus.FAILED, error = "接口返回网页"))

        assertTrue(text.contains("失败"))
        assertTrue(text.contains("接口返回网页"))
    }

    @Test
    fun `每个状态都有可读文案不会露出枚举名`() {
        AgentThreadStatus.entries.forEach { status ->
            val text = agentStatusText(thread("x", status))
            assertTrue("状态 $status 缺少中文文案", text.isNotBlank())
            assertTrue("状态 $status 直接暴露了枚举名: $text", !text.contains(status.name))
        }
    }
}
