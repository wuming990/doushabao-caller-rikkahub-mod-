package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v297：token 统计的两个口径。
 *
 * - [merge] 是「上下文水位」：后到的非零值覆盖前值。自动压缩与输入框那条占用进度条依赖它。
 * - [accumulate] 是「真实消耗」：同一趟生成里每次请求相加。
 *
 * 旧实现只有 merge，于是「一条回答调了 5 次工具」在统计里只算最后那次，
 * 用户看到的数字比实际花费小好几倍（这就是「token 统计太差」的主因）。
 * 两个口径必须同时存在，不能把 merge 改成累加 —— 那会把上下文判定与压缩触发一起改坏。
 */
class TokenUsageAccumulateTest {

    private fun usage(
        prompt: Int = 0,
        completion: Int = 0,
        cached: Int = 0,
        total: Int = 0,
        reasoning: Int = 0,
    ) = TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        totalTokens = total,
        reasoningTokens = reasoning,
    )

    @Test
    fun `merge 仍是水位口径 不改成累加`() {
        val first = usage(prompt = 1000, completion = 100, cached = 50, reasoning = 30)
        val second = usage(prompt = 1200, completion = 150, cached = 60, reasoning = 40)
        val merged = first.merge(second)
        assertEquals(1200, merged.promptTokens)
        assertEquals(150, merged.completionTokens)
        assertEquals(60, merged.cachedTokens)
        assertEquals(40, merged.reasoningTokens)
        assertEquals(1350, merged.totalTokens)
    }

    @Test
    fun `merge 本轮没报的字段保留旧值 报了 0 不抹掉`() {
        val first = usage(prompt = 1000, completion = 100, reasoning = 30)
        val merged = first.merge(usage(prompt = 0, completion = 0))
        assertEquals(1000, merged.promptTokens)
        assertEquals(100, merged.completionTokens)
        assertEquals(30, merged.reasoningTokens)
    }

    @Test
    fun `accumulate 把同一趟生成的每次请求相加`() {
        val round1 = usage(prompt = 10_000, completion = 200, cached = 4_000, reasoning = 150)
        val round2 = usage(prompt = 10_000, completion = 300, cached = 4_000, reasoning = 250)
        val total = round1.accumulate(round2)
        assertEquals(20_000, total.promptTokens)
        assertEquals(500, total.completionTokens)
        assertEquals(8_000, total.cachedTokens)
        assertEquals(400, total.reasoningTokens)
        assertEquals(20_500, total.totalTokens)
    }

    @Test
    fun `accumulate 本轮没报用量时不重复计入`() {
        val base = usage(prompt = 10_000, completion = 200)
        val none: TokenUsage? = null
        // 服务商这一轮压根没报 usage（全零）→ 直接把上一轮的数原样传下去，不能翻倍
        assertEquals(10_000, base.accumulate(usage()).promptTokens)
        assertEquals(10_000, base.accumulate(null).promptTokens)
        // 起点为 null（本轮是这条消息的第一次请求）
        assertEquals(200, none.accumulate(usage(completion = 200)).completionTokens)
        assertEquals(0, none.accumulate(null).totalTokens)
    }

    @Test
    fun `accumulate 服务商只报 total 不报分项时不退化成零`() {
        val a = usage(total = 500)
        val b = usage(total = 700)
        assertEquals(1200, a.accumulate(b).totalTokens)
    }

    @Test
    fun `三工具轮的真实消耗是水位口径的三倍多`() {
        // 钉住这次修复要解决的场景：每轮都重发全量上下文
        var cumulative: TokenUsage? = null
        var waterline = TokenUsage()
        repeat(3) {
            val round = usage(prompt = 75_000, completion = 900, cached = 40_000, reasoning = 600)
            cumulative = cumulative.accumulate(round)
            waterline = waterline.merge(round)
        }
        assertEquals(75_000, waterline.promptTokens)      // 旧口径只剩一轮
        assertEquals(225_000, cumulative!!.promptTokens)  // 新口径才是真实花费
    }
}
