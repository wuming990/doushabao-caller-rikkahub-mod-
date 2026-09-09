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
    val obj = arguments as? JsonObject ?: return false
    if (obj.isEmpty()) return false
    return parseAskUserQuestions(arguments).isEmpty()
}

/** 选项对象里可能放文字的字段名（各家模型写法不一，按顺序试；都取不到才丢这一个选项）。 */
private val OPTION_TEXT_KEYS = listOf("text", "label", "value", "content", "title", "name", "option")

/** questions 节点：正常是数组；有的模型把整个数组塞成 JSON 字符串，这里再解一次。 */
private fun questionsArray(arguments: JsonElement?): JsonArray? {
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
    val question = item["question"].asTextOrNull().orEmpty().trim()
    val options = (item["options"] as? JsonArray)?.mapNotNull { optionText(it) } ?: emptyList()
    if (question.isEmpty() && options.isEmpty()) return null
    return AskUserQuestion(
        id = id,
        question = question.ifEmpty { "第 ${index + 1} 个问题" },
        options = options,
        selectionType = normalizeSelectionType(item["selection_type"].asTextOrNull()),
    )
}

private fun JsonElement?.asTextOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

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
