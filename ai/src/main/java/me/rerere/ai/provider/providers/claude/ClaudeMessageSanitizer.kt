package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Claude(Anthropic Messages) 请求体消毒层。
 *
 * 二改版新增。动机: 在同一个会话里先用别家模型(GPT/Gemini)再切到 Claude 时,
 * 历史消息会被翻译成 Anthropic 格式回传, 其中若干结构是 Anthropic 明确拒收的,
 * 表现为 400 ValidationException(例如 "Invalid tool use format."), 整个会话从此发不出去。
 *
 * 这一层只做"删除非法内容 / 补齐必填内容 / 合并同角色", 不改变对话语义:
 *
 * 1. thinking block 缺 signature -> 丢弃。
 *    Anthropic 要求回传的 thinking 必须带它自己签发的 signature；
 *    别家模型产生的思考没有 signature, 回传必然被拒。
 * 2. 空的 text block -> 丢弃。Anthropic 不接受 text 为空字符串的 block
 *    (图片编码失败的兜底分支就会产出空 text)。
 * 3. tool_result 的 content 为空 -> 填占位文本。
 *    工具输出全是 Anthropic 不认识的类型(如 document)时会被清成空数组。
 * 4. content 被清空的消息 -> 整条丢弃。Anthropic 不接受空 content。
 * 5. 相邻同 role 消息 -> 合并 content。role 必须交替, 上一步的丢弃可能制造出连续同 role。
 * 6. user 消息内的 tool_result -> 排到最前。Anthropic 要求 tool_result 位于 content 开头。
 *
 * 注意: 有意不处理"首条消息必须是 user"。开头若为 assistant(tool_use),
 * 删除它会让紧随其后的 tool_result 变成孤儿, 反而更糟；而 RikkaHub 的对话总由用户发起,
 * 该场景不会自然出现。
 */

/** tool_result 内容为空时的占位文本, 让请求合法且语义明确。 */
internal const val CLAUDE_EMPTY_TOOL_RESULT_PLACEHOLDER = "(tool returned no textual output)"

internal fun sanitizeClaudeMessages(messages: JsonArray): JsonArray {
    val cleaned = ArrayList<JsonObject>(messages.size)

    for (element in messages) {
        val message = element as? JsonObject ?: continue
        val content = message["content"]
        if (content !is JsonArray) {
            // 结构不认识就原样放过, 避免误伤将来新增的消息形态
            cleaned += message
            continue
        }
        val blocks = content.mapNotNull { sanitizeContentBlock(it) }
        if (blocks.isEmpty()) continue
        cleaned += JsonObject(message + mapOf("content" to JsonArray(blocks)))
    }

    return mergeAdjacentSameRole(cleaned)
}

private fun sanitizeContentBlock(element: JsonElement): JsonObject? {
    val block = element as? JsonObject ?: return null
    return when (block.stringValueOf("type")) {
        "text" -> block.takeUnless { it.stringValueOf("text").isNullOrBlank() }
        "thinking" -> block.takeUnless { it.stringValueOf("signature").isNullOrEmpty() }
        "tool_result" -> block.withUsableToolResultContent()
        else -> block
    }
}

/**
 * tool_result 的 content 既可能是 block 数组也可能是字符串。
 * 数组形式先按同一套规则清理内部 block(主要是去掉空 text), 清空后补占位文本。
 */
private fun JsonObject.withUsableToolResultContent(): JsonObject {
    when (val content = this["content"]) {
        is JsonArray -> {
            val inner = content.mapNotNull { sanitizeContentBlock(it) }
            return if (inner.isEmpty()) {
                withPlaceholderToolResultContent()
            } else {
                JsonObject(this + mapOf("content" to JsonArray(inner)))
            }
        }

        is JsonPrimitive -> {
            return if (content.contentOrNull.isNullOrBlank()) {
                withPlaceholderToolResultContent()
            } else {
                this
            }
        }

        else -> return withPlaceholderToolResultContent()
    }
}

private fun JsonObject.withPlaceholderToolResultContent(): JsonObject = JsonObject(
    this + mapOf(
        "content" to buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", CLAUDE_EMPTY_TOOL_RESULT_PLACEHOLDER)
            })
        }
    )
)

private fun mergeAdjacentSameRole(messages: List<JsonObject>): JsonArray = buildJsonArray {
    var pending: JsonObject? = null

    for (message in messages) {
        val previous = pending
        if (previous != null && canMerge(previous, message)) {
            val merged = previous.contentArray() + message.contentArray()
            pending = JsonObject(previous + mapOf("content" to JsonArray(merged)))
            continue
        }
        previous?.let { add(finalizeMessage(it)) }
        pending = message
    }

    pending?.let { add(finalizeMessage(it)) }
}

private fun canMerge(left: JsonObject, right: JsonObject): Boolean {
    val leftRole = left.stringValueOf("role") ?: return false
    val rightRole = right.stringValueOf("role") ?: return false
    if (leftRole != rightRole) return false
    // 只合并两边 content 都是数组的情况, 其它形态不碰
    return left["content"] is JsonArray && right["content"] is JsonArray
}

/** Anthropic 要求 tool_result 位于 user 消息 content 的开头。 */
private fun finalizeMessage(message: JsonObject): JsonObject {
    if (message.stringValueOf("role") != "user") return message
    val content = message["content"] as? JsonArray ?: return message
    val toolResults = content.filter { it.blockType() == "tool_result" }
    if (toolResults.isEmpty() || toolResults.size == content.size) return message
    val others = content.filter { it.blockType() != "tool_result" }
    return JsonObject(message + mapOf("content" to JsonArray(toolResults + others)))
}

private fun JsonObject.contentArray(): List<JsonElement> =
    (this["content"] as? JsonArray)?.toList().orEmpty()

private fun JsonElement.blockType(): String? = (this as? JsonObject)?.stringValueOf("type")

private fun JsonObject.stringValueOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
