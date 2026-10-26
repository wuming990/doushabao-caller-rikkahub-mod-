package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.StreamChunk

/**
 * v1012：Claude 请求的**兼容层**。
 *
 * ## 要解决的问题
 *
 * 用户两张截图（两家不同中转站、同一个 claude-opus-5.5 系模型）：
 * · 一家回 HTTP 400「The request could not be processed. Please check the request
 *   parameters.」—— 参数被上游拒收；
 * · 一家回 `stop_reason: refusal` + 分类 `reasoning_extraction` —— 上游认为我们在索要
 *   模型内部输出。
 *
 * 两种表现，同一个处境：**我们发出去的请求形状，对面不接受**。
 *
 * ## 为什么是「逐档剥字段」而不是「查出那一个字段」
 *
 * 前三轮我们各猜过一次元凶：先猜 `thinking.display=summarized`（官方文档反过来说它是
 * 推荐做法）、再猜子代理工具入参里的 `include_reasoning`（关掉子代理后照样被拦，
 * 被一个干净的对照实验否掉）。**猜的代价是每一轮都发一个版本、用户都装一次、都没修好。**
 *
 * 所以现在不再猜：把请求里所有**可选**字段排成一个阶梯，从最全的那一档开始，
 * 被拒就剥掉一档重发，剥到哪一档真的吐出内容，就把那一档的组合记进本地。
 * 判定依据是「它成功了」，不是「我觉得是它」。
 *
 * ## 三条不能松的约束（都是讨论定稿，别再改回去）
 *
 * 1. **落盘存字段组合本身，不存"第几格"**。阶梯是算出来的派生列表，存下标等于给未来的
 *    重排留雷（同一个数字换套阶梯就指向别的东西，越界还会被夹到最退化那一档，
 *    把用户无声钉死在「什么都不发」）。[ClaudeRequestFlags] 是自描述的，重排不改语义。
 * 2. **只记实测成功过的组合**。撞一次就落盘 = 一次误判永久改坏（上下文超长、图片过大
 *    这些同样回 400 的情况会一路钉到最末档）。请求内的逐档试探只改内存变量。
 * 3. **手动档恒为单格阶梯** —— 由 [claudeVariantsFor] 保证（选了新式/老式/不发时，
 *    连缓存和采样都不许剥），不靠调用方记得判断。
 */

/** 用户在「供应商 → 编辑」里钉死的思考参数格式；默认 [AUTO] 才参与自动剥档。 */
enum class ClaudeThinkingStyle {
    /** 自动：按型号能力选，被拒就逐档剥字段（默认） */
    AUTO,

    /** 强制新式：始终 `adaptive`（+ `output_config.effort`），不剥档 */
    ADAPTIVE,

    /** 强制老式：始终 `enabled` + `budget_tokens`，不剥档 */
    LEGACY,

    /** 完全不发思考参数（最保守的兼容写法） */
    OMIT,
}

/**
 * v1012：某个 Claude 型号支持哪几种思考写法。三个开关互相独立，全部来自官方支持矩阵。
 *
 * 官方三代协议互不兼容（platform.claude.com thinking-troubleshooting 支持表）：
 * 5.x / Fable / Mythos 只认 `adaptive`（发 `enabled` 或 `disabled` 必 400）；
 * 4.5 及更早只认 `enabled`+`budget_tokens`（发 `adaptive` 必 400）；
 * 4.6 两种都收；4.7 / 4.8 只认 `adaptive` 但可 `disabled`。
 */
data class ClaudeThinkingSupport(
    val adaptive: Boolean,
    val extended: Boolean,
    val disable: Boolean,
)

/**
 * 一档请求的完整描述：**哪些可选字段被剥掉了**。
 *
 * 这就是落盘的东西 —— 一个自描述的字段组合，不是下标。
 * 顺序按「最可疑的先剥」：先是 Anthropic 最新加的那批思考相关字段（老上游、
 * 订阅账号侧的执法逻辑最容易不认识），再是顶层 `cache_control`（Anthropic 的
 * Messages API 并没有这个顶层字段，缓存断点是挂在内容块上的，严格上游会当成
 * 未知字段拒收），最后才是采样参数。
 */
@Serializable
data class ClaudeRequestFlags(
    val dropEffort: Boolean = false,
    val dropSummary: Boolean = false,
    val dropThinking: Boolean = false,
    /**
     * v1013：改发老式 `enabled` + `budget_tokens`。
     *
     * 这一档是**补回来的**，不是新增的 —— 阶梯改成"只剥字段"之后，我把 v1012 就有的
     * 「从新式退回老式」那一档弄丢了（逐类比对测试数量时才发现）。后果很具体：
     * Claude Opus 4.6 / Sonnet 4.6 这类两种写法都收的型号，如果上游不认 adaptive，
     * v1012 还能退到老式继续带思考，而"只剥"的版本只能整个不发思考 ——
     * 结果是"能用"，但比原来少了"能用且有思考"。少做就是越界。
     */
    val useLegacyBudget: Boolean = false,
    val dropCacheControl: Boolean = false,
    val dropTopP: Boolean = false,
    val dropTemperature: Boolean = false,
) {
    /** 读数里给这一档起的名字（进报错卡片的可复制区，不进人话那行） */
    fun label(): String {
        val dropped = buildList {
            if (dropEffort) add("思考强度")
            if (dropSummary) add("思考摘要")
            if (dropThinking) add("思考参数")
            // buildClaudeThinkingFields 里 dropThinking 的短路在前，这一档实际上一个 thinking
            // 字段都不发。再印「思考改发老式预算」，同一行里就等于既说思考被剥掉、又说改了老式
            // —— 两位审查位都据此判 fail：报错卡的读数在说谎。
            if (useLegacyBudget && !dropThinking) add("思考改发老式预算")
            if (dropCacheControl) add("提示词缓存")
            if (dropTopP) add("top_p")
            if (dropTemperature) add("temperature")
        }
        return if (dropped.isEmpty()) "完整形态" else "已剥：" + dropped.joinToString("、")
    }
}

