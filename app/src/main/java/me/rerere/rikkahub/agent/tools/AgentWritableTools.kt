package me.rerere.rikkahub.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * v236：子代理的**受限**写文件工具（「编程位」「改错位」用）。
 *
 * ## 为什么单独一个文件
 *
 * v218~v235 的子代理只有 [createAgentReadOnlyTools] 那 4 把只读工具，
 * 所以用户设想的「一个负责编程的子代理」在物理上根本做不到 —— 不是配置问题，
 * 是工具表里就没有写能力。本文件补上这一环。
 *
 * ## 三条硬约束（缺一条都不该放开写权限）
 *
 * 1. **白名单制**：[writablePaths] 为空就一个工具都不给（默认只读，与 v235 完全一致）。
 *    主模型必须显式点名哪些文件/目录可改，子代理越界一律拒绝。
 * 2. **防目录穿越**：路径先归一化（去掉 `/workspace` 前缀、拆掉 `.` 与 `..`），
 *    再与白名单比对。`../../etc/passwd` 这类写法在归一化阶段就被打掉。
 * 3. **不给执行能力**：这里只有「写整个文件」和「精确替换一段文字」两把工具。
 *    没有 shell、没有编译、没有删除、没有改名。编译独占 Gradle、单次好几分钟，
 *    只能留在主模型手里；删除的破坏半径太大，不值得为它承担风险。
 */
fun createAgentWritableTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository?,
    writablePaths: List<String>,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val repo = workspaceRepository ?: return emptyList()
    val allowed = writablePaths.mapNotNull { normalizeWorkspacePath(it).takeIf { p -> p.isNotEmpty() } }
    if (allowed.isEmpty()) return emptyList()
    return listOf(
        createWriteFileTool(workspaceId, repo, allowed),
        createEditFileTool(workspaceId, repo, allowed),
    )
}

/**
 * 把各种写法归一成「工作区相对路径」：
 * - 反斜杠转正斜杠（Windows 风格粘贴进来的路径）
 * - 去掉 `/workspace` 前缀与首尾斜杠
 * - 逐段消解 `.` 与 `..`（`..` 弹出上一段，弹不动就丢弃，绝不允许爬到工作区外）
 */
internal fun normalizeWorkspacePath(raw: String?): String {
    val cleaned = raw?.replace('\\', '/')?.trim().orEmpty()
    if (cleaned.isEmpty()) return ""
    val withoutPrefix = cleaned.removePrefix("/workspace").trim('/')
    val stack = ArrayList<String>()
    withoutPrefix.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            else -> stack.add(segment)
        }
    }
    return stack.joinToString("/")
}

/**
 * 目标路径是否落在白名单里。
 *
 * 白名单条目既可以是具体文件（完全相等），也可以是目录前缀（目标以 `条目/` 开头）。
 * 用「加斜杠再比前缀」而不是裸 startsWith，避免 `app` 意外放开 `application.kt`。
 */
internal fun isWorkspacePathAllowed(target: String, allowed: List<String>): Boolean {
    if (target.isEmpty()) return false
    return allowed.any { entry ->
        target == entry || target.startsWith("$entry/")
    }
}

private fun allowedHint(allowed: List<String>): String =
    allowed.joinToString(", ").let { if (it.length > 400) it.take(400) + "…" else it }

private fun deniedResult(path: String, allowed: List<String>): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("error", "path is not writable for this subagent")
            put("path", path)
            put("writable_whitelist", allowedHint(allowed))
            put(
                "hint",
                "Only the whitelisted paths above can be modified. " +
                    "Report the problem back to the main agent instead of trying another path.",
            )
        }.toString()
    )
)

private const val MAX_WRITE_CHARS = 256 * 1024

private fun createWriteFileTool(
    workspaceId: String,
    repo: WorkspaceRepository,
    allowed: List<String>,
) = Tool(
    name = "workspace_write_file",
    description = "Write a whole UTF-8 text file inside the workspace. " +
        "Only paths inside this subagent's writable whitelist are accepted: ${allowedHint(allowed)}. " +
        "Creating a new file under a whitelisted directory is allowed. " +
        "There is no shell, no build and no delete: report results back instead of trying to run anything.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Target file path, e.g. app/src/main/java/Foo.kt")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full new content of the file")
                })
            },
            required = listOf("path", "content"),
        )
    },
    execute = {
        val params = it.jsonObject
        val rawPath = params.text("path")
        val path = normalizeWorkspacePath(rawPath)
        val content = params.text("content")
        when {
            path.isEmpty() -> listOf(UIMessagePart.Text("{\"error\":\"path is required\"}"))
            !isWorkspacePathAllowed(path, allowed) -> deniedResult(rawPath, allowed)
            content.length > MAX_WRITE_CHARS ->
                listOf(UIMessagePart.Text("{\"error\":\"content too large (>$MAX_WRITE_CHARS chars)\"}"))

            else -> {
                repo.writeText(workspaceId, path, content, overwrite = true)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("path", path)
                            put("chars", content.length)
                        }.toString()
                    )
                )
            }
        }
    },
)

private fun createEditFileTool(
    workspaceId: String,
    repo: WorkspaceRepository,
    allowed: List<String>,
) = Tool(
    name = "workspace_edit_file",
    description = "Replace an exact snippet inside an existing workspace text file. " +
        "Only paths inside this subagent's writable whitelist are accepted: ${allowedHint(allowed)}. " +
        "old_text must appear exactly once unless replace_all is true. " +
        "Prefer this over rewriting a whole file, so unrelated code cannot be lost.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Target file path")
                })
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    execute = {
        val params = it.jsonObject
        val rawPath = params.text("path")
        val path = normalizeWorkspacePath(rawPath)
        val oldText = params.text("old_text")
        val newText = params.text("new_text")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        when {
            path.isEmpty() -> listOf(UIMessagePart.Text("{\"error\":\"path is required\"}"))
            oldText.isEmpty() -> listOf(UIMessagePart.Text("{\"error\":\"old_text is required\"}"))
            !isWorkspacePathAllowed(path, allowed) -> deniedResult(rawPath, allowed)
            else -> {
                val original = repo.readText(workspaceId, path)
                val occurrences = countOccurrences(original, oldText)
                when {
                    occurrences == 0 -> listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "old_text not found")
                                put("path", path)
                            }.toString()
                        )
                    )

                    occurrences > 1 && !replaceAll -> listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "old_text is not unique")
                                put("path", path)
                                put("occurrences", occurrences)
                                put("hint", "Include more surrounding context, or set replace_all=true.")
                            }.toString()
                        )
                    )

                    else -> {
                        val updated = if (replaceAll) {
                            original.replace(oldText, newText)
                        } else {
                            original.replaceFirst(oldText, newText)
                        }
                        repo.writeText(workspaceId, path, updated, overwrite = true)
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("ok", true)
                                    put("path", path)
                                    put("replaced", if (replaceAll) occurrences else 1)
                                    put("chars", updated.length)
                                }.toString()
                            )
                        )
                    }
                }
            }
        }
    },
)

/** 统计不重叠出现次数（避免 indexOf 循环写错导致死循环） */
internal fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
        count++
        index = text.indexOf(needle, index + needle.length)
    }
    return count
}

private fun JsonObject.text(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
