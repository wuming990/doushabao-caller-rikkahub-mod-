package me.rerere.rikkahub.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * v1024：共享生成层原有4KB下限不动。仅子代理把用户选的1~3KB在工具出口兑现。
 * 保持有效JSON并明确截断；默认64KB和0不限原路径原样通过，非文本附件不丢。
 */
internal fun limitSmallAgentToolOutput(output: List<UIMessagePart>, charLimit: Int): List<UIMessagePart> {
    if (charLimit >= 4 * 1024 || charLimit <= 0) return output
    val text = output.filterIsInstance<UIMessagePart.Text>()
    if (text.sumOf { it.text.length.toLong() } <= charLimit) return output
    val full = text.joinToString("\n") { it.text }
    var excerptLength = minOf(full.length, charLimit)
    var payload: String
    while (true) {
        payload = buildJsonObject {
            put("truncated", true)
            put("total_chars", full.length)
            put("excerpt", full.take(excerptLength))
            put("hint", "Result exceeds your configured subagent limit; request a narrower range.")
        }.toString()
        if (payload.length <= charLimit || excerptLength == 0) break
        excerptLength = (excerptLength - maxOf(1, payload.length - charLimit)).coerceAtLeast(0)
    }
    return listOf(UIMessagePart.Text(payload)) + output.filter { it !is UIMessagePart.Text }
}

internal fun withSmallAgentOutputLimit(tools: List<Tool>, charLimit: Int): List<Tool> {
    if (charLimit >= 4 * 1024 || charLimit <= 0) return tools
    return tools.map { tool ->
        tool.copy(execute = { input -> limitSmallAgentToolOutput(tool.execute(input), charLimit) })
    }
}
