package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v290：密钥轮询判定与换 key 决策的单元测试（纯 JVM，不依赖 Android Context）。
 *
 * 背景真机故障：Kimi 的限速报错「RPM (Requests Per Minute) limit of the model kimi-k3
 * is exceeded. Please try again later」既不含 "rate limit" 也没有独立的 429 数字
 * （Request id 是连续字母数字串，词边界正则提不出状态码），导致多把密钥一次都不换、
 * 续跑也不触发，直接弹「消息生成失败」。
 */
class KeyRetryDecisionTest {

    /** 用户真机截图原文（kimi-k3 经中转站） */
    private val kimiRpmError = "RPM (Requests Per Minute) limit of the model kimi-k3 is exceeded. " +
        "Please try again later Request id: 021788670385441137f500080bf18603e76d17d12e7c43e53dbc5"

    @Test
    fun `Kimi的RPM限速报错被判定为可换key`() {
        assertTrue(isKeyRetryableError(Exception(kimiRpmError)))
    }

    @Test
    fun `普通错误不触发换key`() {
        assertFalse(isKeyRetryableError(Exception("something went wrong")))
        assertFalse(isKeyRetryableError(Exception("")))
        assertFalse(isKeyRetryableError(Exception("context is too long")))
    }

    @Test
    fun `传统的rate_limit与too_many_requests文案仍然命中`() {
        assertTrue(isKeyRetryableError(Exception("429 Too Many Requests")))
        assertTrue(isKeyRetryableError(Exception("Rate limit exceeded")))
        assertTrue(isKeyRetryableError(Exception("Insufficient balance")))
    }

    @Test
    fun `v291_TPM与天配额与节流文案也命中`() {
        // 真机实锤："inference tpm exhausted" 三道关卡全漏（不换 key、不重试、不续跑）
        assertTrue(isKeyRetryableError(Exception("inference tpm exhausted")))
        assertTrue(isKeyRetryableError(Exception("current rpm exhausted")))
        assertTrue(isKeyRetryableError(Exception("tokens per minute limit reached")))
        assertTrue(isKeyRetryableError(Exception("requests per day exceeded")))
        assertTrue(isKeyRetryableError(Exception("Request throttled by upstream")))
    }

    @Test
    fun `已吐出内容后绝不换key重发`() {
        // 重发必然前半段重复（v285 规矩），吐过字就交给上层续跑机制
        assertFalse(canRetryWithNextKey(switchCount = 0, keyCount = 10, emittedAny = true))
    }

    @Test
    fun `只有一把key时不换`() {
        assertFalse(canRetryWithNextKey(switchCount = 0, keyCount = 1, emittedAny = false))
    }

    @Test
    fun `把整个密钥池轮完为止`() {
        // 10 把 key：第 1~9 次切换都允许（每把都试一遍），第 10 把试完（switchCount=9）到顶
        repeat(9) { i ->
            assertTrue(
                "第 ${i + 1} 次切换应被允许",
                canRetryWithNextKey(switchCount = i, keyCount = 10, emittedAny = false),
            )
        }
        assertFalse(canRetryWithNextKey(switchCount = 9, keyCount = 10, emittedAny = false))
        // 2 把 key：换 1 次到顶
        assertTrue(canRetryWithNextKey(switchCount = 0, keyCount = 2, emittedAny = false))
        assertFalse(canRetryWithNextKey(switchCount = 1, keyCount = 2, emittedAny = false))
    }

    @Test
    fun `keyCount的分隔符规则与轮询器一致`() {
        val roulette = KeyRoulette.default()
        assertEquals(3, roulette.keyCount("a,b,c"))
        assertEquals(3, roulette.keyCount("a，b、c")) // 全角逗号、顿号
        assertEquals(2, roulette.keyCount("a; b"))
        assertEquals(1, roulette.keyCount("single-key"))
        assertEquals(0, roulette.keyCount("  "))
    }
}
