package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val SELECTION_TEXT = "text"
internal const val SELECTION_SINGLE = "single"
internal const val SELECTION_MULTI = "multi"

/**
 * ask_user 卡片的一道问题（从模型给的参数里解析出来）。
 *
 * v297：从 ChatMessageTools.kt 里搬出来，做成顶层纯函数 + 数据类。
 * 搬出来的唯一理由是「能被真单测跑起来」——旧实现埋在 Composable 文件里，
 * 只能靠读源码做字符串断言，这类解析 bug 根本测不出来。
 *
 * 注意：三个档位常量必须声明在本文件里比数据类更靠前的位置 ——
 * Kotlin 顶层属性的初始化器不允许前向引用（v279 编译踩坑），默认值虽然走函数体
 * 不受此限，但按老规矩把常量放前面最省事。
 */
internal data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = SELECTION_TEXT,
    /**
     * 原始参数里**出现过**选项字段（options / choices / option_list / items）。
     *
     * v299 审查位要求：卡片摊开原始参数只在「本该有选项却没解析出来」时做，
     * 而 schema 明确允许纯文本题不带 options —— 只看「选项为空」会把所有正常
     * 自由文本提问卡都摊成一段 JSON。这个标记用来区分「本来就没有」和「有但丢了」。
     */
    val hadOptionsField: Boolean = false,
)

/**
 * 把 ask_user 的参数解析成问题列表（v297：逐题、逐选项容错）。
 *
 * 真机实锤的失败形态（这一版是主模型自己发错参数触发的）：模型把 options 写成
 * **对象数组**（每个选项长成 `{"text": "选项一", "selection_type": "single"}`），
 * 而旧实现是「整批 runCatching + 对每个选项硬取 jsonPrimitive」，于是
 * **一个选项形状不对 → 整张卡片所有题目一起消失**：标题退化成省略号、内容区空白，
 * 而提交键因为「空列表的 all 判定恒真」照样可点，模型只收到空的 answers，
 * 用户答不了话、模型也不知道发生了什么。
 *
 * 现在的规矩：坏一个丢一个；全丢光也要留一个自由文本框；并且如实告诉模型「没解析出问题」。
 */
internal fun parseAskUserQuestions(arguments: JsonElement?): List<AskUserQuestion> {
    val items = questionsArray(arguments) ?: return emptyList()
    return items.mapIndexedNotNull { index, item -> parseAskUserQuestion(item, index) }
}

/**
 * 参数里确实有内容、却一道题都没读出来。
 *
 * 这种情况 UI 绝不能显示成空白卡片（那是真机用户看到的「什么都显示不了」），
 * 必须给出自由文本输入，并把这条判定如实回传给模型让它换写法。
 */
internal fun askUserArgumentsUnreadable(arguments: JsonElement?): Boolean {
    // v299 审查位补：参数本身就是数组时也要能判（旧写法只认 JsonObject，裸数组恒 false）
    val hasContent = when (arguments) {
        is JsonObject -> arguments.isNotEmpty()
        is JsonArray -> arguments.isNotEmpty()
        else -> false
    }
    if (!hasContent) return false
    return parseAskUserQuestions(arguments).isEmpty()
}

/** 选项对象里可能放文字的字段名（各家模型写法不一，按顺序试；都取不到才丢这一个选项）。 */
private val OPTION_TEXT_KEYS = listOf("text", "label", "value", "content", "title", "name", "option")

/** 选项节点可能出现的字段名（v299：商汤渠道真机实遇「选项一个都显示不出来」）。 */
private val OPTION_KEYS = listOf("options", "choices", "option_list", "items")

/** questions 节点：正常是数组；有的模型把整个数组塞成 JSON 字符串，这里再解一次。 */
private fun questionsArray(arguments: JsonElement?): JsonArray? {
    // v299：参数本身就是数组时也算能读（模型省掉了外层 questions 包装）
    if (arguments is JsonArray) return arguments
    return when (val node = (arguments as? JsonObject)?.get("questions")) {
        is JsonArray -> node
        is JsonPrimitive -> node.contentOrNull
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray

        else -> null
    }
}

