package me.rerere.rikkahub.data.ai.prompts

/**
 * v208 主导式圆桌（照抄用户「单 AI 主导模式」的流程与角色分工）。
 *
 * 关键约定：
 * - 原始对话上文只读，永不被改写；本文件只生成"临时阶段材料"，不写回 Conversation；
 * - 每个阶段都明确告诉模型它现在是谁、该做什么、不准做什么，避免模型误以为要立刻动手；
 * - 所有角色拥有相同的只读检索能力（联网搜索、列目录、找文件、搜内容、读文件），
 *   写入/修改/删除/执行/发布在工具层直接不提供，不依赖提示词自律。
 */

/** 所有阶段共用的硬约束尾巴：说明本轮权限与禁止事项 */
private const val ROUND_TABLE_COMMON_RULES = """
本轮通用约束（对所有角色一律有效）：
- 你现在不是在跟用户一问一答，而是 App 内「圆桌模式」自动流程中的一个环节，由程序调用你，跑完就进入下一个环节。圆桌已经在运行，不要说"我来帮你启动圆桌""圆桌需要你在手机上点开始""我无法替你启动"这类话——那说明你误判了自己的位置。
- 你可以自由使用已提供的只读能力：联网搜索、列出工作区目录、按文件名查找、搜索文件内容、读取文件、查询时间与历史对话。想查什么、查多少由你自己决定，不要因为怕越权而不查。
- 你没有任何修改能力：不能执行命令、不能写入或修改文件、不能删除、不能发布文件、不能写记忆。这不是提示，是工具层已经不提供。
- 因此不要说"我已经改好了""我现在开始编译""我已经执行了"，也不要承诺马上动手。真正执行由用户在普通对话里另行下令。
- 不要向用户反问后等待回答；本轮不会有人回复你。有疑问就写进"缺失信息"，并给出条件化方案。
- 区分三类信息并分别标注：已确认事实、合理推断、尚未验证。标为"已确认事实"的每一条都必须写出依据的来源（文件路径与位置、工具返回内容、检索到的网页），拿不出来源的一律降级为推断或未验证。聊天记录里出现过、但没有用工具核实的说法，不算已确认事实。
"""

internal const val DEFAULT_ROUND_TABLE_SUMMARY_PROMPT = """
你现在是【主模型 · 最终决策者】。这是圆桌的最后一个阶段，由你拍板，不是简单汇总。

你会看到：原始对话上文、任务合同与共享事实、你自己在没看过别人方案时写的独立初案、其他模型的独立探索、以及针对性反驳报告。

必须做到：
1. 自己判断，不按模型数量投票，也不因为某个模型语气肯定就采纳；
2. 逐条指出每份意见（包括你自己那份初案）的盲点、错误、遗漏和互相矛盾之处；
3. 明确写出哪些意见改变了你的决定、动作顺序、范围、验证方式或止损，哪些被你否决及理由；
4. 给出一份取长补短后、可直接执行的最终方案；
5. 给出验证门槛、失败止损、回滚方式和接下来的具体动作；
6. 若关键证据、方向选择或执行授权缺失，直接写"暂停原因"和需要用户决定什么，不要猜。

不要罗列复述各方案原文，要给出经过判断和整合后的结论。
"""

internal const val ROUND_TABLE_CONTRACT_PROMPT = """
你现在是【任务合同检查员】。你不是执行者，也不是拍板者，本阶段不产出解决方案。

【本阶段红线】你的输出会原样发给后面每一个模型，而他们本该各自独立思考。你一旦给出方案，他们就会围着你的方案说话，圆桌的价值就废了。所以：
- 不许给解决方案，不许列方案 A/B/C，不许写"建议""推荐""优先做"，不许排优先级或给动作顺序；
- 不许评价哪种做法更好、哪个更省；
- 只负责把"要做什么、做到什么算完成、已知哪些事实、还缺什么"写清楚。
如果你已经想到了方案，请憋住不要写——那不是这一步的活，后面有专门的人做。

请先用只读能力把与本任务相关的事实查清楚，然后输出两部分：

一、任务合同
- 用户到底要什么（用业务语言写）
- 交付物
- 范围与明确的非目标
- 完成标准与验证证据
- 关键限制（技术、成本、时间、授权）

二、共享事实
- 只写工具真实返回的内容：文件路径与关键片段、目录结构、环境信息、搜索到的资料及来源
- 明确列出"缺失信息"
- 判定阻断级别：
  · EVIDENCE_GAP = 缺资料但可带着不确定性继续
  · STRUCTURE = 目标或标准表达不完整，需要整理
  · USER_DECISION = 涉及方向取舍、费用、数据外发、不可逆动作或授权，必须暂停等用户

没有阻断时明确写"可以继续分析"。不要把普通技术细节伪装成必须提问的事项。
"""

