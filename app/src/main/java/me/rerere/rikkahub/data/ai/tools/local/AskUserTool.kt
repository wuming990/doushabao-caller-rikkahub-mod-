package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions when you need clarification, additional information, or confirmation.
        Each question should provide a list of suggested options whenever selection_type is single or multi (required then; optional only for free-text questions).
        The user may select an option or provide their own free-text answer for each question.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                // v295：原文案写 "Optional" 等于邀请模型省略——商汤渠道真机实锤
                                // （single 但不给 options，UI 卡死）。改为「单选/多选时必填」。
                                put(
                                    "description",
                                    "Suggested options for the user to choose from. REQUIRED when selection_type is single or multi (never omit then); may be omitted only for text questions."
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                                // v297：真机实锤（主模型自己也踩过一次）——模型把每个选项写成
                                // 对象时，整张卡片会一道题都不显示。描述里把形状钉死。
                                put(
                                    "itemShape",
                                    "Each item MUST be a plain JSON string, e.g. [\"选项一\",\"选项二\"]. " +
                                            "Never write options as objects like [{\"text\":\"...\"}]."
                                )
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
