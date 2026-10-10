package me.rerere.rikkahub.data.ai.prompts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.parseAskUserQuestions
import me.rerere.rikkahub.data.model.GroupSpeakerMode
import kotlin.uuid.Uuid

/**
 * v313：AI 讨论群组的提示词与上文整形（纯函数，不依赖 Android，可直接单测）。
 *
 * ## 必须解决的一个核心问题
 *
 * 群成员的发言都存成 ASSISTANT 消息（这样气泡、导出、署名都能复用现成聊天页）。
 * 但如果把整段历史**原样**发给下一个成员，它会看到一堆"自己说过的话"，
 * 分不清哪些是别人说的 —— 讨论立刻退化成自说自话。
 *
 * 做法照 SillyTavern 群聊的成熟方案：**别人的发言转成带署名的 USER 消息**，
 * 只有"当前开口的这个成员"自己以前的发言保持 ASSISTANT 角色。
 * 参考其文档原文：给群成员与历史人格的消息加名字前缀（Groups and Past Personas）。
 *
 * ## 与圆桌的区别
 *
 * 圆桌是「各自盲写 → 一次性拍板」，成员之间不许互相回应，也明确禁止反问用户；
 * 群组是「共享时间线、互相接话、缺条件就问用户」，所以这里反而要**主动**告诉成员：
 * 你可以调用提问工具。
 */

/** 成员发言的最大长度：超过就截断，避免整段历史把上下文挤爆（圆桌同款保护） */
private const val GROUP_UTTERANCE_MAX_CHARS = 20_000

// v319：原来这里写死「整份上文 12 万字符预算」，超出就从最旧的发言开始丢。
// 用户口径：「我要的是打开时候获取全部上文」—— 真机反馈十几万字的原对话开群后只剩 3 万，
// 被砍掉的正是「之前干活查到的资料」（工具调用与子代理产出）。
// 所以整份上限挪进 GroupChatConfig.transcriptCharLimit（默认 0 = 不限，界面可调，
// 留给「某个模型窗口装不下、服务商报错」时自救），这个写死值删掉。

/**
 * v318：ask_user 的工具名。
 *
 * 与 service 层 GroupChatRun.kt 里的 GROUP_ASK_USER_TOOL_NAME 是同一个值。
 * 这里刻意不 import 那个常量：提示词层不该反向依赖 service 层，
 * 一致性由 GroupChatWiringGuardTest 的「两处 ask_user 工具名必须一致」钉住。
 */
private const val ASK_USER_TOOL_NAME = "ask_user"

/** 一轮「提问 + 回答」摘要的最大长度（防止某条超长回答把整份上文挤爆） */
private const val GROUP_ASK_DIGEST_MAX_CHARS = 3_000

/**
 * v318：「用户跳过提问」的识别串。
 *
 * 跳过时写进答案的是一段**写给提问者的指令**（GROUP_SKIP_ANSWER / 卡片跳过按钮的 note），
 * 不是用户原话。原样摊给其他成员，它们会把「不要重复问同样的问题」当成用户说过这话。
 * 这里只认这段指令共有的半句，不 import service 层的常量（避免提示词层反向依赖服务层）。
 */
private const val GROUP_ASK_SKIP_MARKER = "跳过了本次询问"

/**
 * 把存储的消息列表整形成"某个成员眼中的讨论记录"。
 *
 * @param speakingModelId 当前要开口的那个成员
 * @param nameOf 模型 id → 显示名（由调用方提供，本函数不查设置，保持可单测）
 */