/**
 * 剥档阶梯。累积式：后一档包含前一档已经剥掉的东西，所以「剥到哪一档」单调、可解释。
 *
 * 实际会试几档由 [claudeVariantsFor] 按真实请求体探测后决定 ——
 * 用户本来就没开缓存、没设 top_p 时，那几档算出来的请求体逐字节相同，
 * 去重掉才不会白撞（去重必须在**造完请求体之后**做，因为可不可用取决于本次到底带了什么）。
 */
fun claudeFlagLadder(): List<ClaudeRequestFlags> = listOf(
    ClaudeRequestFlags(),
    ClaudeRequestFlags(dropEffort = true),
    ClaudeRequestFlags(dropEffort = true, dropSummary = true),
    // 先试「改发老式预算」再试「整个不发思考」：前者还留着思考能力，代价更小
    ClaudeRequestFlags(dropEffort = true, dropSummary = true, useLegacyBudget = true),
    ClaudeRequestFlags(
        dropEffort = true, dropSummary = true, useLegacyBudget = true, dropThinking = true,
    ),
    ClaudeRequestFlags(
        dropEffort = true, dropSummary = true, useLegacyBudget = true, dropThinking = true,
        dropCacheControl = true,
    ),
    ClaudeRequestFlags(
        dropEffort = true, dropSummary = true, useLegacyBudget = true, dropThinking = true,
        dropCacheControl = true, dropTopP = true,
    ),
    ClaudeRequestFlags(
        dropEffort = true, dropSummary = true, useLegacyBudget = true, dropThinking = true,
        dropCacheControl = true, dropTopP = true, dropTemperature = true,
    ),
)

// v1013：删掉了这里的 claudeNewlyStripped。阶梯改由 claudeVariantsFor 按「真实请求体里
// 到底带了哪些字段」归一化之后，它一个调用点都没有了；留着会让下一个人以为卡片上
// 「这一档新剥了什么」是它算的（两位审查位都被它误导过一次）。要比较两档差异，
// 直接看 ClaudeRequestFlags 的字段即可。

/**
 * 按**真实请求体里到底带了哪些字段**，算出这次真的值得试的档位。
 *
 * 为什么不是「把 7 档各造一份请求体再去重」：请求体里含全部历史消息，还可能带 base64
 * 图片，一条消息造 7 份完整体是纯粹的自己找罪受（两位审查位都据此判 fail）。
 * 现在只造一份（[baseBody]），看哪些字段真的在，把「剥了等于没剥」的档位直接跳过。
 *
 * 副作用是「已到底」这个判断也变准了：本次没带的字段不占档位，
 * 所以 variants.size == 1 就真的意味着「无可再剥」。
 *
 * 手动档（[ClaudeThinkingStyle.ADAPTIVE] / [LEGACY] / [OMIT]）**恒为单档** ——
 * 用户既然钉死了格式，就不该被我们顺手把缓存和采样也剥掉。这条由这里保证，
 * 设置页那句「不参与自动剥档」才不是假话。
 */
