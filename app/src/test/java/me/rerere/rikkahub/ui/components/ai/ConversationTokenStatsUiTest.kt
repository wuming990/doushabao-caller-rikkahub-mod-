package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v299：本对话 token 明细里的「缓存命中率」纯函数单测。
 *
 * 拆成顶层函数就是为了能这样真跑 —— 埋在 Composable 里只能靠读源码做字符串断言，
 * 除零、四舍五入这类问题根本测不出来。
 */
class ConversationTokenStatsUiTest {

    @Test
    fun `命中率按 缓存输入 除以 总输入 计算`() {
        assertEquals("50%", formatCacheHitRate(cachedTokens = 500, promptTokens = 1000))
        assertEquals("42.3%", formatCacheHitRate(cachedTokens = 423, promptTokens = 1000))
        assertEquals("100%", formatCacheHitRate(cachedTokens = 1000, promptTokens = 1000))
        assertEquals("0%", formatCacheHitRate(cachedTokens = 0, promptTokens = 1000))
    }

    @Test
    fun `输入为 0 时不给数字 也不能抛异常`() {
        assertEquals("—", formatCacheHitRate(cachedTokens = 0, promptTokens = 0))
        assertEquals("—", formatCacheHitRate(cachedTokens = 123, promptTokens = 0))
        assertEquals("—", formatCacheHitRate(cachedTokens = 0, promptTokens = -5))
    }

    @Test
    fun `整数百分比不带小数点`() {
        assertEquals("7%", formatCacheHitRate(cachedTokens = 70, promptTokens = 1000))
        assertEquals("7.1%", formatCacheHitRate(cachedTokens = 71, promptTokens = 1000))
    }
}
