package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v305：网络重试策略的真单测（纯函数，真跑逻辑，不是源码字符串比对）。
 *
 * 两条口径要钉死：
 * 1) 重试次数上限跟随「回复异常中断时自动续写」滑条，但滑条 <= 0 时必须落回官方 3 次 ——
 *    圆桌 / 子代理 / 压缩 / 翻译一律不传 truncationAutoResumeMax（默认 0），
 *    若跟着变成 0 就等于把它们的断网重试静默砍光。
 * 2) 单次等待 1s 起翻倍、封顶 30s，且不随移位量溢出/取模而退化。
 */
class ProviderNetworkRetryPolicyTest {

    @Test
    fun `滑条为 0 或负数时落回官方 3 次`() {
        assertEquals(3, providerNetworkRetryLimit(0))
        assertEquals(3, providerNetworkRetryLimit(-5))
    }

    @Test
    fun `滑条为正数时重试上限等于滑条`() {
        assertEquals(1, providerNetworkRetryLimit(1))
        assertEquals(3, providerNetworkRetryLimit(3))
        assertEquals(10, providerNetworkRetryLimit(10))
        assertEquals(99, providerNetworkRetryLimit(99))
    }

    @Test
    fun `等待时间前三次与官方一致`() {
        // 官方原写法：1s / 2s / 4s —— 滑条设 3 时用户感受必须与 v304 一字不差
        assertEquals(1_000L, providerNetworkRetryDelayMs(0))
        assertEquals(2_000L, providerNetworkRetryDelayMs(1))
        assertEquals(4_000L, providerNetworkRetryDelayMs(2))
        assertEquals(8_000L, providerNetworkRetryDelayMs(3))
    }

    @Test
    fun `等待时间从第 6 次重试起封顶 30 秒`() {
        // 等待时间取 providerNetworkRetryDelayMs(retryCount)，而界面提示显示的是 retryCount + 1：
        // 第 5 次重试仍等 16 秒，第 6 次起（32 秒被夹住）才封顶 30 秒。
        assertEquals(16_000L, providerNetworkRetryDelayMs(4))
        assertEquals(30_000L, providerNetworkRetryDelayMs(5))
        assertEquals(30_000L, providerNetworkRetryDelayMs(10))
        assertEquals(30_000L, providerNetworkRetryDelayMs(98))
    }

    @Test
    fun `移位量取模与溢出不会把等待退化`() {
        // Long 的 shl 对移位量取模 64：移位 64 会被当成移位 0（退化回 1 秒）；
        // 移位 100 会被当成移位 36（约两千多年）。两者都必须被夹到 30 秒。
        assertEquals(30_000L, providerNetworkRetryDelayMs(64))
        assertEquals(30_000L, providerNetworkRetryDelayMs(100))
        // 负数入参同样不能逃逸
        assertEquals(1_000L, providerNetworkRetryDelayMs(-1))
    }
}