fun buildGroupTranscript(
    messages: List<UIMessage>,
    speakingModelId: Uuid,
    nameOf: (Uuid?) -> String?,
    /**
     * v319：整份上文的字符上限，**0 = 不限**（默认）。
     *
     * 用户口径：「我要的是打开时候获取全部上文」。之前默认 12 万字硬砍，
     * 把「之前干活查到的资料」砍没了（真机：十几万字只剩 3 万）。
     * 保留这个参数是「模型窗口装不下时」的自救档，由群组设置里那一栏填。
     */
    charLimit: Int = 0,
    /**
     * v321：「群讨论只保留发言正文」开关（用户 v321 要求新增）。
     *
     * 打开后，**群讨论期间产生的**思考过程、工具调用与结果、服务商侧搜索结果
     * 都不再塞进成员的上文 —— 每位成员只看得到大家说过的正文。
     *
     * 三条边界，一条都不能越（用户原话为准）：
     * ① 只管「开群之后群里聊出来的」东西。**开群时带进来的原对话上文照旧整份带**
     *    （v319 的「我要的是打开时候获取全部上文」一个字不动）。
     *    用户补充口径：「我说的是打开群聊进行聊天时候需要附带全部上文，
     *    但是你打开群聊使用过程中可以不用带那么多杂乱的额外上文，
     *    我说的这个上文是群聊过程中产生的上文，不是打开群聊之前的上文。」
     * ② ask_user 的提问与用户回答**保留** —— 那是用户本人说的话，
     *    摘掉会让下一个成员当场否认用户给过的条件（v318 真机坑，不踩第二遍）。
     * ③ 成员生成的图片 / 文件 / 音视频**保留** —— 那算模型的实际输出，
     *    不属于「杂乱的调查过程」，而且库里存的是路径不是原文。
     *
     * 默认 false = 行为与 v320 逐字节一致。
     */
    cleanGroupUtterances: Boolean = false,
): List<UIMessage> {
    val mapped = messages.map { message ->
        when (message.role) {
            // 用户的话原样保留 —— 这是群成员共同的需求来源
            MessageRole.USER -> message

            MessageRole.SYSTEM -> message

            // 工具结果消息：不带进上文。那是某个成员与服务商之间的协议内容，
            // 换给另一个模型只会让它以为"我调过一个并不存在的工具"。
            MessageRole.TOOL -> null

            MessageRole.ASSISTANT -> when {
                // 自己以前的发言：保持助手角色，模型才知道"我之前说过这个"
                // v321：开关打开时角色不变、正文照留，但把「**在群里**说的那几次」当时想的
                // 过程与查回的原文摘掉 —— 那才是用户要清的「群聊过程中产生的上文」。
                // 为什么必须再加 fromGroupChat 这条线：成员模型完全可能和主对话当前模型
                // 是同一个（v319 注释里写死过这个坑），只按 modelId 判会把**开群之前**
                // 主对话自己的经历一起摘掉，那是用户 v319 要的「全部上文」，动不得。
                message.modelId == speakingModelId ->
                    if (cleanGroupUtterances && message.fromGroupChat) {
                        message.onlyGroupUtteranceText()
                    } else {
                        message
                    }

                else -> {
                    val text = groupUtteranceText(message)
                    // v318：他人发言里的「向用户提的问题 + 用户的回答」必须带进上文。
                    // 成员提问时那条消息常常只有提问卡片、没有正文，用户的回答也存在卡片内部
                    // （approvalState），旧实现只看正文文字 → 整条被丢掉，下一位成员既不知道
                    // 有人问过、也不知道用户答过，会当场否认用户给过的条件（真机实锤）。
                    val askDigest = groupAskUserDigest(message)
                    // v319：**只有「开群之前的历史」才带工具调用与结果**。
                    // 用户口径：「打开时候获取全部上文」+「群组聊天过程中每个 ai 的搜索和工具
                    // 上文不会参杂进群组聊天中，只有给出的方案/对话内容」——
                    // 两句话合起来正好按 fromGroupChat 这条线分开：
                    //   fromGroupChat = false（开群前的原对话）→ 干活产出整份带进来
                    //   fromGroupChat = true （群里别人说的）  → 仍然只带正文，防幻觉那条设计不动
                    val toolDigest =
                        if (message.fromGroupChat) null else groupToolDigest(message)
                    if (text.isNullOrBlank() && askDigest == null && toolDigest == null) {
                        // 没有正文（只有工具调用/图片）的他人发言不进上文，
                        // 否则会出现一条只有署名的空消息，模型会照着空话接。
                        // v319：带上了工具产出的那条不再算「空发言」，所以判定里加了 toolDigest。
                        null
                    } else {
                        val speakerName = nameOf(message.modelId) ?: "其他成员"
                        val body = buildString {
                            if (!text.isNullOrBlank()) append(text)
                            if (askDigest != null) {
                                if (isNotEmpty()) append('\n')
                                append(askDigest)
                            }
                            if (toolDigest != null) {
                                if (isNotEmpty()) append('\n')
                                append(toolDigest)
                            }
                        }
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text("[$speakerName 说]：$body")
                            ),
                        )
                    }
                }
            }
        }
    }.filterNotNull()

    return trimGroupTranscript(mapped, charLimit)
}

