package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenSpeedTest {
    @Test
    fun `正常换算成每秒 token`() {
        assertEquals(50.0, computeTokenSpeed(tokens = 100, durationMs = 2_000L)!!.toDouble(), 0.0001)
    }

    @Test
    fun `耗时缺失或非正返回 null 不用 0 冒充`() {
        assertNull(computeTokenSpeed(tokens = 100, durationMs = null))
        assertNull(computeTokenSpeed(tokens = 100, durationMs = 0L))
        assertNull(computeTokenSpeed(tokens = 100, durationMs = -1L))
    }

    @Test
    fun `token 非正返回 null`() {
        assertNull(computeTokenSpeed(tokens = 0, durationMs = 1_000L))
    }
}
