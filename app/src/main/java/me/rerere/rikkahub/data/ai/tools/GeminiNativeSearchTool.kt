package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.gemini.GeminiSearchClient
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

/**
 * v310：谷歌账号通道的「独立搜索」工具。
 *
 * 为什么做成工具、而不是请求里的内置工具：这条通道上「内置搜索 + 函数工具」同请求必 400
 * （本项目两轮真机 + 三家开源实测），v308（加开关）与 v309（二选一）都只能缓解。
 * 官方 gemini-cli 与开源反代的共同做法是把搜索拆成一次独立请求 —— 本工具就是那次请求的
 * 入口：模型随时可以调用它，搜索结果作为普通工具结果回填，于是「搜索」与「工作区工具」
 * 可以在同一轮回答里先后使用。
 *
 * 与用户界面的关系：「模型设置 → 内置工具 → 搜索」这个开关的语义不变（打开 = 用谷歌搜索），
 * 只是这条通道上它由独立请求承载。
 */
fun createGeminiNativeSearchTool(client: GeminiSearchClient): Tool = Tool(
    name = "google_search",
    description = """
        Search the web using Google Search for real-time or specific information.
        Use this when the user asks for the latest news, current facts, or needs verification
        (today is ${LocalDate.now().toLocalString(true)}).
        Generate focused keywords; feel free to call it more than once.
        Returns a synthesized summary with source links.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "The search query to find information on the web.")
                    }
                )
            },
            required = listOf("query"),
        )
    },
    execute = { arguments ->
        val query = ((arguments as? JsonObject)?.get("query") as? JsonPrimitive)
            ?.contentOrNull
            .orEmpty()
            .trim()
        if (query.isEmpty()) {
            listOf(UIMessagePart.Text("Error: the 'query' parameter cannot be empty."))
        } else {
            listOf(UIMessagePart.Text(client.search(query)))
        }
    },
)