/**
 * 取一条发言可用作上文的正文。
 *
 * 他人消息里的工具调用**刻意不带进上文**：那是别人与服务商之间的协议内容，
 * 塞进当前模型的上文只会制造"我调过一个并不存在的工具"的幻觉。
 * **唯一的例外是 ask_user**（见 [groupAskUserDigest]）：那是用户本人给的信息，
 * 不带进上文就等于把用户的话从讨论里抹掉。
 */
private fun groupUtteranceText(message: UIMessage): String? {
    val text = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (text.isEmpty()) return null
    return if (text.length > GROUP_UTTERANCE_MAX_CHARS) {
        text.take(GROUP_UTTERANCE_MAX_CHARS) + "\n（该发言过长，后面部分未纳入上文）"
    } else {
        text
    }
}

/**
 * v319：把一条**历史**发言里的工具调用与结果，翻成群成员读得懂的叙述。
 *
 * 只用于「开群之前的原对话」（fromGroupChat = false）—— 用户要求「打开时候获取全部上文」，
 * 而之前干活查到的资料恰恰全藏在这些工具结果里，不带就等于让成员凭空讨论。
 * 真机现象：十几万字的原对话开群后只剩 3 万字，缺的就是这一块。
 *
 * 刻意写成**第三人称叙述**（「它当时调用了…」）而不是原始协议内容：
 * 成员模型没参与过那次调用，直接塞协议会让它误以为是自己调的 ——
 * 这正是「群组期间别人的工具不进上文」的同一条理由，两处口径必须一致。
 *
 * ask_user **不在这里处理**：那是「用户本人给的信息」，由 [groupAskUserDigest] 负责，
 * 两处都管会把同一次问答贴两遍。
 *
 * 刻意不做长度截断：用户选的是「全部上文」，工具结果再长也整份带上；
 * 真嫌大就在群组设置里把「上文上限」填一个数 —— 那是唯一的降级阀。
 */
private fun groupToolDigest(message: UIMessage): String? {
    val tools = message.getTools().filter { it.toolName != ASK_USER_TOOL_NAME }
    if (tools.isEmpty()) return null
    val lines = buildList {
        for (t in tools) {
            val output = t.output
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            val input = t.input.trim()
            add(
                if (output.isNotEmpty()) {
                    "它当时调用了工具「${t.toolName}」，参数：$input\n结果：$output"
                } else {
                    "它当时调用了工具「${t.toolName}」，参数：$input（该工具没有产出结果）"
                }
            )
        }
    }
    if (lines.isEmpty()) return null
    return "（这段发言里还调用过工具，参数与结果一并给出，供你了解之前查到过什么）\n" +
        lines.joinToString("\n")
}

/**
 * 把一条助手消息里的「ask_user 提问 + 用户的回答」翻译成一句人话，供其他成员阅读。
 *
 * v318 新增，修的是真机 bug：成员向用户提问后，用户的答案存在提问卡片内部
 * （approvalState = Answered），而这条消息往往**没有正文**。旧的上文整形只认正文，
 * 于是「问了什么 + 答了什么」整条被丢掉，下一位开口的成员完全不知道用户答过什么，
 * 会当场否认用户刚给过的条件。
 *
 * 三条口径：
 * ① **只认 ask_user**。其它工具的输出仍然一律不进上文（那是某个成员与服务商之间的
 *    协议内容，换给另一个模型只会让它以为自己也调过一个并不存在的工具）。
 * ② 用户**还没回答**时不产出摘要 —— 那件事还没定，写进上文只会误导。
 * ③ 题目与答案都从卡片自己的参数/答案 JSON 里读（复用界面同一个解析器
 *    [parseAskUserQuestions]，两处口径不会漂移）；读不出题目时退化成只给答案原文。
 *
 * @return null 表示这条消息里没有 ask_user，或用户尚未作答
 */