fun claudeVariantsFor(
    baseBody: JsonObject,
    style: ClaudeThinkingStyle,
    // 「改发老式」这一档只有型号本身支持 extended 时才存在 —— 4.5 系本来就从老式起步，
    // 5.x 系发老式必 400，摆进去就是白撞。
    supportsExtended: Boolean = false,
): List<ClaudeRequestFlags> {
    if (style != ClaudeThinkingStyle.AUTO) return listOf(ClaudeRequestFlags())
    val present = HashSet<String>()
    if (baseBody["output_config"] != null) present += "output_config"
    val thinking = baseBody["thinking"] as? JsonObject
    if (thinking != null || baseBody["output_config"] != null) present += "thinking"
    if (thinking?.get("display")?.jsonPrimitive?.contentOrNull == "summarized") present += "display"
    // 缓存断点会出现在四处：顶层、system 块、tools 末项、消息块内。漏看一处就等于没剥干净。
    // 顶层那个要直接看键在不在（值本身是 {"type":"ephemeral"}，
    // 拿值的字符串去找 "cache_control" 永远找不到 —— 上一版就是这么把缓存档整个吃掉的）；
    // 挂在 system / tools / messages 里的那些，才需要在子树文本里搜。
    if (baseBody["cache_control"] != null ||
        listOf("system", "tools", "messages").any { baseBody[it].toString().contains("cache_control") }
    ) present += "cache_control"
    if (baseBody["top_p"] != null) present += "top_p"
    if (baseBody["temperature"] != null) present += "temperature"
    // 只有「正在发新式思考」且型号支持老式时，改发老式才是有意义的一档
    if (supportsExtended && (baseBody["thinking"] as? JsonObject)
            ?.get("type")?.jsonPrimitive?.contentOrNull == "adaptive"
    ) present += "legacy_budget"

    // 只对本次真的带了的字段逐格累积。
    //
    // 两种错法都被测试抓过：
    // · 「按整条阶梯去重」→ 本次没带的字段那几档算出来跟上一档一模一样，白占档位，
    //   「已到底」判断也跟着失真；
    // · 「把没带的字段强制标成已剥」→ 阶梯长度对了，但**第一档不再是"完整形态"**，
    //   于是 label() 会把用户压根没开的功能报成「已剥：提示词缓存、top_p…」，
    //   诊断读数和设置页那行「已学到的形态」全都在说谎。
    // 现在从全 false 起步，只往上加「本次真的有、而且还没剥」的字段：
    // 第一档永远是干净的完整形态，档数 = 1 + 可剥字段数，也不会有档位被没带的字段卡死。
    val strippable = listOf(
        "output_config" to ClaudeRequestFlags::dropEffort,
        "display" to ClaudeRequestFlags::dropSummary,
        "legacy_budget" to ClaudeRequestFlags::useLegacyBudget,
        "thinking" to ClaudeRequestFlags::dropThinking,
        "cache_control" to ClaudeRequestFlags::dropCacheControl,
        "top_p" to ClaudeRequestFlags::dropTopP,
        "temperature" to ClaudeRequestFlags::dropTemperature,
    ).filter { (field, _) -> field in present }

    val out = mutableListOf(ClaudeRequestFlags())
    var current = ClaudeRequestFlags()
    for ((_, flag) in strippable) {
        current = when (flag) {
            ClaudeRequestFlags::dropEffort -> current.copy(dropEffort = true)
            ClaudeRequestFlags::dropSummary -> current.copy(dropSummary = true)
            ClaudeRequestFlags::dropThinking -> current.copy(dropThinking = true)
            ClaudeRequestFlags::useLegacyBudget -> current.copy(useLegacyBudget = true)
            ClaudeRequestFlags::dropCacheControl -> current.copy(dropCacheControl = true)
            ClaudeRequestFlags::dropTopP -> current.copy(dropTopP = true)
            else -> current.copy(dropTemperature = true)
        }
        out += current
    }
    return out
}

// ─────────────────────────────────────────────────────────────
// 思考字段：按型号能力选写法
// ─────────────────────────────────────────────────────────────

/**
 * 新命名（claude-<家族>-<主>[.<次>]）。版本号一律 `(\d{1,2})(?!\d)` ——
 * 第一版没带后顾断言，把 `claude-sonnet-4-20250514` 的 8 位日期读成「次版本 20250514」，
 * 于是 4.0 被误判成 4.7 那一代、给只认老格式的型号发 adaptive，必 400。
 */
private val CLAUDE_VERSION_RE =
    Regex("""claude[-_.\s]+(?:opus|sonnet|haiku|fable|mythos)[-\s]*(\d{1,2})(?!\d)(?:[-._](\d{1,2})(?!\d))?""")

/** 老命名把版本号写在家族名前（claude-3-5-sonnet-20241022、claude-3-opus-20240229）。 */
private val CLAUDE_LEGACY_NAME_RE =
    Regex("""claude[-_.\s]*(\d{1,2})(?!\d)(?:[-._](\d{1,2})(?!\d))?[-_.\s]*(?:opus|sonnet|haiku|instant)""")

private val CLAUDE_FABLE_RE = Regex("""fable[-_\s]*(\d{1,2})(?!\d)""")
private val CLAUDE_MYTHOS_RE = Regex("""mythos[-_\s]*(\d{1,2})(?!\d)""")

/**
 * 按 modelId 判断思考写法能力。认不出来时返回 [UNKNOWN_SUPPORT]：
 * 只允许 adaptive、不允许 disabled —— 「省略 thinking」是官方允许的写法，
 * 而发一个不被支持的 `disabled` 是必定 400 的。宁可省略也不赌。
 */
fun claudeThinkingSupport(modelId: String): ClaudeThinkingSupport {
    val id = modelId.lowercase()

    // Fable / Mythos：adaptive only。Mythos Preview 那档「两种都收」按 adaptive-only
    // 处理也不会错（它接受 adaptive），只是少用一种写法。
    if (CLAUDE_FABLE_RE.containsMatchIn(id) || CLAUDE_MYTHOS_RE.containsMatchIn(id)) {
        return ADAPTIVE_ONLY
    }

    CLAUDE_VERSION_RE.find(id)?.let { match ->
        return supportForVersion(
            major = match.groupValues[1].toIntOrNull() ?: return UNKNOWN_SUPPORT,
            minor = match.groupValues[2].toIntOrNull(),
        )
    }
    CLAUDE_LEGACY_NAME_RE.find(id)?.let { match ->
        val major = match.groupValues[1].toIntOrNull() ?: return UNKNOWN_SUPPORT
        // 老命名一律是 3.x 及更早（4 开始换成 claude-opus-4-x 那种写法）。
        return if (major >= 5) ADAPTIVE_ONLY else EXTENDED_ONLY
    }
    return UNKNOWN_SUPPORT
}

