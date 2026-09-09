package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel
import java.util.Collections

/**
 * 思考档位的兼容兜底（v219 泛化版）。
 *
 * 原「MAX 智能兼容」只覆盖 MAX 一个档位；这里推广到**所有思考档位**：
 * 一旦服务端/中转站不认当前档位（报「Thinking level xxx is not supported」之类的参数错误），
 * 自动沿档位链向下找到该模型**可用的最高档位**重发，最终用户拿到的是正常回答而不是报错。
 *
 * 关键设计（沿用历史 MAX 兜底的成熟原理）：
 * - 请求前查黑名单：已知该模型不支持的档位直接跳过，直达可用最高档（不白花一次注定被拒的请求）；
 * - 请求被拒后记录档位进黑名单（进程内有效、不落盘），并沿链取下档重发；
 * - 黑名单让「再次遇到同样档位」时直接跳级，无需逐级试探；
 * - 只识别「思考档位参数非法」类错误，限流/超时/余额等绝不触发降级（宁可漏判不要误判）；
 * - 流式场景只有「还没吐出任何内容」时才允许重发，避免半截回答重复；
 * - 链有尽头（最低到关闭思考），不会无限重试。
 *
 * 实现位置见 [ReasoningFallbackProvider]，它包在 provider 外层，不侵入官方请求构造代码。
 */
internal fun TextGenerationParams.isMaxReasoningRequested(): Boolean =
    reasoningLevel == ReasoningLevel.MAX

/** 降到官方本来就有的「超高」档。 */
internal fun TextGenerationParams.downgradeMaxToXHigh(): TextGenerationParams =
    if (isMaxReasoningRequested()) copy(reasoningLevel = ReasoningLevel.XHIGH) else this

/** 思考档位降级链：从高到低。最低档是「关闭思考」，通常所有模型都接受。 */
internal val REASONING_DOWNGRADE_CHAIN: List<ReasoningLevel> = listOf(
    ReasoningLevel.MAX,
    ReasoningLevel.XHIGH,
    ReasoningLevel.HIGH,
    ReasoningLevel.MEDIUM,
    ReasoningLevel.LOW,
    ReasoningLevel.AUTO,
    ReasoningLevel.OFF,
)

/**
 * 请求前应用黑名单：跳到该模型「可用的最高档位」。
 *
 * 原则（沿用「越高越容易被服务端拒绝」的经验）：
 * - 取黑名单中该模型**最高的被拒档位**；
 * - 若请求档位 ≥ 该最高被拒档位 → 直接降到「最高被拒档位之下第一个未被拒的档位」，
 *   跳过注定被拒的请求（含比被拒档位更高的未知档位）；
 * - 若请求档位 < 最高被拒档位 → 请求档位更温和，原样返回（乐观）。
 */
internal fun TextGenerationParams.withEffectiveReasoningLevel(): TextGenerationParams {
    val requested = reasoningLevel
    val highestDenied = REASONING_DOWNGRADE_CHAIN
        .firstOrNull { MaxEffortDenyList.isDenied(model.modelId, it) }
        ?: return this
    if (requested.ordinal < highestDenied.ordinal) return this
    val usable = nextUsableReasoningLevel(highestDenied) ?: return this
    return copy(reasoningLevel = usable)
}

/**
 * 被拒后取重试档位：链上「被拒档位之后第一个未被拒绝的档位」。
 * 返回 null 表示链已到底（连关闭思考都被拒），此时应放弃并抛原始错误。
 */
internal fun TextGenerationParams.nextUsableReasoningLevel(denied: ReasoningLevel): ReasoningLevel? =
    REASONING_DOWNGRADE_CHAIN
        .dropWhile { it != denied }
        .drop(1)
        .firstOrNull { !MaxEffortDenyList.isDenied(model.modelId, it) }

/**
 * 判断错误是否属于「思考档位参数不被接受」。
 * 兼容旧名：MAX 时代叫 isMaxEffortRejection，语义相同（错误指向思考档位参数）。
 */
internal fun isReasoningRejection(error: Throwable): Boolean = isMaxEffortRejection(error)

/** 该模型已知不吃 max 时提前降档，省掉一次注定被拒的请求。 */
internal fun TextGenerationParams.downgradeIfMaxDenied(): TextGenerationParams =
    if (isMaxReasoningRequested() && MaxEffortDenyList.isDenied(model.modelId)) {
        downgradeMaxToXHigh()
    } else {
        this
    }

/**
 * 运行期「这个模型不吃某思考档位」黑名单（v219 泛化版）。
 *
 * - 旧语义（MAX 时代）：deny(modelId) / isDenied(modelId) 表示「该模型不吃 MAX」，
 *   为兼容旧调用与旧测试保留，内部等价于记录 MAX 档被拒；
 * - 新语义：deny(modelId, level) / isDenied(modelId, level) 精确记录被拒的档位，
 *   请求前可据此跳级直达可用最高档；
 * - 只在真实请求被服务端以「参数非法」之类的理由拒绝后写入，进程内有效、不落盘：
 *   重启 App 会重新乐观尝试一次，避免服务端后来支持了该档位却被永久拉黑。
 */