private fun groupAskUserDigest(message: UIMessage): String? {
    val asks = message.getTools().filter { it.toolName == ASK_USER_TOOL_NAME }
    if (asks.isEmpty()) return null
    val lines = buildList {
        for (ask in asks) {
            val answer = (ask.approvalState as? ToolApprovalState.Answered)?.answer
                ?: continue // 用户还没答，别把悬而未决的问题写进上文
            val questions = parseAskUserQuestions(ask.inputAsJson())
            val answered = parseAskAnswer(answer)
            // 用户点的是「跳过」：那是写给提问者的指令，不是用户原话，别摊给别人
            val skipped = answered.isSkip
            for (q in questions) {
                val given = (answered[q.id] ?: answered.freeText)?.trim().orEmpty()
                if (skipped) {
                    add("提问：${q.question} → 用户跳过了这次提问，没有作答")
                } else if (q.question.isNotBlank()) {
                    add("提问：${q.question} → 用户回答：${given.ifBlank { "（未作答）" }}")
                } else if (given.isNotBlank()) {
                    add("用户回答：$given")
                }
            }
            if (questions.isEmpty()) {
                // 题目读不出来（参数形状不符）时，至少把用户的原话带进去，别丢掉答案。
                // 优先取 free_text（那是用户真打的字），取不到才摊答案原文。
                // ⚠️ 整条只产出一行：非 JSON 答案若按题重复，同一段原文会贴 N 遍。
                val raw = answered.freeText?.trim().orEmpty().ifBlank { answer.trim() }
                when {
                    skipped -> add("它向用户提了问，用户跳过了这次提问，没有作答")
                    raw.isNotBlank() -> add("它向用户提了问，用户的回答：$raw")
                }
            }
        }
    }
    if (lines.isEmpty()) return null
    val digest = lines.joinToString("\n")
    return if (digest.length > GROUP_ASK_DIGEST_MAX_CHARS) {
        digest.take(GROUP_ASK_DIGEST_MAX_CHARS) + "\n（问答过长，后面部分未纳入上文）"
    } else {
        digest
    }
}

/** 用户答案 JSON 的拆解结果：按题号的答案 + 兜底自由文本 + 附言。 */
private class AskAnswerMap(
    private val values: Map<String, String>,
    val freeText: String?,
    val note: String?,
) {
    operator fun get(id: String): String? = values[id]

    /**
     * 用户点的是「跳过」还是真答了。
     *
     * 两条来源都认：① 群面板跳过时整段答案就是那句指令；② 卡片跳过按钮把同样的话放在
     * note 字段里。识别不到就会把「不要重复问同样的问题」当成用户原话摊给其他成员。
     */
    val isSkip: Boolean
        get() = note?.contains(GROUP_ASK_SKIP_MARKER) == true ||
            values.values.any { it.contains(GROUP_ASK_SKIP_MARKER) } ||
            freeText?.contains(GROUP_ASK_SKIP_MARKER) == true
}

/**
 * 把 ask_user 的答案 JSON 拆成「题号 → 答案」。
 *
 * 答案形状由界面卡片构造：{"answers":{"q0":"…","free_text":"…"},"note":"…"}。
 * 但历史上也出现过整段自由文本直接当答案传的情况（跳过、降级输入等），
 * 所以解析失败时把原文整体当自由文本，绝不因为形状不符就丢掉用户说的话。
 */
private fun parseAskAnswer(answer: String): AskAnswerMap {
    val root = runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonObject
    val note = (root?.get("note") as? JsonPrimitive)?.contentOrNull
    val node = root?.get("answers") as? JsonObject
        ?: return AskAnswerMap(emptyMap(), answer, note)
    val values = node.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
    }.toMap()
    return AskAnswerMap(
        values = values,
        freeText = (node["free_text"] as? JsonPrimitive)?.contentOrNull,
        note = note,
    )
}

/**
 * v318：把某条消息挪到列表末尾，其余顺序不变。
 *
 * 修的是审查位抓出的 P1：成员向用户提问后，如果用户**先插一句话再点卡片回答**，
 * 上文的末条就变成那条插话（USER）。而生成层的续跑判定只看末条消息里有没有
 * 「待续跑的工具」（GenerationLoop 的 pendingTools 判定），于是认不出那条已回答的提问：
 * ① 提问者自己拿不到用户的答案（症状与原 bug 一样，只是换了触发路径）；
 * ② 更糟的是它会走「全新生成」，产出再按 overwriteMessageId 原地覆盖那条提问消息 ——
 *    提问卡片连同用户刚写下的答案一起被抹掉（丢用户数据）。
 *
 * 把提问消息放回末位，生成层就会照设计意图走续跑分支：答案作为工具输出喂回提问者，
 * 覆盖写回的也是「带答案的那条消息」，卡片与答案都不丢。
 *
 * 插话内容不会因此丢失：它仍在列表里，只是排在提问消息之前。
 *
 * @param messageId 要挪到末位的那条消息；为 null 或找不到时**原样返回**
 */
