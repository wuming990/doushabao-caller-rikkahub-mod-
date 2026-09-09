package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/**
 * 圆桌专用的工作区只读检索工具（v208 新增）。
 *
 * 有意不复用 workspace_shell：并发的多个模型如果能执行任意命令，写文件、跑构建会互相打架，
 * 而且无法靠提示词可靠约束。这里只提供固定的查看操作：
 * 列目录、按文件名查找、按内容搜索。读文件仍复用官方 workspace_read_file。
 *
 * 路径统一按 "/workspace/..." 绝对路径书写，与 workspace_read_file 保持一致；
 * 内部会剥掉 /workspace 前缀再交给工作区文件区处理。
 */
fun createRoundTableReadOnlyWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    return listOf(
        createListFilesTool(workspaceId, workspaceRepository),
        createFindFilesTool(workspaceId, workspaceRepository),
        createSearchTextTool(workspaceId, workspaceRepository),
    )
}

private const val PATH_DESC =
    "Path inside the workspace files area. Absolute form like /workspace/foo/bar or relative form foo/bar both work. Empty means the workspace root."

private fun createListFilesTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_files",
    description = "List files and directories inside the workspace files area (/workspace). Read-only: it never changes anything.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", PATH_DESC)
                })
            },
            required = emptyList(),
        )
    },
    execute = {
        val path = it.jsonObject.workspaceRelativePath("path")
        val entries = workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("count", entries.size)
                    put("entries", JsonArray(entries.map { entry -> entry.toJsonElement() }))
                }.toString()
            )
        )
    },
)

private fun createFindFilesTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_find_files",
    description = "Find files in the workspace by a glob pattern, for example **/*.kt or **/build.gradle.kts. Read-only.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern relative to the workspace files area, e.g. **/*.kt")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", PATH_DESC)
                })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val params = it.jsonObject
        val pattern = params.stringOrNull("pattern")?.trim().orEmpty()
        require(pattern.isNotBlank()) { "pattern is required" }
        val path = params.workspaceRelativePath("path")
        val entries = workspaceRepository.globFiles(workspaceId, pattern, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("path", path)
                    put("count", entries.size)
                    put("entries", JsonArray(entries.map { entry -> entry.toJsonElement() }))
                }.toString()
            )
        )
    },
)

private fun createSearchTextTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_search_text",
    description = "Search text inside workspace files, plain text or regular expression. Returns matching file paths with line numbers. Read-only.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or regular expression to search for")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", PATH_DESC)
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether query is a regular expression. Defaults to false.")
                })
                put("includeGlob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file filter, such as **/*.kt")
                })
            },
            required = listOf("query"),
        )
    },
    execute = {
        val params = it.jsonObject
        val query = params.stringOrNull("query")?.trim().orEmpty()
        require(query.isNotBlank()) { "query is required" }
        val path = params.workspaceRelativePath("path")
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val includeGlob = params.stringOrNull("includeGlob")?.trim()?.takeIf { g -> g.isNotBlank() }
        val matches = workspaceRepository.grepFiles(
            id = workspaceId,
            query = query,
            path = path,
            regex = regex,
            includeGlob = includeGlob,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("query", query)
                    put("path", path)
                    put("count", matches.size)
                    put("matches", JsonArray(matches.map { match ->
                        buildJsonObject {
                            put("path", match.path)
                            put("line", match.line)
                            put("text", match.text)
                        }
                    }))
                }.toString()
            )
        )
    },
)

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/** 允许模型传 /workspace/... 绝对路径或相对路径，统一归一化为工作区文件区内的相对路径 */
private fun JsonObject.workspaceRelativePath(name: String): String =
    stringOrNull(name)
        ?.replace('\\', '/')
        ?.trim()
        ?.removePrefix("/workspace")
        ?.trimStart('/')
        .orEmpty()

private fun WorkspaceFileEntry.toJsonElement() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
