package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v301：按自填单价算花费的真单测（直接喂数据，不读源码字符串）。
 *
 * 这是「钱」的功能，口径必须钉死：
 * - 单价 = 每 100 万 token 的价格
 * - 缓存价留空 → 按输入价算（不然缓存那部分等于白送，会明显少报）
 * - 模型没填价 → 完全不计入，只累加「未计入条数」，绝不用 0 冒充
 */
class ConversationTokenCostTest {

    private val delta = 1e-9

    private fun slice(
        modelId: String? = "m1",
        prompt: Long = 0,
        completion: Long = 0,
        cached: Long = 0,
        messages: Int = 1,
    ) = ModelUsageSlice(
        modelId = modelId,
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        messageCount = messages,
    )

    @Test
    fun `输入与输出分别按各自单价算_每百万`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 1_000_000, completion = 1_000_000)),
            priceOf = { ModelPrice(inputPerMillion = 10.0, outputPerMillion = 30.0) },
        )
        assertEquals(40.0, result.cost, delta)
        assertEquals(1, result.pricedMessages)
        assertEquals(0, result.unpricedMessages)
        assertTrue(result.hasAnyPrice)
    }

    @Test
    fun `缓存部分按缓存价算_输入部分要扣掉缓存`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 2_000_000, cached = 1_000_000)),
            priceOf = { ModelPrice(inputPerMillion = 10.0, outputPerMillion = 30.0, cachedPerMillion = 2.0) },
        )
        // 未命中输入 1M×10 = 10，缓存 1M×2 = 2
        assertEquals(12.0, result.cost, delta)
    }

    @Test
    fun `缓存价留空时按输入价算_不能当免费`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 2_000_000, cached = 1_000_000)),
            priceOf = { ModelPrice(inputPerMillion = 10.0, outputPerMillion = 30.0) },
        )
        // 1M×10 + 1M×10 = 20（若错误地把缓存按 0 算，会得到 10）
        assertEquals(20.0, result.cost, delta)
    }

    @Test
    fun `模型没填价_整段不计入并如实报条数`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 5_000_000, completion = 5_000_000, messages = 3)),
            priceOf = { null },
        )
        assertEquals(0.0, result.cost, delta)
        assertEquals(0, result.pricedMessages)
        assertEquals(3, result.unpricedMessages)
        assertFalse("一条都没定价时不能显示 ≈0", result.hasAnyPrice)
    }

    @Test
    fun `只填了输出价_输入按0算_但模型仍算已定价`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 1_000_000, completion = 1_000_000)),
            priceOf = { ModelPrice(outputPerMillion = 30.0) },
        )
        assertEquals(30.0, result.cost, delta)
        assertTrue(result.hasAnyPrice)
    }

    @Test
    fun `只填了缓存价_不算有效定价_整段不计入`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 1_000_000, cached = 500_000, messages = 2)),
            priceOf = { ModelPrice(cachedPerMillion = 1.0) },
        )
        assertEquals(0.0, result.cost, delta)
        assertEquals(2, result.unpricedMessages)
        assertFalse(result.hasAnyPrice)
    }

    @Test
    fun `缓存数超过输入数时封顶_不会算出负数`() {
        val result = computeConversationCost(
            slices = listOf(slice(prompt = 100, cached = 500)),
            priceOf = { ModelPrice(inputPerMillion = 2.0, outputPerMillion = 0.0, cachedPerMillion = 4.0) },
        )
        // 缓存按输入数封顶 → 100/1e6×4 = 0.0004
        assertEquals(0.0004, result.cost, delta)
    }

    @Test
    fun `多模型分组_各按自己单价相加`() {
        val result = computeConversationCost(
            slices = listOf(
                slice(modelId = "a", prompt = 1_000_000, completion = 1_000_000),
                slice(modelId = "b", prompt = 1_000_000),
            ),
            priceOf = { id ->
                when (id) {
                    "a" -> ModelPrice(inputPerMillion = 10.0, outputPerMillion = 30.0)
                    "b" -> ModelPrice(inputPerMillion = 5.0, outputPerMillion = 5.0)
                    else -> null
                }
            },
        )
        assertEquals(45.0, result.cost, delta)
        assertEquals(2, result.pricedModels)
        assertEquals(2, result.pricedMessages)
    }

    @Test
    fun `部分模型有价部分没有_价格算对的_未计入条数也报对`() {
        val result = computeConversationCost(
            slices = listOf(
                slice(modelId = "a", prompt = 1_000_000, messages = 1),
                slice(modelId = "deleted", prompt = 9_000_000, messages = 4),
            ),
            priceOf = { id -> if (id == "a") ModelPrice(inputPerMillion = 10.0) else null },
        )
        assertEquals(10.0, result.cost, delta)
        assertEquals(1, result.pricedMessages)
        assertEquals(4, result.unpricedMessages)
    }

    @Test
    fun `空用量_啥都没有`() {
        val result = computeConversationCost(slices = emptyList(), priceOf = { ModelPrice(1.0, 1.0) })
        assertEquals(0.0, result.cost, delta)
        assertFalse(result.hasAnyPrice)
        assertEquals(0, result.unpricedMessages)
    }

    @Test
    fun `花费显示_按量级取小数并去掉无聊的尾零`() {
        assertEquals("0", formatCost(0.0))
        assertEquals("0", formatCost(-1.0))
        assertEquals("0.5", formatCost(0.5))
        assertEquals("1.23", formatCost(1.234))
        assertEquals("12", formatCost(12.0))
        assertEquals("0.00005", formatCost(0.00005))
    }
}