fun moveGroupMessageToEnd(messages: List<UIMessage>, messageId: Uuid?): List<UIMessage> {
    messageId ?: return messages
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0 || index == messages.lastIndex) return messages
    return messages.subList(0, index) + messages.subList(index + 1, messages.size) + messages[index]
}

/**
 * 超预算时从最旧的一端丢弃。
 *
 * 用户最新的要求必须保住 —— 圆桌那边是「材料过长截尾部」，这里反过来截头部，
 * 因为群讨论里越新的内容越关键，截尾会把用户刚补充的条件丢掉。
 */
private fun trimGroupTranscript(
    messages: List<UIMessage>,
    charLimit: Int,
): List<UIMessage> {
    // v319：<= 0 = 不限，整份原样返回（默认走这条，用户要的「获取全部上文」）
    if (charLimit <= 0) return messages
    var total = messages.sumOf { it.toText().length }
    if (total <= charLimit) return messages
    var index = 0
    while (index < messages.lastIndex &&
        total > charLimit
    ) {
        total -= messages[index].toText().length
        index++
    }
    return messages.drop(index)
}

/**
 * v321：群讨论期间产生的「杂乱上文」分两类判定，**两处共用同一套口径**：
 * 成员上文（buildGroupTranscript）与主对话请求（withoutGroupChatToolParts）。
 *
 * 为什么拆成两个函数而不是合成一个：工具与搜索结果**一直**都要摘（v319 定的口径），
 * 思考过程只在用户打开「只保留发言正文」时才摘 —— 两者条件不同，合并就没法表达。
 */
private fun isGroupToolPart(part: UIMessagePart): Boolean = when (part) {
    is UIMessagePart.Tool -> part.toolName != ASK_USER_TOOL_NAME
    is UIMessagePart.ServerTool -> true
    else -> false
}

private fun isGroupReasoningPart(part: UIMessagePart): Boolean = part is UIMessagePart.Reasoning

/**
 * v321：把「这位成员**在群里**说过的话」剥到只剩正文 + 提问 + 图片/文件。
 *
 * 别人的发言不走这里：他人发言经 [groupUtteranceText] 取正文后重新署名，
 * 思考本来就没被带上（只有工具摘要在开群前的历史那条线上才展开）。
 */
private fun UIMessage.onlyGroupUtteranceText(): UIMessage {
    val kept = parts.filterNot { part -> isGroupToolPart(part) || isGroupReasoningPart(part) }
    if (kept == parts) return this
    if (kept.isEmpty()) {
        // 整条只剩思考与工具：留一句占位，别发一条空消息给模型
        // （空 assistant 消息会被部分服务商直接拒掉，那会让整次讨论报错，比多一行字严重得多）
        return copy(
            parts = listOf(
                UIMessagePart.Text("（这条群讨论发言只有思考过程与工具调用，正文为空）")
            )
        )
    }
    return copy(parts = kept)
}

/**
 * v319：把「群组讨论期间产生的发言」里的工具调用与搜索结果摘掉，只留正文与推理。
 *
 * 给**主对话**拼上下文用：群里为了讨论去搜的东西，不该在解散群之后被当成主对话的
 * 亲身经历带出去（用户口径：「只带方案和聊天记录，不把群聊里的搜索/调用工具上文带出去」）。
 *
 * 三条口径，一条都不能省：
 * ① 只动 `fromGroupChat = true` 的消息 —— 开群之前的原对话、以及普通聊天自己的工具调用，
 *    本来就是主对话经历过的，一律不动。
 * ② `ask_user` 的提问与用户回答**保留**：那是用户本人说的话，摘掉等于把用户的话从
 *    记录里抹掉（v318 为这个踩过一次真机 bug，同一个坑不踩第二遍）。
 * ③ 返回**新列表**，绝不改传入的那份 —— 对话里的原始记录必须一个字都不动，
 *    用户仍然能在界面上回看群里当时查了什么（用户选的是「不删数据」）。
 */