private fun supportForVersion(major: Int, minor: Int?): ClaudeThinkingSupport = when {
    // 5.x：adaptive only。官方表格与 AWS 文档对「能否 disabled」说法不一致
    // （表格写拒绝、Bedrock 页写支持但 effort 有上限），按更严的处理：
    // 省略 thinking 是官方给出的等价写法，不赌。
    major >= 5 -> ADAPTIVE_ONLY

    // 4.6：两种都收（extended 已标废弃），disabled 也可用。
    major == 4 && minor == 6 ->
        ClaudeThinkingSupport(adaptive = true, extended = true, disable = true)

    // 4.7 / 4.8 及以后可能出现的 4.9+：只认 adaptive，但还能关。
    // （日期后缀已被 (?!\d) 挡在门外，不会再出现「4-20250514 当成 4.20」）
    major == 4 && minor != null && minor >= 7 ->
        ClaudeThinkingSupport(adaptive = true, extended = false, disable = true)

    // 4.0 ~ 4.5，以及「claude-opus-4-<日期>」这种被断言挡掉的形态：只认老式。
    else -> EXTENDED_ONLY
}

private val ADAPTIVE_ONLY = ClaudeThinkingSupport(adaptive = true, extended = false, disable = false)
private val EXTENDED_ONLY = ClaudeThinkingSupport(adaptive = false, extended = true, disable = true)
private val UNKNOWN_SUPPORT = ADAPTIVE_ONLY

/**
 * 按「用户选项 + 型号能力 + 思考档位 + 本档 flags」算出该塞进请求体的思考字段。
 *
 * `level == OFF` 且型号不支持关闭时**省略整个字段**，而不是发 `disabled`：
 * 官方对 Opus 5.5 写得很直接 —— 任何 effort 下发 `disabled` 都是 400。
 */
fun buildClaudeThinkingFields(
    style: ClaudeThinkingStyle,
    support: ClaudeThinkingSupport,
    level: ReasoningLevel,
    maxTokens: Int?,
    flags: ClaudeRequestFlags,
): ClaudeThinkingFields {
    val effectiveMax = maxTokens ?: DEFAULT_MAX_TOKENS
    if (flags.dropThinking) return ClaudeThinkingFields(null, null)
    // 手动档不参与剥档，也就不可能被要求改发老式；AUTO 档只在型号支持时才走这一格
    if (flags.useLegacyBudget && style == ClaudeThinkingStyle.AUTO && support.extended) {
        return legacyFields(level, effectiveMax)
    }
    return when (style) {
        ClaudeThinkingStyle.OMIT -> ClaudeThinkingFields(null, null)

        // 手动两档：尊重用户的选择，不因型号能力而擅自改写
        ClaudeThinkingStyle.ADAPTIVE -> adaptiveFields(
            level, effectiveMax,
            withDisplay = !flags.dropSummary,
            withEffort = !flags.dropEffort,
            disableAllowed = true,
        )

        ClaudeThinkingStyle.LEGACY -> legacyFields(level, effectiveMax)

        ClaudeThinkingStyle.AUTO -> when {
            support.adaptive -> adaptiveFields(
                level, effectiveMax,
                withDisplay = !flags.dropSummary,
                withEffort = !flags.dropEffort,
                disableAllowed = support.disable,
            )

            support.extended -> legacyFields(level, effectiveMax)
            else -> ClaudeThinkingFields(null, null)
        }
    }
}

/** 最终写进请求体的思考字段；两个键都可以为 null（= 整个键省略）。 */
data class ClaudeThinkingFields(
    val thinking: JsonObject?,
    val outputConfig: JsonObject?,
) {
    /** 这一档是否真的往请求里塞了思考相关字段（降档前置条件） */
    val carriesThinking: Boolean get() = thinking != null || outputConfig != null
}

private fun adaptiveFields(
    level: ReasoningLevel,
    maxTokens: Int,
    withDisplay: Boolean,
    withEffort: Boolean,
    disableAllowed: Boolean,
): ClaudeThinkingFields {
    if (level == ReasoningLevel.OFF) {
        if (!disableAllowed) return ClaudeThinkingFields(null, null)
        return ClaudeThinkingFields(
            thinking = buildJsonObject { put("type", "disabled") },
            outputConfig = null,
        )
    }
    val thinking = buildJsonObject {
        put("type", "adaptive")
        if (withDisplay) put("display", "summarized")
    }
    // AUTO 不发 effort：官方没有 auto 这个 effort 值，文档明说别把 adaptive 当 effort 传。
    val outputConfig = if (withEffort && level != ReasoningLevel.AUTO && level.effort != "none") {
        buildJsonObject { put("effort", level.effort) }
    } else {
        null
    }
    return ClaudeThinkingFields(thinking, outputConfig)
}

