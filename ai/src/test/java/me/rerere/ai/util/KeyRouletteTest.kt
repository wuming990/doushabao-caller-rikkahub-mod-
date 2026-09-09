package me.rerere.ai.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v255：密钥轮询「失败感知」的单元测试（纯 JVM，不依赖 Android Context） */
class KeyRouletteTest {

    // 按脚本出牌的假轮询器，保证测试确定性
    private class ScriptedRoulette : KeyRoulette {
        val queue = ArrayDeque<String>()
        val failed = mutableListOf<Pair<String, String>>()

        override fun next(keys: String, providerId: String): String = queue.removeFirst()

        override fun markFailed(providerId: String, key: String) {
            failed.add(providerId to key)
        }
    }

    @Test
    fun `默认轮询器在冷却期内跳过失败过的key`() {
        val roulette = DefaultKeyRoulette()
        roulette.markFailed("p1", "keyA")
        repeat(50) {
            val picked = roulette.next("keyA,keyB,keyC", "p1")
            assertTrue("冷却期内不能选 keyA，实际选了 $picked", picked != "keyA")
        }
    }

    @Test
    fun `全部key都在冷却期时回退随机选择保证请求仍会发出`() {
        val roulette = DefaultKeyRoulette()
        roulette.markFailed("p1", "keyA")
        roulette.markFailed("p1", "keyB")
        val picked = roulette.next("keyA,keyB", "p1")
        assertTrue("全部冷却中也要能选出一个", picked == "keyA" || picked == "keyB")
    }

    @Test
    fun `失败标记只作用于对应provider`() {
        val roulette = DefaultKeyRoulette()
        roulette.markFailed("p1", "keyA")
        repeat(20) {
            val picked = roulette.next("keyA,keyB", "p2")
            assertTrue("另一个 provider 不受影响", picked == "keyA" || picked == "keyB")
        }
    }

    @Test
    fun `错误判定覆盖余额不足鉴权失败与限速`() {
        assertTrue(isKeyRetryableError(Exception("insufficient_quota")))
        assertTrue(isKeyRetryableError(Exception("Insufficient Balance")))
        assertTrue(isKeyRetryableError(Exception("401 Unauthorized")))
        assertTrue(isKeyRetryableError(Exception("Too many requests")))
        assertTrue(isKeyRetryableError(Exception("Failed to get response: 402 ...")))
        assertTrue(isKeyRetryableError(Exception("Failed to get response: 429 ...")))
        assertFalse(isKeyRetryableError(Exception("Failed to get response: 500 ...")))
        assertFalse(isKeyRetryableError(Exception("Connection refused")))
        assertFalse(isKeyRetryableError(Exception("model not found")))
    }

    // ------------------------------------------------------------ v255.1：全角分隔符

    @Test
    fun `中文逗号顿号全角分号全角空格都能正确分隔多个key`() {
        // 用户真机踩坑：用中文逗号分隔 → 整串被当成一个 key →
        // OkHttp 报 "Unexpected char 0xff0c in Authorization value"
        val roulette = DefaultKeyRoulette()
        val groups = listOf(
            "keyA，keyB，keyC",   // 全角逗号
            "keyA、keyB、keyC",   // 顿号
            "keyA；keyB；keyC",   // 全角分号
            "keyA，keyB,keyC；keyD、keyE", // 混合
        )
        groups.forEach { raw ->
            // 无论怎么轮，抽出来的都必须是干净的 key，绝不能含任何分隔符字符
            repeat(30) {
                val picked = roulette.next(raw, "split-test")
                assertTrue(
                    "抽出的 key 含分隔符残留：$picked（原始输入：$raw）",
                    picked in setOf("keyA", "keyB", "keyC", "keyD", "keyE"),
                )
            }
        }
        // 单个 key 也照常工作
        assertEquals("keyA", roulette.next("keyA", "split-test"))
    }

    @Test
    fun `分隔结果去重且忽略空段`() {
        val roulette = DefaultKeyRoulette()
        // 重复 key、连续分隔符、首尾分隔符 → 只有一个候选，抽它必然命中
        repeat(20) {
            assertEquals(
                "keyA",
                roulette.next("，keyA，keyA，， keyA、", "dedup-test"),
            )
        }
    }

    @Test
    fun `重试包装在key失败后自动换下一个`() = runBlocking {
        val roulette = ScriptedRoulette().apply { queue.addAll(listOf("keyA", "keyB")) }
        var calls = 0
        val result = roulette.withKeyRetry("keyA,keyB", "p1") { key ->
            calls++
            if (key == "keyA") throw Exception("insufficient_quota")
            "ok-$key"
        }
        assertEquals("ok-keyB", result)
        assertEquals(2, calls)
        assertEquals(listOf("p1" to "keyA"), roulette.failed)
    }

    @Test
    fun `重试包装遇到不可重试错误直接抛出不重试`() = runBlocking {
        val roulette = ScriptedRoulette().apply { queue.addAll(listOf("keyA", "keyB")) }
        var calls = 0
        try {
            roulette.withKeyRetry("keyA,keyB", "p1") {
                calls++
                throw Exception("model not found")
            }
        } catch (e: Exception) {
            assertEquals("model not found", e.message)
        }
        assertEquals(1, calls)
        assertTrue(roulette.failed.isEmpty())
    }

    @Test
    fun `重试包装两次都失败时抛出最后一次错误`() = runBlocking {
        val roulette = ScriptedRoulette().apply { queue.addAll(listOf("keyA", "keyB")) }
        var calls = 0
        try {
            roulette.withKeyRetry("keyA,keyB", "p1") {
                calls++
                throw Exception("401 Unauthorized")
            }
        } catch (e: Exception) {
            assertEquals("401 Unauthorized", e.message)
        }
        assertEquals(2, calls)
        assertEquals(listOf("p1" to "keyA", "p1" to "keyB"), roulette.failed)
    }
}
