package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    /**
     * 思考（推理）花掉的 token（v297 新增）。
     *
     * 各家服务商都把它算在输出里（OpenAI 官方口径：reasoning tokens 计入 output 并按输出计费；
     * Anthropic 的 output_tokens 是含 thinking 的总量；Gemini 的 totalTokenCount 含 thoughts），
     * 所以这里**只做单列展示，不额外加到 completionTokens 上**，否则会把同一笔钱数两遍。
     * 服务商没报这个字段时保持 0（显示时整行不出现，不显示成 0 骗人）。
     * 新字段带默认值，旧聊天 JSON 仍可正常反序列化，无需数据库迁移。
     */
    val reasoningTokens: Int = 0,
)

/**
 * 「上下文水位」口径：后到的非零值覆盖前值。
 *
 * 自动压缩与输入框上方那条占用进度条依赖这个语义 —— 它要的是
 * 「下一次请求会带多大的上下文」，也就是**最后那一次**请求的输入量，不是历次之和。
 * v297 想统计真实消耗时不要动这里，改用下面的 [accumulate]。
 */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    val reasoningTokens = if (other.reasoningTokens > 0) {
        other.reasoningTokens
    } else {
        this?.reasoningTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        reasoningTokens = reasoningTokens,
    )
}

/**
 * 「真实消耗」口径（v297 新增）：同一趟生成里**每一次**请求的用量相加。
 *
 * 为什么必须另开一个口径：一次回答往往不止一次请求 —— 每调用一次工具、每自动续跑一次，
 * 都会把全量上下文重新发一遍并重新计费。旧实现只有 merge（覆盖），于是「一条回答调了 5 次工具」
 * 的消息只留下最后那次的数字，用户看到的统计比真实花费小好几倍（这就是「统计太差」的主因）。
 *
 * 与 merge 的另一处区别：本轮服务商没报用量（other 为 null 或全零）时**不加**，
 * 避免把上一轮的数重复计入。
 */
fun TokenUsage?.accumulate(other: TokenUsage?): TokenUsage {
    if (other == null || other.isEmpty()) return this ?: TokenUsage()
    if (this == null || this.isEmpty()) return other
    val promptTokens = this.promptTokens + other.promptTokens
    val completionTokens = this.completionTokens + other.completionTokens
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = this.cachedTokens + other.cachedTokens,
        reasoningTokens = this.reasoningTokens + other.reasoningTokens,
        // 服务商只报 total 不报分项时（prompt+completion 为 0）退化成两边 total 相加
        totalTokens = if (promptTokens + completionTokens > 0) {
            promptTokens + completionTokens
        } else {
            this.totalTokens + other.totalTokens
        },
    )
}

/** 服务商这一轮到底有没有报用量 —— 全零视为没报，不参与累加。 */
private fun TokenUsage.isEmpty(): Boolean =
    promptTokens == 0 && completionTokens == 0 && cachedTokens == 0 &&
            totalTokens == 0 && reasoningTokens == 0