/**
 * 老式 `enabled` + `budget_tokens`。
 *
 * 官方硬约束：`budget_tokens` 必须 ≥1024 且 < max_tokens，违反直接 400。
 * 而 [ReasoningLevel.LOW] 的映射值是 1000、用户也可能把最大Token数调小，
 * 所以必须夹一道；夹不出合法区间就干脆不发。
 *
 * 4.6 及更早的默认就是返回摘要，所以这一档**不发 display**：少一个字段少一分被老上游
 * 拒收的风险，效果一样。
 */
private fun legacyFields(level: ReasoningLevel, maxTokens: Int): ClaudeThinkingFields {
    if (level == ReasoningLevel.OFF) {
        return ClaudeThinkingFields(
            thinking = buildJsonObject { put("type", "disabled") },
            outputConfig = null,
        )
    }
    val upper = maxTokens - 1
    if (upper < MIN_BUDGET_TOKENS) return ClaudeThinkingFields(null, null)
    val wanted = if (level == ReasoningLevel.AUTO) DEFAULT_LEGACY_BUDGET else level.budgetTokens
    val budget = wanted.coerceIn(MIN_BUDGET_TOKENS, upper)
    return ClaudeThinkingFields(
        thinking = buildJsonObject {
            put("type", "enabled")
            put("budget_tokens", budget)
        },
        outputConfig = null,
    )
}

private const val MIN_BUDGET_TOKENS = 1_024
private const val DEFAULT_LEGACY_BUDGET = 8_000

/** 与 [ClaudeProvider] 里「留空按 64000」的既有语义一致，不另立规矩。 */
private const val DEFAULT_MAX_TOKENS = 64_000

// ─────────────────────────────────────────────────────────────
// 上游回话的判读
// ─────────────────────────────────────────────────────────────

/**
 * 这条错误是不是「上游认为请求参数不合法」。
 *
 * 判定刻意宽（400 就是官方的 invalid_request_error；各家网关模板文案见真机那句），
 * **安全性靠调用方的前置条件**：只有「这一趟真的带了可剥字段」时才允许据此剥档。
 */
fun isClaudeParameterRejection(statusCode: Int?, message: String?): Boolean {
    val msg = message?.lowercase().orEmpty()
    // 顺序很要紧：这条必须排在 `statusCode == 400 return true` **前面**。
    // 第一版把它写在后面，于是对最常见的 400 一个字节都不生效 —— 测试
    // （长度类 400 不该触发剥档）把它抓红了。判据写得再对，位置错了就是零。
    // 否决必须**让位于**明确的字段名信号。原因：调用方传进来的常常是**整段响应体**，
    // 而上游会把我们的请求回显进去 —— 用户只要聊过「too long / rate limit / quota」这类词，
    // 旧写法就会把这个 400 误判成「不是形状问题」，**把整趟阶梯直接关掉**（二审 P1）。
    if (msg.isNotBlank() && STRONG_SHAPE_HINTS.none { it in msg } &&
        NOT_A_SHAPE_REJECTION_HINTS.any { it in msg }
    ) return false
    if (statusCode == 400) return true
    if (statusCode != null && statusCode != 400) return false
    if (msg.isBlank()) return false
    val rejectionHints = listOf(
        "unknown name", "unexpected", "unrecognized", "unknown field", "extra inputs",
        "not supported", "unsupported", "invalid_request_error", "invalid parameter",
        "check the request parameters", "could not be processed", "cannot find field",
        "validationexception", "field required",
    )
    return rejectionHints.any { msg.contains(it) }
}

/**
 * 这些措辞说明上游拦的是**长度/额度**这一类，不是请求形状。
 *
 * 判据来自 Anthropic 与各家网关的真实文案（"prompt is too long"、
 * "input length and `max_tokens` exceed context limit" 等）。
 */
/**
 * 只有「明确指向请求体字段」的措辞才算强信号，能否决长度/额度词。
 *
 * 三审 P3：上一版收了 "is not supported" / "unrecognized" / "extra inputs" 三个通用短语，
 * 结果被回显的正文或工具说明书里很容易出现这类英文 —— 一旦命中就把长度类 400 判成「该剥」，
 * 白烧阶梯的口子又开回来。留下的一律是**字段拒绝专用**表述。
 */
private val STRONG_SHAPE_HINTS = listOf(
    "unknown name", "cannot find field", "at 'request'", "unknown field",
    "json_invalid", "unexpected character after", "unrecognized request argument",
)

private val NOT_A_SHAPE_REJECTION_HINTS = listOf(
    "too long", "context length", "context window", "maximum context",
    "exceed context", "reduce the length", "input is too long",
    "rate limit", "quota", "insufficient", "overloaded", "timeout", "timed out",
)

/**
 * 从非流式路径的异常文本里取回 HTTP 状态码。
 * 只认我们自己拼的 `Failed to get response: 400 …` 前缀 —— 放宽成「任意三位数」
 * 会把 request id、token 数误读成状态码。
 */
private val CLAUDE_STATUS_RE = Regex("""Failed to get response:\s*(\d{3})""")