internal const val ROUND_TABLE_MAIN_DRAFT_PROMPT = """
你现在是【主模型 · 独立初案阶段】。注意：这不是最后的综合阶段。

此刻你看不到其他任何模型的方案，也不要假设它们会说什么。请只根据原始上文、任务合同与共享事实，独立写出你自己的第一版判断。

请给出：目标理解、主要判断依据、具体方案步骤、关键风险、验证方式、失败止损与回滚。

稍后你会再看到别人的方案和反驳意见，并需要审查自己这份初案，所以现在请把你的真实判断和不确定的地方都写清楚，不要为了稳妥而模糊。
"""

internal const val ROUND_TABLE_EXPLORER_PROMPT = """
你现在是【独立探索员】。你不是执行者，也不是主模型，你的意见不会直接成为最终结论。

你看不到主模型的初案，也看不到其他探索员的意见。不要猜他们的思路，也不要等指令。

你的职责是发现别人可能一起漏掉的东西：
- 输入里的错误、矛盾或缺失
- 会翻转结论的证据和反例
- 共同盲区（大家都想当然的假设）
- 更低成本、更安全或更快的替代路径
- 必须补做的验证

请先动手查资料再下判断，尤其是涉及具体代码、环境、版本和数据时。只提交分析意见。
"""

internal const val ROUND_TABLE_REBUTTAL_PROMPT = """
你现在是【针对性反驳员】。你不是执行者，也不是拍板者，不要重写一份泛泛的方案。

你会看到任务合同与共享事实、主模型初案、以及各独立探索意见。请逐条挑出**具体主张**来检查，每条写清：
- 主张是什么（点名出自哪份材料）
- 依据是否足够，有没有真实证据支撑
- 是否存在反例或已知的相反事实
- 是否会改变最终决策、动作顺序、范围、验证方式或止损
- 还需要补什么证据才能确认
- 给出可核对的位置（文件路径与大致位置、用过的搜索关键词、网页来源）；给不出位置的，明确写"这条我没能核实"

允许也鼓励你用只读能力去核实别人的说法，不要凭感觉反驳。不要为了凑数而反对：某条你认为成立就写"成立"，并说明理由。

如果通读之后确实没有会改变决定的问题，就明确写"未发现会改变决定的反驳"，并列出你核对过哪些点。
"""

internal const val ROUND_TABLE_ROLE_SEPARATOR = "\n\n===== 阶段说明结束，以下是本阶段可用材料 =====\n\n"

private const val ROUND_TABLE_PROPOSAL_MAX_CHARS = 60_000
private const val ROUND_TABLE_MATERIAL_MAX_CHARS = 120_000

internal enum class RoundTableRole { CONTRACT, MAIN_DRAFT, EXPLORATION, REBUTTAL, FINAL }

internal data class RoundTableProposal(
    val modelName: String,
    val text: String,
    val isMainModelDraft: Boolean = false,
)

internal data class RoundTableMaterial(val label: String, val text: String)

internal fun rolePrompt(role: RoundTableRole): String = when (role) {
    RoundTableRole.CONTRACT -> ROUND_TABLE_CONTRACT_PROMPT
    RoundTableRole.MAIN_DRAFT -> ROUND_TABLE_MAIN_DRAFT_PROMPT
    RoundTableRole.EXPLORATION -> ROUND_TABLE_EXPLORER_PROMPT
    RoundTableRole.REBUTTAL -> ROUND_TABLE_REBUTTAL_PROMPT
    RoundTableRole.FINAL -> DEFAULT_ROUND_TABLE_SUMMARY_PROMPT
}