object MaxEffortDenyList {

    private val denied: MutableMap<String, MutableSet<ReasoningLevel>> = java.util.concurrent.ConcurrentHashMap()

    // ---- 旧语义（兼容）：整个模型被视为「不吃 MAX」 ----

    fun isDenied(modelId: String): Boolean =
        denied[normalizeModelId(modelId)]?.contains(ReasoningLevel.MAX) == true

    fun deny(modelId: String) {
        deny(modelId, ReasoningLevel.MAX)
    }

    // ---- 新语义：按档位记录 ----

    fun isDenied(modelId: String, level: ReasoningLevel): Boolean =
        denied[normalizeModelId(modelId)]?.contains(level) == true

    fun isDeniedAny(modelId: String): Boolean =
        denied[normalizeModelId(modelId)]?.isNotEmpty() == true

    fun deny(modelId: String, level: ReasoningLevel) {
        denied.getOrPut(normalizeModelId(modelId)) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            .add(level)
    }

    /** 仅供测试与「重试上一条」之类的显式重置使用。 */
    fun clear() {
        denied.clear()
    }
}

/** 明显与 max 档位无关、不应触发降档重试的错误。 */
private val UNRELATED_ERROR_HINTS = listOf(
    "max_tokens", "maxtokens", "max tokens", "max_completion_tokens",
    "context length", "context_length", "context window", "too long",
    "rate limit", "rate_limit", "quota", "insufficient", "balance",
    "too many requests", "overloaded", "timeout", "timed out",
)

/** 看起来像「参数值不合法」的措辞。 */
private val INVALID_VALUE_HINTS = listOf(
    "invalid", "unsupported", "not supported", "not support", "unrecognized",
    "unexpected", "must be one of", "expected one of", "one of the following",
    "not allowed", "not permitted", "enum", "bad request", "illegal",
)

/** 出现这些字样说明错误确实指向思考档位这个参数。 */
private val EFFORT_FIELD_HINTS = listOf(
    "reasoning_effort", "reasoning.effort", "reasoningeffort",
    "effort", "output_config", "thinking_level", "thinkinglevel",
    "thinking", "reasoning",
)

private val MAX_TOKEN_REGEX = Regex("""(^|[^a-z0-9_])max([^a-z0-9_]|$)""")

/**
 * 判断一个错误是否是「服务端不认 max 这个档位」。
 *
 * 判定要求同时满足：不是明显无关的错误、措辞像参数非法、且确实提到了思考档位参数或 max 这个值。
 * 宁可漏判（用户看到原始报错）也不要误判（把限流之类的错误当成降档信号，白白多花一次请求）。
 */
internal fun isMaxEffortRejection(error: Throwable): Boolean {
    val message = buildString {
        append(error.message ?: "")
        append(' ')
        append(error.cause?.message ?: "")
    }.lowercase().trim()
    if (message.isBlank()) return false
    if (UNRELATED_ERROR_HINTS.any { it in message }) return false
    if (INVALID_VALUE_HINTS.none { it in message }) return false
    return EFFORT_FIELD_HINTS.any { it in message } || MAX_TOKEN_REGEX.containsMatchIn(message)
}

private fun normalizeModelId(modelId: String): String = modelId
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

/** 商汤网关允许的单次输出上限（官方文档实测 `max_tokens ∈ [1, 65536]`）。 */
internal const val SENSENOVA_MAX_OUTPUT_TOKENS = 65_536

/**
 * 商汤网关上 deepseek 的输出配额兜底（v297）——真机空回的真正止血点。
 *
 * App 的「输出上限」默认留空（`Assistant.maxTokens = null`），留空时我们一个配额字段
 * 都不发、由网关按自己的小默认值执行；商汤官方文档明确写了这份配额**含 reasoning**
 * （原文：最大输出 tokens（含 reasoning））。deepseek 一轮思考动辄 8K+，
 * 配额被思考吃满 → finish_reason=length → 正文零 = 用户看到的「输出完了但一个字没有」。
 *
 * 用户口径（v297 拍板）：**不映射思考档位**（真机实测 kimi-k3 的 MAX 比 high 思考更久，
 * 说明商汤认更高档位，钳到 high 反而砍掉用户要的能力），只单独给 deepseek 兜一份输出预算。
 * 因此这里只在「商汤 + deepseek + 思考开着 + 用户没填过上限」四条同时成立时给满额，
 * 其余一律返回 null（= 行为与改前一字不差，不新增任何限制）。
 */
internal fun sensenovaDeepSeekOutputBudget(
    host: String,
    modelId: String,
    userMaxTokens: Int?,
    reasoningEnabled: Boolean,
): Int? {
    if (host != "token.sensenova.cn") return null
    if ("deepseek" !in modelId.lowercase()) return null
    if (userMaxTokens != null) return null
    if (!reasoningEnabled) return null
    return SENSENOVA_MAX_OUTPUT_TOKENS
}
