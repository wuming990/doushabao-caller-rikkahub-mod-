package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.prompts.RoundTableRole

/**
 * v213：判断一页圆桌产出到底能不能当材料用。
 *
 * 背景：v212 只要文字不是空的就当成"有效方案"，结果出现过这些情况被送进最终拍板：
 * - 只把"你现在是【针对性反驳员】"这类角色说明复述一遍；
 * - 只说"我会先核查源码"，却没有给出任何核查结果；
 * - 只有一两句开场白。
 *
 * 这些不是空白，但也不是可用结论。这里只做**保守**判定：命中就标成"疑似无效"，
 * 页面依然保留、依然能看，用户可以手动勾选让它参与拍板；不会直接删内容。
 */
internal enum class RoundTableContentVerdict {
    /** 可以用 */
    VALID,

    /** 太短，不足以构成这个角色该给的结论 */
    TOO_SHORT,

    /** 基本是在复述角色说明或本轮约束 */
    ROLE_ECHO,

    /** 只说了"我准备去做什么"，没有给出结果 */
    INTENT_ONLY,
    ;

    val isUsable: Boolean get() = this == VALID
}

internal object RoundTableValidity {
    /** 出方案 / 反驳 / 拍板这类角色，低于这个字数几乎不可能是完整结论 */
    private const val MIN_CHARS_DEFAULT = 300

    /** 任务合同偏结构化，允许更短 */
    private const val MIN_CHARS_CONTRACT = 200

    /**
     * 判定"复述角色说明"时的上限：
     * 超过这个长度说明模型确实写了不少自己的内容，即使开头引用了角色说明也算有效。
     */
    private const val ROLE_ECHO_MAX_CHARS = 1_200

    /** 判定"只说要去做什么"时的上限，同理 */
    private const val INTENT_ONLY_MAX_CHARS = 900

    /** 只在开头这一段里找"我准备去做什么"的说法，避免正文中途的正常表述被误判 */
    private const val INTENT_SCAN_PREFIX_CHARS = 240

    /** 这些片段来自本模块的角色提示词与通用约束，正常回答不会整句照抄 */
    private val ROLE_ECHO_MARKERS = listOf(
        "你现在是【",
        "本轮通用约束",
        "阶段说明结束",
        "以下是本阶段可用材料",
        "【本阶段红线】",
    )

    /** 只宣布下一步动作、没有结论的典型开场 */
    private val INTENT_ONLY_MARKERS = listOf(
        "我将先",
        "我会先",
        "让我先",
        "我先来",
        "我准备先",
        "接下来我会",
        "接下来我将",
        "我需要先核实",
        "首先我需要",
        "让我开始",
        "i will first",
        "let me first",
        "i'll start by",
    )

    /** 出现这些说明模型已经在给结论，不再按"只宣布动作"处理 */
    private val CONCLUSION_MARKERS = listOf(
        "已确认事实",
        "合理推断",
        "尚未验证",
        "缺失信息",
        "任务合同",
        "结论",
        "建议",
        "风险",
        "验证",
        "止损",
        "回滚",
        "步骤",
        "方案",
        "成立",
        "未发现会改变决定的反驳",
    )

    fun evaluate(role: RoundTableRole, rawText: String): RoundTableContentVerdict {
        val text = rawText.trim()
        if (text.isEmpty()) return RoundTableContentVerdict.TOO_SHORT

        // 先看"是不是根本没在回答"，再看长度：
        // 复述角色说明、只宣布下一步动作，即使篇幅不短也一样不能当材料。
        if (text.length <= ROLE_ECHO_MAX_CHARS && ROLE_ECHO_MARKERS.any { text.contains(it) }) {
            return RoundTableContentVerdict.ROLE_ECHO
        }

        if (text.length <= INTENT_ONLY_MAX_CHARS) {
            val prefix = text.take(INTENT_SCAN_PREFIX_CHARS).lowercase()
            val looksLikeIntent = INTENT_ONLY_MARKERS.any { prefix.contains(it.lowercase()) }
            val hasConclusion = CONCLUSION_MARKERS.any { text.contains(it) }
            if (looksLikeIntent && !hasConclusion) return RoundTableContentVerdict.INTENT_ONLY
        }

        val minChars = if (role == RoundTableRole.CONTRACT) MIN_CHARS_CONTRACT else MIN_CHARS_DEFAULT
        if (text.length < minChars) return RoundTableContentVerdict.TOO_SHORT

        return RoundTableContentVerdict.VALID
    }
}