fun claudeStatusCodeFromMessage(message: String?): Int? =
    message?.let { CLAUDE_STATUS_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/**
 * 上游原文进「可以复制的报错卡片」之前的统一脱敏闸。
 *
 * 有些网关会把我们发过去的请求体整段回显在错误信息里。卡片自带复制按钮，用户一复制
 * 就可能贴到群里或 GitHub，而助手提示词、记忆正文、工作区路径都在请求体里 —— 所以
 * 只要原文里出现本次请求内容的一段，就整段换成占位符，不做局部打码（局部打码要猜
 * 哪些片段算隐私，猜漏一次就是泄露）。
 *
 * 检索范围必须是 **system + messages + tools 三处**：助手提示词在 system、工具说明书在
 * tools，只查 messages 等于把两处最主要的隐私面漏掉（这是审查位 P1 抓到的）。
 */
/** 长文本取前这么多字去比对；短于这个数的字符串值则整值比对。 */
/** 把 \uXXXX 还原成字符。解不动的原样保留，绝不因为解码失败就把整条闸跳过。 */
private val UNICODE_ESCAPE_RE = Regex("""\\u([0-9a-fA-F]{4})""")

private fun decodeClaudeUnicodeEscapes(text: String): String =
    if (!text.contains("\\u")) text else UNICODE_ESCAPE_RE.replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
        code.toChar().toString()
    }

private const val LEAK_WINDOW = 48

/**
 * 短于这么长的字符串值不参与比对。
 *
 * 边界必须写清：**短于 12 字的片段不会被这道闸抓到**。留下这个门槛是有意的取舍 ——
 * "user"、"adaptive"、"ephemeral"、工具名这类脚手架词一参与匹配，就会把
 * `Unknown name "cache_control" at 'request'` 这种**最有用**的诊断整段抹成占位符，
 * 而本轮的验收判据恰恰是「卡片读数有用」。真实的隐私本体（助手提示词、记忆正文、
 * 工具说明书）都是长文本，全部在覆盖范围内；整段原样回显另走方向一，不受这个门槛限制。
 */
private const val LEAK_MIN_VALUE = 12

/** 一条请求里最多取多少个字符串值参与比对（失败路径才跑，仍要有上限兜底）。 */
private const val LEAK_MAX_VALUES = 400

/**
 * 把 system / messages / tools 里的**字符串值**收集出来。
 *
 * 为什么比对「值」而不是比对序列化后的整棵子树：子树字符串里塞满了 { " } 和字段名这类
 * 脚手架，网关的错误文案恰恰会提到字段名（"thinking.type.enabled" is not supported），
 * 拿整棵子树做窗口匹配，要么匹配不上（真机最常见的「文案里夹了一段正文」），
 * 要么把无关词误判成泄露（把本来有用的诊断整段抹掉）。字符串值才是隐私本体。
 */
/**
 * 这些键的值是**标识符**，不是内容：工具名、id、块类型。
 *
 * 二审 P2 的实证：Anthropic 自己就有「You have entered tool names that are invalid or too
 * long. Invalid tools: my_tool」这类**专门点名工具名**的报错。把名字当隐私隐去，用户丢掉的
 * 恰好是唯一可操作的那条诊断；而名字本身不构成泄露（它是我们自己起的标识符，不是用户内容）。
 */
private val IDENTIFIER_KEYS = setOf("name", "id", "tool_call_id", "type")

private fun collectClaudePrivateStrings(
    element: JsonElement?,
    out: MutableList<String>,
    skipKeys: Set<String> = IDENTIFIER_KEYS,
) {
    if (element == null || out.size >= LEAK_MAX_VALUES) return
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key !in skipKeys) collectClaudePrivateStrings(value, out, skipKeys)
        }

        is JsonArray -> element.forEach { collectClaudePrivateStrings(it, out, skipKeys) }
        is JsonPrimitive -> if (element.isString) {
            val text = element.content
            if (text.length >= LEAK_MIN_VALUE) out += text
        }
    }
}

fun sanitizeClaudeUpstreamExcerpt(requestBody: JsonObject?, raw: String?): String? {
    val excerpt = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val subtreeTexts = mutableListOf<String>()
    val privateValues = mutableListOf<String>()
    requestBody?.let { body ->
        listOf("system", "messages", "tools").forEach { key ->
            val element = body[key] ?: return@forEach
            subtreeTexts += element.toString()
            // 标识符只在 **system / tools**（我们自己的提示词与工具说明书）里跳过：
            // 网关点名工具名的报错（Anthropic 自己就有 Invalid tools: xxx）必须留着。
            // messages 里什么都算 —— 模型转述过的 JSON、工具结果里的 name 可能就是用户数据。
            collectClaudePrivateStrings(
                element, privateValues, if (key == "messages") emptySet() else IDENTIFIER_KEYS,
            )
        }
    }
    if (subtreeTexts.isEmpty()) return excerpt
    val placeholder = "<上游原文含请求内容，已整段隐去（${excerpt.length} 字）>"
    // 有些网关（fastjson / Jackson 那一类）把非 ASCII 转义成 \uXXXX 再回显。不先还原，
    // 中文提示词与记忆正文就会**漏判成没泄露** —— 那是最坏的一种漏（闸看起来在工作）。
    val forms = listOf(excerpt, decodeClaudeUnicodeEscapes(excerpt)).distinct()
    // 方向一：上游把请求里的一整段**原样**吐回来（v1013 第一版只查了这一头）。
    if (subtreeTexts.any { text -> forms.any { text.contains(it) } }) return placeholder
    // 方向二：错误文案里**夹了**一段我们的内容 —— 真机最常见的是这种：
    //   "HTTP 400: your request was {\"system\":[{\"text\":\"<助手提示词>\"}]}"
    // 整段比对测不到它（原文两头还包着网关自己的话），所以拿请求内容去 excerpt 里找。
    // 命中就整段隐去，不做局部打码：局部打码要猜隐私边界，猜漏一次就是泄露。
    return if (privateValues.any { value ->
        val probe = if (value.length >= LEAK_WINDOW) value.take(LEAK_WINDOW) else value
        forms.any { it.contains(probe) }
    }
    ) placeholder else excerpt
}