fun List<UIMessage>.withoutGroupChatToolParts(
    /**
     * v321：一并摘掉思考过程 —— 用户打开「群讨论只保留发言正文」时由 ChatService 传 true。
     *
     * 默认 false：不传就是 v320 的老口径（只摘工具与搜索结果），思考照旧保留。
     */
    dropReasoning: Boolean = false,
): List<UIMessage> = map { message ->
    if (!message.fromGroupChat) return@map message
    val kept = message.parts.filterNot { part ->
        isGroupToolPart(part) || (dropReasoning && isGroupReasoningPart(part))
    }
    if (kept == message.parts) return@map message
    if (kept.isEmpty()) {
        // 整条只有工具调用：留一句占位，别发一条空消息给模型（空 assistant 消息会被
        // 部分服务商直接拒掉，那会让整次对话报错，比多一行字严重得多）
        return@map message.copy(
            parts = listOf(
                UIMessagePart.Text(
                    if (dropReasoning) {
                        "（这条群讨论发言只有思考过程与工具调用，内容未纳入主对话上下文）"
                    } else {
                        "（这条群讨论发言只有工具调用，内容未纳入主对话上下文）"
                    }
                )
            )
        )
    }
    message.copy(parts = kept)
}

/**
 * 每个成员开口前收到的那段指令。
 *
 * 只作为临时输入，不写进对话（与圆桌同一套做法）。
 */
fun buildGroupTurnInstruction(
    speakerName: String,
    memberNames: List<String>,
    speakerMode: GroupSpeakerMode,
    allowAskUser: Boolean,
    isFirstUtterance: Boolean,
): String = buildString {
    appendLine("【讨论群】现在轮到你（$speakerName）发言。")
    appendLine()
    appendLine("群成员：${memberNames.joinToString("、")}。用户也在群里。")
    appendLine()
    if (isFirstUtterance) {
        appendLine("这是你在这个群里第一次开口：先给出你自己的判断，不要附和尚未说过的观点。")
    } else {
        appendLine("上面标注过是谁说的。请**针对具体的某几句话**回应：")
        appendLine("同意就说明为什么同意、还能补什么；不同意就指出哪一条不成立、依据是什么。")
        appendLine("不要复述别人已经说过的内容，也不要写所有人都同意的空话。")
    }
    appendLine()
    if (speakerMode == GroupSpeakerMode.MODERATED) {
        appendLine("本群由主持人安排发言顺序，你说完后会交给主持人，不必点名下一位。")
    }
    appendLine()
    if (allowAskUser) {
        appendLine("如果继续讨论缺关键条件（目标不清、范围不明、缺素材、有多个方案需要你替用户决定），")
        appendLine("**调用提问工具向用户提问**，不要自己猜、也不要把问题埋在正文里等没人回答。")
        appendLine("问题要具体、可回答，并给出可选项。用户回答后讨论会自动继续。")
    } else {
        appendLine("本群已关闭向用户提问：把疑问写进你的发言，并给出条件化的判断。")
    }
    appendLine()
    append("直接写你要说的话本身，不要加“作为…”“我同意/不同意各位”这类客套开场。")
}

/**
 * 主持人用来决定"下一个谁说话"的提示词。
 *
 * 主持人**不发言**，只输出一个成员名字，所以提示词把输出形状钉死。
 */
fun buildGroupModeratorInstruction(
    speakerNames: List<String>,
    lastSpeakerName: String?,
): String = buildString {
    appendLine("你是这个讨论群的主持人，只负责决定下一个发言的人，不要自己发表意见。")
    appendLine()
    appendLine("候选成员（只能从这些名字里选，一字不差）：")
    speakerNames.forEach { appendLine("- $it") }
    appendLine()
    if (lastSpeakerName != null) {
        appendLine("刚发言的是「$lastSpeakerName」，除非只有他合适，否则换一个还没说过的人。")
        appendLine("谁已经说过、谁还没说过，看上面标注过署名的记录。")
    }
    appendLine()
    append("只输出一个成员名字，不要输出解释、标点或引号。")
}

/**
 * 从主持人的回复里挑出下一个发言者。
 *
 * 主持人是模型，输出形状不可信（可能带引号、带解释、甚至编一个不存在的名字）。
 * 所以这里做**最长匹配 + 兜底**：认不出来就退回轮流，绝不让整场讨论卡住。
 */
fun resolveModeratedSpeaker(
    moderatorReply: String,
    speakerNames: List<String>,
    fallback: Uuid?,
    idOf: (String) -> Uuid?,
): Uuid? {
    val reply = moderatorReply.trim()
    if (reply.isEmpty() || speakerNames.isEmpty()) return fallback
    // 先按名字长度倒序匹配，避免"小明"抢在"小明同学"前面命中
    speakerNames.sortedByDescending { it.length }.forEach { name ->
        if (reply.contains(name, ignoreCase = true)) {
            idOf(name)?.let { return it }
        }
    }
    return fallback
}
