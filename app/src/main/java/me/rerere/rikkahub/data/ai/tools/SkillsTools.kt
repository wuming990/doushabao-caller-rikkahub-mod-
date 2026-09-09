package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    // v294：强制翻阅模式（设置项，默认关）。开启时注入一段强制指令：模型在首次回答前
    // 必须对每个已开启 skill 调一次 use_skill 翻阅入口说明；细节仍由模型按需自取
    // （保持 skill 渐进披露设计，不注入正文）。
    forceReview: Boolean = false,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        appendLine("    <name>${skill.name}</name>")
                        appendLine("    <description>${skill.description}</description>")
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                    // v294：强制翻阅模式（默认关 = 只给清单；开 = 强制先翻阅一遍入口说明）
                    if (forceReview) {
                        appendLine("<mandatory_skill_review>")
                        appendLine("Force-review mode is ON. Before your FIRST response in this conversation, you MUST call the use_skill tool once for EVERY skill listed above (default path = its SKILL.md). After that, read deeper into any skill only the parts you actually need via the path parameter. Do not skip this review while the switch is on.")
                        appendLine("</mandatory_skill_review>")
                        appendLine()
                    }
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}