/** Anthropic 的「安全分类器拒答」结束原因原文。 */
const val CLAUDE_STOP_REFUSAL: String = "refusal"

/** 官方分类里「怀疑你在逆向或复刻模型输出」那一档。 */
const val CLAUDE_REFUSAL_REASONING_EXTRACTION: String = "reasoning_extraction"

/**
 * 官方 2026-09-24 起「即使在产出任何内容之前被拒也照常计费」的三类。
 *
 * 记在这里是为了**说明事实**，不是为了据此少试 —— 用户口径很明确：
 * 「我有所谓的重复扣费需求我不会自己关自动续跑么」。所以这一组只用来在报错里
 * 提醒一句，不用来关掉重试通道。
 */
val BILLABLE_REFUSAL_CATEGORIES: Set<String> = setOf(
    "bio",
    "frontier_llm",
    CLAUDE_REFUSAL_REASONING_EXTRACTION,
)

/**
 * 这一类拒答，剥字段有没有可能解开。
 *
 * 官方对分类的定义分两种：
 * · `reasoning_extraction` 判的是**请求形状**（"asks the model to reproduce its internal
 *   reasoning"），我们的思考参数正是这一类里唯一可剥的东西 → 值得试；
 * · `cyber` / `bio` / `frontier_llm` / `general_harms` 判的是**内容**，剥掉缓存和采样
 *   不会让内容变得不违规 → 试了就是白烧计费请求（这一类产出前被拒也计费）。
 * · category 为 null 时按值得试处理：官方允许 null 是常态，没证据说它一定与形状无关，
 *   而阶梯最多 6 档、失败就停，代价可控。
 *
 * 这不是「替用户决定要不要重试」—— 重试次数仍然归他的自动续跑滑条管；
 * 这里只是不在**一条消息内部**明知无用还连发 6 次。
 */
/**
 * 这一趟错误值不值得**在一条消息内部**换下一档重发。
 *
 * 三审 P1 把它抽成纯函数，是为了能被测试钉住：非流式那条路以前把 looksRejected 算出来却
 * 没当闸门，于是 429 / 500 / 断网 / 自家解坏响应体都会逐档重烧一遍阶梯（最多 7 次真实请求）。
 * 拒答看分类（内容类剥了没用），其它错误必须真的像「参数被拒」。
 * 这里仍然只管**一条消息内部**的无用试探；要不要继续重试归用户的自动续跑滑条。
 */
fun claudeShouldDegradeForError(
    isRefusal: Boolean,
    worthStripping: Boolean,
    looksLikeParameterRejection: Boolean,
): Boolean = if (isRefusal) worthStripping else looksLikeParameterRejection

fun claudeRefusalWorthStripping(category: String?): Boolean = when (category) {
    "cyber", "bio", "frontier_llm", "general_harms" -> false
    else -> true
}

/**
 * 把上游 `stop_details` 折成一句能给人看的话。
 * 官方：`category` 与 `explanation` 都可能为 null（「That null is a normal, permanent
 * value」），两个都拿不到就返回 null 走兜底文案；只拼接展示，不拿它改行为。
 */
fun claudeRefusalDetail(stopDetails: JsonObject?): String? {
    if (stopDetails == null) return null
    val category = stopDetails["category"]?.jsonPrimitive?.contentOrNull
    val explanation = stopDetails["explanation"]?.jsonPrimitive?.contentOrNull
    val parts = listOfNotNull(
        category?.takeIf { it.isNotBlank() }?.let { "分类 $it" },
        explanation?.takeIf { it.isNotBlank() },
    )
    return parts.joinToString("，").ifBlank { null }
}

/** 卡片第一行的人话。用户在这一轮里说过好几次「我看不懂」，英文分类名只进可复制区。 */
fun claudeRefusalCategoryInChinese(category: String?): String = when (category) {
    CLAUDE_REFUSAL_REASONING_EXTRACTION -> "这家上游认为我们在索要它的内部思考内容，按商业条款拒绝回答"
    "cyber" -> "这家上游的网络内容安全策略拦下了这一轮"
    "bio" -> "这家上游的生物内容安全策略拦下了这一轮"
    "frontier_llm" -> "这家上游认为请求涉及开发竞争性模型，拦下了这一轮"
    "general_harms" -> "这家上游的内容安全策略拦下了这一轮"
    null -> "这家上游拒绝回答这一轮，没给出具体分类"
    else -> "这家上游以「$category」为由拒绝回答这一轮"
}

