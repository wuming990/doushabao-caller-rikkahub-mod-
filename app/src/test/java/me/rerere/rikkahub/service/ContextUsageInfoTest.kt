package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框上方常显的「上下文占用」的组装逻辑。
 * 重点保证：分母口径与自动压缩触发值一致，界面数字走到分母时确实会触发压缩。
 */
class ContextUsageInfoTest {

    private val enabled = Assistant(enableAutoCompress = true, autoCompressTriggerTokens = 100_000)

    @Test
    fun `uses reported usage and does not mark it as estimated`() {
        val info = buildContextUsageInfo(
            assistant = enabled,
            triggerTokens = 100_000,
            reportedTokens = 25_000L,
            approxTokens = 999L,
        )

        assertEquals(25_000L, info.usedTokens)
        assertEquals(100_000, info.limitTokens)
        assertFalse(info.isEstimated)
        assertEquals(25, info.percent)
        assertEquals(0.25f, info.fraction, 0.0001f)
        assertFalse(info.nearLimit)
        assertFalse(info.overLimit)
    }

    @Test
    fun `falls back to local estimate and marks it as estimated`() {
        val info = buildContextUsageInfo(
            assistant = enabled,
            triggerTokens = 100_000,
            reportedTokens = null,
            approxTokens = 40_000L,
        )

        assertEquals(40_000L, info.usedTokens)
        assertTrue(info.isEstimated)
        assertEquals(40, info.percent)
    }

    @Test
    fun `denominator matches the auto compress trigger value`() {
        val atTrigger = buildContextUsageInfo(enabled, 100_000, reportedTokens = 100_000L, approxTokens = 0L)

        assertTrue(atTrigger.overLimit)
        assertTrue(shouldAutoCompressAtTokenCount(enabled, atTrigger.usedTokens, 100_000))

        val belowTrigger = buildContextUsageInfo(enabled, 100_000, reportedTokens = 99_999L, approxTokens = 0L)
        assertFalse(belowTrigger.overLimit)
        assertFalse(shouldAutoCompressAtTokenCount(enabled, belowTrigger.usedTokens, 100_000))
    }

    @Test
    fun `special model trigger value becomes the displayed denominator`() {
        // 当前模型被列进特殊模型表时，进度条分母必须换成该模型自己的值
        val info = buildContextUsageInfo(
            assistant = enabled,
            triggerTokens = 900_000,
            reportedTokens = 450_000L,
            approxTokens = 0L,
        )

        assertEquals(900_000, info.limitTokens)
        assertEquals(50, info.percent)
        assertFalse(info.overLimit)
    }

    @Test
    fun `no denominator when auto compress is disabled`() {
        val info = buildContextUsageInfo(
            assistant = Assistant(enableAutoCompress = false, autoCompressTriggerTokens = 100_000),
            triggerTokens = 100_000,
            reportedTokens = 30_000L,
            approxTokens = 0L,
        )

        assertFalse(info.hasLimit)
        assertEquals(30_000L, info.usedTokens)
        assertEquals(0, info.percent)
        assertEquals(0f, info.fraction, 0.0001f)
        assertFalse(info.nearLimit)
        assertFalse(info.overLimit)
    }

    @Test
    fun `near limit starts at 80 percent`() {
        assertFalse(buildContextUsageInfo(enabled, 100_000, 79_999L, 0L).nearLimit)
        assertTrue(buildContextUsageInfo(enabled, 100_000, 80_000L, 0L).nearLimit)
    }

    @Test
    fun `progress bar is clamped but percent can exceed 100`() {
        val info = buildContextUsageInfo(enabled, 100_000, reportedTokens = 150_000L, approxTokens = 0L)

        assertEquals(1f, info.fraction, 0.0001f)
        assertEquals(150, info.percent)
        assertTrue(info.overLimit)
    }

    @Test
    fun `empty conversation shows zero without crashing`() {
        val info = buildContextUsageInfo(enabled, 100_000, reportedTokens = null, approxTokens = 0L)

        assertEquals(0L, info.usedTokens)
        assertEquals(0, info.percent)
        assertEquals(0f, info.fraction, 0.0001f)
    }
}