private fun parseAskUserQuestion(item: JsonElement, index: Int): AskUserQuestion? {
    // 模型直接给字符串数组（["问题一","问题二"]）时也算能读，退化成自由文本回答
    if (item !is JsonObject) {
        val text = item.asTextOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return AskUserQuestion(id = "q$index", question = text.trim(), options = emptyList())
    }
    val id = item["id"].asTextOrNull()?.takeIf { it.isNotBlank() } ?: "q$index"
    // v299：题干也可能被换名（title / text / prompt），都取不到才算这题读不出来
    val question = (item["question"] ?: item["title"] ?: item["text"] ?: item["prompt"])
        .asTextOrNull().orEmpty().trim()
    val options = parseOptions(item)
    if (question.isEmpty() && options.isEmpty()) return null
    return AskUserQuestion(
        id = id,
        question = question.ifEmpty { "第 ${index + 1} 个问题" },
        options = options,
        selectionType = normalizeSelectionType(item["selection_type"].asTextOrNull()),
        // v299：区分「本来就没给选项」和「给了但丢了」——卡片摊原文只对后者做
        hadOptionsField = OPTION_KEYS.any { item.containsKey(it) },
    )
}

private fun JsonElement?.asTextOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * 取一题的选项（v299 加固）。
 *
 * 真机现场：商汤渠道 + kimi-k3 调 ask_user 时，题干正常显示、选项一个都看不到。
 * 旧实现只认 `options` 且必须是 JSON 数组，于是「网关把嵌套数组吞掉」或
 * 「模型把选项写成字符串」这两种形状都会静默退化成自由文本框。
 * 现在：字段名按 [OPTION_KEYS] 依次试；数组、JSON 字符串、分隔符串三种形状都接。
 */
private fun parseOptions(item: JsonObject): List<String> {
    for (key in OPTION_KEYS) {
        val node = item[key] ?: continue
        val parsed = when (node) {
            is JsonArray -> node.mapNotNull { optionText(it) }
            is JsonPrimitive -> parseOptionString(node.contentOrNull)
            else -> emptyList()
        }
        if (parsed.isNotEmpty()) return parsed
    }
    return emptyList()
}

/**
 * 选项整串：先当 JSON 数组再解一次（模型把数组序列化成字符串很常见），
 * 解不出来再按常见分隔符切。
 *
 * 分隔符只收「几乎不会出现在单个选项内部」的几种；逗号也收，因为中文模型
 * 写选项串时经常用「、」或「，」。切完若只剩一项，说明这串本来就是单个选项。
 */
private fun parseOptionString(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    if (text.startsWith("[")) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonArray) return parsed.mapNotNull { optionText(it) }
    }
    return text.split('|', '\n', '、', '；', ';', '，', ',')
        .map { part ->
            part.trim()
                .trimStart('-', '*', '·', '•')
                .trim()
                // v299 审查位：模型写「1. 甲 / 2. 乙」这种编号列表极常见，编号必须剥掉，
                // 否则选项会带着「1. 」一起显示（且测试里那条断言也就永远过不去）
                .replaceFirst(OPTION_INDEX_PREFIX, "")
                .trim()
        }
        .filter { it.isNotBlank() }
        .take(12)
}

/**
 * 选项串里的「1. 」「2) 」这类序号前缀（v299）。
 *
 * 分隔符后面**必须跟空白**才算序号 —— 否则「3.5 倍」「1.5 倍」这种正常选项会被削成「5 倍」。
 * 顿号（、）故意不收：它既是列表分隔符又可能是内容（「3、5」），收了容易误伤。
 */
private val OPTION_INDEX_PREFIX = Regex("^\\d+\\s*[.．)]\\s+")

/** 选项：纯字符串直接用；对象则按常见字段名找文字；找不到只丢这一个选项，不牵连整题。 */
private fun optionText(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> element.let { obj ->
        OPTION_TEXT_KEYS.firstNotNullOfOrNull { key -> obj[key].asTextOrNull() }
    }?.takeIf { it.isNotBlank() }

    else -> null
}

/**
 * 选择类型容错：只认 text / single / multi，另收几个常见变体措辞。
 *
 * 认不出来（含模型根本没写这个字段）时一律按 **text** 处理——text 这一档界面会同时
 * 给出「可点的选项 + 自由输入框」，比 single（只有选项）宽松，
 * 不会因为模型写错措辞就把用户的回答途径堵死。
 */
private fun normalizeSelectionType(raw: String?): String {
    return when (raw?.trim()?.lowercase()) {
        SELECTION_MULTI, "multiple", "multi-select", "multiselect" -> SELECTION_MULTI
        SELECTION_SINGLE, "single-select", "radio", "choice", "select" -> SELECTION_SINGLE
        else -> SELECTION_TEXT
    }
}