/**
 * 一次「被上游拒绝」的诊断读数。
 *
 * 只放我们自己的元信息与上游原文，**绝不放用户内容**：报错卡片自带复制按钮，
 * 用户一复制就可能贴到群里或 GitHub，而助手提示词、记忆正文、工作区路径都是隐私。
 */
data class ClaudeRejectDiagnostics(
    val httpStatus: Int?,
    val variantsTried: Int,
    val variantTotal: Int,
    val lastVariantLabel: String,
    val sentOptionalFields: List<String>,
    val upstreamExcerpt: String?,
    /**
     * 是不是真的剥到了头。默认由两个读数自己推出来，调用方漏传也不会说反话。
     *
     * 之前卡片文案无条件宣称「已经把可选字段逐个剥到只剩必需字段」，而本次只有 1 档时
     * （用户手动钉死格式，或请求本来就没带可剥字段）一个字段都没动过 —— 那是说谎。
     */
    val fullyStripped: Boolean = variantTotal > 1 && variantTotal == variantsTried,
) {
    /** 第二行：给人复制给我们看的读数 */
    fun readableLine(): String = buildString {
        append("已试 ").append(variantsTried).append('/').append(variantTotal).append(" 档")
        append("；最后一档=").append(lastVariantLabel)
        if (httpStatus != null) append("；HTTP ").append(httpStatus)
        if (sentOptionalFields.isNotEmpty()) {
            append("；本次带了 ").append(sentOptionalFields.joinToString("、"))
        }
        if (!upstreamExcerpt.isNullOrBlank()) append("；上游原文：").append(upstreamExcerpt)
    }
}

/** 上游以 refusal 收尾且一个字没吐。文案只陈述真发生过的事。 */
class ClaudeRefusalException(
    val category: String?,
    val detail: String?,
    val diagnostics: ClaudeRejectDiagnostics? = null,
    val triedOtherKeys: Boolean = false,
    /** 密钥池里有几把。非流式这条路径不换密钥，多密钥用户不能被写成「只有一把」。 */
    val keyPoolSize: Int = 1,
) : Exception(
    buildString {
        append(claudeRefusalCategoryInChinese(category))
        if (!detail.isNullOrBlank()) append("（上游原文：").append(detail).append("）")
        append("。")
        // 「剥档剥到哪」与「换没换密钥」是**两件独立的事**，必须分开说。
        // 旧写法把它们绑在同一个 when 里：一旦换过密钥，剥档那句就永远说不准（二审 P2）。
        append(
            when {
                diagnostics == null -> "本次没有剥过可选字段（这条路径没拿到现场读数）；"
                diagnostics.fullyStripped -> "可选字段已逐个剥到只剩必需字段仍被拒；"
                diagnostics.variantsTried > 1 ->
                    "已剥掉一部分可选字段（第 ${diagnostics.variantsTried}/${diagnostics.variantTotal} 档）" +
                        "仍被拒，还没剥到底；"
                else -> "本次没有剥过可选字段（没有可剥的，或手动格式已钉死）；"
            },
        )
        append(
            when {
                triedOtherKeys -> "换过其它密钥仍然被拒。"
                keyPoolSize > 1 -> "这一条请求路径没有换密钥重试，池里还有 $keyPoolSize 把可换。"
                else -> "这个供应商只有一把密钥，没有别的密钥可换。"
            },
        )
        if (category in BILLABLE_REFUSAL_CATEGORIES) {
            append("这一类官方已改为照常计费（要不要继续重试由你的自动续跑设置决定）。")
        }
        append("同样的内容重发还会被拒，需要改这一轮的措辞或换模型。")
        diagnostics?.let { append("\n").append(it.readableLine()) }
    },
)

/** 阶梯剥到底、上游仍然回「参数不合法」。 */
class ClaudeDegradeExhaustedException(
    val diagnostics: ClaudeRejectDiagnostics,
    upstreamMessage: String?,
) : Exception(
    buildString {
        // 三审 P2：判据用 fullyStripped，不用「档数 > 1」—— 万一以后有调用点带着
        // tried < total 进来，按档数判断就会又说谎。
        if (diagnostics.fullyStripped) {
            append("这家上游拒绝我们的请求参数，已经把可选字段逐个剥到只剩必需字段，")
        } else {
            append("这家上游拒绝我们的请求参数，而本次请求本来就没有可剥的可选字段，")
        }
        append("它仍包含助手提示词与工具说明书原文。")
        // 「仍包含…」这半句不能省：只写「最简形态」会被读成「请求已经空了」，
        // 从而错误地排除文本嫌疑。
        if (!upstreamMessage.isNullOrBlank()) append("上游原文：").append(upstreamMessage)
        append("\n").append(diagnostics.readableLine())
    },
)

/**
 * 这条 chunk 算不算「已经给用户吐过可见内容」。
 *
 * [StreamChunk.Finish] 是协议收尾帧、[StreamChunk.Usage] 是计量帧，屏幕上不会出现任何东西。
 * 旧代码用 `chunks.any()` 判「吐过内容」，于是「一个字没吐但收了尾」的流被误判成吐过，
 * 后果是 refusal / 上游空回这类零产出流永远不许换密钥重发。
 */
fun StreamChunk.isClaudeVisibleContent(): Boolean =
    this !is StreamChunk.Finish && this !is StreamChunk.Usage
