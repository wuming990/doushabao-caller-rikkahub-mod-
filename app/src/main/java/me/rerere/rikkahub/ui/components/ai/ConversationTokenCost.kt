package me.rerere.rikkahub.ui.components.ai

import java.util.Locale

/**
 * v301：按用户自己填的单价，把「本对话 token 明细」里的花费估出来。
 *
 * 为什么是「估算」而不是「结算」：
 * - 单价由用户在中转站/官网看到的价格自己填（每 100 万 token 多少钱），各家不同、还会变；
 * - 服务商报的 token 数本身可能有偏差（缓存口径、思考是否计入等各家不一样）；
 * - 这里**只算这个对话里 AI 回答的消耗**，不含起标题/生成建议/压缩/翻译等后台调用，
 *   也不含子代理的消耗 —— 界面必须如实标注，不能让用户拿这个数去跟账单对质。
 *
 * 所以这里全部是纯函数：输入「按模型分组的用量」+「单价查询」，输出一个数。
 * 真单测见 ConversationTokenCostTest（不读源码字符串，直接喂数据）。
 */

/** 一个模型的单价（每 100 万 token）。null = 用户没填这一项。 */
data class ModelPrice(
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    val cachedPerMillion: Double? = null,
) {
    /** 输入价与输出价都没填 = 这个模型没法定价（只有缓存价没有意义）。 */
    val isUsable: Boolean get() = inputPerMillion != null || outputPerMillion != null
}

/** 按「生成这条回答的模型」分组后的一段用量。 */
data class ModelUsageSlice(
    val modelId: String? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val messageCount: Int = 0,
)

/**
 * 算钱结果。
 *
 * @param cost 估算花费（货币单位由用户填单价时决定，界面不追加符号）
 * @param pricedMessages 计入价格的消息条数
 * @param unpricedMessages 有用量、但模型没填价格（或模型已删）而**没被计入**的消息条数
 * @param pricedModels 参与计算的模型个数
 */
data class ConversationCost(
    val cost: Double = 0.0,
    val pricedMessages: Int = 0,
    val unpricedMessages: Int = 0,
    val pricedModels: Int = 0,
) {
    /** 一条有用量的消息都没定上价 —— 界面不该显示「≈ 0」骗人。 */
    val hasAnyPrice: Boolean get() = pricedMessages > 0
}

/**
 * 按模型单价算总花费。
 *
 * 公式（每个模型分别算再相加）：
 * ```
 * (输入token − 缓存token) × 输入价 + 缓存token × 缓存价 + 输出token × 输出价
 * ───────────────────────────────────────────────────────────────  （单价按每百万 token）
 *                          1,000,000
 * ```
 * 几条刻意定下的口径（都是为了不把数算得比真实更小或更大）：
 * - **缓存价留空 → 按输入价算**。缓存那部分 token 现实里不是免费的；按 0 算会明显少报。
 *   想按 0 算就自己填 0。
 * - 只填了其中一项价（比如只填输出价）时，另一项按 0 算 —— 这是用户的显式选择，不替他猜。
 * - 缓存 token 数超过输入 token 数时按输入数封顶（服务商口径不一致时不至于算出负数）。
 * - 模型没填价 / 模型已删 → 这一段**完全不计入**，只累加「未计入条数」，不用 0 冒充。
 */
fun computeConversationCost(
    slices: List<ModelUsageSlice>,
    priceOf: (String?) -> ModelPrice?,
): ConversationCost {
    var cost = 0.0
    var pricedMessages = 0
    var unpricedMessages = 0
    var pricedModels = 0

    slices.forEach { slice ->
        val price = priceOf(slice.modelId)
        if (price == null || !price.isUsable) {
            unpricedMessages += slice.messageCount.coerceAtLeast(0)
            return@forEach
        }
        val prompt = slice.promptTokens.coerceAtLeast(0L)
        val completion = slice.completionTokens.coerceAtLeast(0L)
        val cached = slice.cachedTokens.coerceIn(0L, prompt)
        val plainInput = prompt - cached

        val inputPrice = price.inputPerMillion ?: 0.0
        val outputPrice = price.outputPerMillion ?: 0.0
        val cachedPrice = price.cachedPerMillion ?: inputPrice

        cost += plainInput / 1_000_000.0 * inputPrice
        cost += cached / 1_000_000.0 * cachedPrice
        cost += completion / 1_000_000.0 * outputPrice
        pricedMessages += slice.messageCount.coerceAtLeast(0)
        pricedModels++
    }

    return ConversationCost(
        cost = cost,
        pricedMessages = pricedMessages,
        unpricedMessages = unpricedMessages,
        pricedModels = pricedModels,
    )
}

/**
 * 花费显示：金额一般很小（一次对话几分钱），所以按量级决定小数位，并去掉无意义的尾零。
 * 用 [Locale.US] 固定小数点 —— 否则在「逗号当小数点」的语言下会显示成 1,23，测试也会飘。
 */
fun formatCost(value: Double): String {
    if (value.isNaN() || value <= 0.0) return "0"
    val text = when {
        value < 0.0001 -> String.format(Locale.US, "%.6f", value)
        value < 1.0 -> String.format(Locale.US, "%.4f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
    val trimmed = text.trimEnd('0').trimEnd('.')
    return trimmed.ifEmpty { "0" }
}