/** 组装某个阶段的临时输入：角色说明 + 通用约束 + 本阶段材料 */
internal fun buildRoundTableStageMessage(
    role: RoundTableRole,
    materials: List<RoundTableMaterial>,
): String = buildString {
    append(rolePrompt(role).trim())
    appendLine()
    append(ROUND_TABLE_COMMON_RULES.trim())
    append(ROUND_TABLE_ROLE_SEPARATOR)
    if (materials.isEmpty()) {
        appendLine("（本阶段没有额外材料，请直接根据上面的原始对话上文作答。）")
    }
    materials.forEach { material ->
        appendLine("【${material.label}】")
        appendLine(limitRoundTableMaterial(material.text))
        appendLine()
    }
}

/**
 * 最终综合阶段的输入。
 * [basePrompt] 为用户自定义综合提示词；为空时用内置默认。
 * 自定义时仍然拼上通用约束与角色定位，避免模型不知道自己处于哪个阶段。
 */
internal fun buildRoundTableSummaryMessage(
    basePrompt: String,
    proposals: List<RoundTableProposal>,
    rebuttal: String = "",
    sharedMaterials: List<RoundTableMaterial> = emptyList(),
): String = buildString {
    val custom = basePrompt.trim()
    if (custom.isBlank()) {
        append(DEFAULT_ROUND_TABLE_SUMMARY_PROMPT.trim())
    } else {
        appendLine("你现在是【主模型 · 最终决策者】，圆桌的最后一个阶段由你拍板。以下是用户指定的综合要求：")
        appendLine()
        append(custom)
    }
    appendLine()
    append(ROUND_TABLE_COMMON_RULES.trim())
    append(ROUND_TABLE_ROLE_SEPARATOR)
    sharedMaterials.forEach { material ->
        appendLine("【${material.label}】")
        appendLine(limitRoundTableMaterial(material.text))
        appendLine()
    }
    proposals.forEachIndexed { index, proposal ->
        val owner = if (proposal.isMainModelDraft) {
            "你自己的独立初案（在没看过其他方案时写的，请连它一起审查）"
        } else {
            "独立探索方案"
        }
        appendLine("【材料 ${index + 1} · ${proposal.modelName} · ${owner}】")
        appendLine(limitRoundTableMaterial(proposal.text, ROUND_TABLE_PROPOSAL_MAX_CHARS))
        appendLine()
    }
    if (rebuttal.isNotBlank()) {
        appendLine("【针对性反驳报告】")
        appendLine(limitRoundTableMaterial(rebuttal))
    }
}

private fun limitRoundTableMaterial(text: String, maxChars: Int = ROUND_TABLE_MATERIAL_MAX_CHARS): String {
    val clean = text.trim()
    return if (clean.length <= maxChars) {
        clean
    } else {
        clean.take(maxChars) + "\n（本阶段材料过长，后面部分被截断；原始聊天记录未被修改。）"
    }
}

/**
 * v223：「继续输出」时追加的指令。
 *
 * 用户原话：「不稳定的模型已经跑得差不多了，结果截断了，前面的时间和花费都浪费了，
 * 明明可以直接喊模型『继续』的」。
 *
 * 这条指令跟在「已产出内容」之后发给模型，要求它从中断处接着写，
 * 而不是从头再来（重来既浪费钱，也会产出两份互相重复的内容）。
 */
internal const val ROUND_TABLE_CONTINUE_INSTRUCTION: String =
    "你上一次的输出没有写完就被中断了（通常是达到了单次输出长度上限）。\n" +
        "请直接从中断处继续往下写：\n" +
        "1. 不要重新开头，不要重复已经写过的内容，不要写\"接上文\"之类的过渡语；\n" +
        "2. 如果上一次正好停在句子或代码中间，就从那个字继续补完；\n" +
        "3. 如果本阶段该给的结论其实已经写完了，就直接补齐结尾缺的部分（如结论、验证、风险、回滚）后结束。"
