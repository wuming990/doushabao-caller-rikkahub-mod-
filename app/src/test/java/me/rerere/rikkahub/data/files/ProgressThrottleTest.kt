package me.rerere.rikkahub.data.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 十几万文件的复制只能按节流频率推送进度，否则界面快照堆积会吃光内存。
 */
class ProgressThrottleTest {
    @Test
    fun `first call always emits`() {
        val throttle = ProgressThrottle(intervalMillis = 300) { 1_000 }
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `calls inside interval are suppressed`() {
        var now = 1_000L
        val throttle = ProgressThrottle(intervalMillis = 300) { now }
        assertTrue(throttle.shouldEmit())
        now = 1_100
        assertFalse(throttle.shouldEmit())
        now = 1_299
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun `emits again after interval passes`() {
        var now = 1_000L
        val throttle = ProgressThrottle(intervalMillis = 300) { now }
        assertTrue(throttle.shouldEmit())
        now = 1_300
        assertTrue(throttle.shouldEmit())
        now = 10_000
        assertTrue(throttle.shouldEmit())
    }
}
