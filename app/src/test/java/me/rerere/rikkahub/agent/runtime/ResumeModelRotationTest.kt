package me.rerere.rikkahub.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v304：子代理「续跑次数用完 → 自动换下一个候选模型」的轮次推进。
 *
 * 这是纯函数，可以直接喂数据断言 —— 线程管理器本身要等 4 秒退避、
 * 还要真跑一遍后端，单测里造不出来，所以把「该换谁 / 该停下」这段决策抽出来钉死。
 */
class ResumeModelRotationTest {

    @Test
    fun `链里有多个候选时依次往下换`() {
        assertEquals(1, nextResumeModelRound(chainSize = 3, currentRound = 0))
        assertEquals(2, nextResumeModelRound(chainSize = 3, currentRound = 1))
    }

    @Test
    fun `轮到最后一个候选就停下_不再往后`() {
        assertNull(nextResumeModelRound(chainSize = 3, currentRound = 2))
        assertNull(nextResumeModelRound(chainSize = 1, currentRound = 0))
    }

    @Test
    fun `没有候选链时停下_与旧版本行为一致`() {
        assertNull(nextResumeModelRound(chainSize = 0, currentRound = 0))
        assertNull(nextResumeModelRound(chainSize = -1, currentRound = 1))
    }

    @Test
    fun `候选信息带显示名_事件里能写清换成了谁`() {
        val candidate = ResumeModelCandidate(id = "abc-123", displayName = "某个便宜模型")
        assertEquals("abc-123", candidate.id)
        assertEquals("某个便宜模型", candidate.displayName)
    }
}
