package me.rerere.rikkahub.agent.tools

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
import me.rerere.rikkahub.data.datastore.TOOL_OUTPUT_LIMIT_DEFAULT_KB
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceScanReport
import me.rerere.workspace.WorkspaceStorageArea

/**
 * v218：子代理专用只读工作区工具。
 *
 * 独立实现（不复用圆桌的 createRoundTableReadOnlyWorkspaceTools），
 * 保证 agent 包与圆桌零耦合（由依赖边界测试守护）。
 *
 * 只提供固定查看操作：列目录、按文件名查找、按内容搜索、读文件。
 * 不提供任何写/删/执行能力；workspaceId 为空时不创建任何工具。
 */
fun createAgentReadOnlyTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository?,
    readLimitChars: Int = TOOL_OUTPUT_LIMIT_DEFAULT_KB * 1024,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val repo = workspaceRepository ?: return emptyList()
    return listOf(
        createListFilesTool(workspaceId, repo),
        createFindFilesTool(workspaceId, repo),
        createSearchTextTool(workspaceId, repo),
        createReadFileTool(workspaceId, repo, readLimitChars),
    )
}

private const val PATH_DESC =
    "Path inside the workspace files area. Absolute form like /workspace/foo/bar or relative form foo/bar both work. Empty means the workspace root."

private fun createListFilesTool(
    workspaceId: String,
    repo: WorkspaceRepository,
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
        val entries = repo.listFiles(workspaceId, WorkspaceStorageArea.FILES, path)
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
    repo: WorkspaceRepository,
) = Tool(
    name = "workspace_find_files",
    description = "Find files in the workspace by a glob pattern, for example **/*.kt or **/build.gradle.kts. IMPORTANT: always narrow the search with the path parameter when you have any idea where to look — searching from the workspace root walks the whole tree, which can hold hundreds of thousands of entries and will be cut off by a time budget. Build and cache folders (build, .git, node_modules and similar) are skipped automatically. If the reply carries an \"incomplete\" field, the scan was cut off and the result may be partial. Read-only.",
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
        val scan = WorkspaceScanReport()
        // v248：子代理这条路明确开启剪枝与预算（主对话那条路不开，保持上游行为）
        val entries = repo.globFiles(workspaceId, pattern, path, scan, budgeted = true)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("path", path)
                    put("count", entries.size)
                    // v248：如实告知扫描规模与是否被截断，否则模型会把「没扫完」当成「文件不存在」
                    put("scanned_entries", scan.visitedEntries)
                    if (scan.skippedDirectories > 0) {
                        put("skipped_build_or_cache_dirs", scan.skippedDirectories)
                    }
                    scan.hintOrNull()?.let { put("incomplete", it) }
                    put("entries", JsonArray(entries.map { entry -> entry.toJsonElement() }))
                }.toString()
            )
        )
    },
)

private fun createSearchTextTool(
    workspaceId: String,
    repo: WorkspaceRepository,
) = Tool(
    name = "workspace_search_text",
    description = "Search text inside workspace files, plain text or regular expression. Returns matching file paths with line numbers. IMPORTANT: always narrow the search with the path parameter when you have any idea where to look — searching from the workspace root walks the whole tree and will be cut off by a time budget. Build and cache folders are skipped automatically. If the reply carries an \"incomplete\" field, the scan was cut off and the result may be partial. Read-only.",
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
        val scan = WorkspaceScanReport()
        val matches = repo.grepFiles(
            id = workspaceId,
            query = query,
            path = path,
            regex = regex,
            includeGlob = includeGlob,
            report = scan,
            // v248：同 find_files —— 只有子代理开启刹车
            budgeted = true,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("query", query)
                    put("path", path)
                    put("count", matches.size)
                    // v248：同 find_files —— 扫描规模与截断实况必须回传
                    put("scanned_entries", scan.visitedEntries)
                    if (scan.skippedDirectories > 0) {
                        put("skipped_build_or_cache_dirs", scan.skippedDirectories)
                    }
                    scan.hintOrNull()?.let { put("incomplete", it) }
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

/**
 * v247→v248：单段内容预算与序列化安全线。
 *
 * ## v247 踩过的坑（真机抓到，务必别再犯）
 *
 * 上游对**任何工具返回**写死 32KB 上限，超了只给模型 4KB 预览。v247 把单段内容预算也设成
 * 32KB，漏算了 JSON 转义（换行与引号都要变两个字符）：实测 32721 字符的段序列化成 **33955**
 * 字符 → 每段都被外层截掉。更糟的是元数据排在 content 之前，模型照样读到
 * `end_line=879` / `next_start_line=880`，于是**「以为读过了」直接跳段，中间约 800 行静默丢失、
 * 毫无报错**。
 *
 * ## 现在怎么做
 *
 * 上限是设置项 [me.rerere.rikkahub.data.datastore.Settings.toolOutputLimitKb]，
 * **只作用于子代理**（主对话与圆桌仍走上游写死的 32KB，红线：主模型不许被改动）；
 * 单段预算取上限的 75%，再按**实际序列化长度**回缩（见 [sliceForToolPayload]）——
 * 转义膨胀率随内容变化，固定比例靠不住，必须实测回缩。
 */
internal fun agentReadBudgetChars(limitChars: Int): Int =
    (limitChars * 3 / 4).coerceAtLeast(1024)

/** v248：序列化后的安全线 —— 比上限留 1KB 余量。 */
internal fun agentReadSafeChars(limitChars: Int): Int =
    (limitChars - 1024).coerceAtLeast(2048)

/**
 * v247：读文件分段结果。
 *
 * 为什么需要：单次上限本身是必要的（一次把 70KB 源码塞进上下文没有意义），但旧实现只做
 * `take(MAX_READ_CHARS)`，**没有任何办法读到后面的内容**。真机实测：子代理审查
 * GenerationAgentBackend.kt（71614 字节）时只能看到前 45%，剩下的只能靠
 * workspace_search_text 拿到的零散行去猜 —— 它自己在报告里如实写了「函数体我未能直接
 * 读到原文，只能依据搜索返回的行内片段推断」。
 *
 * 现在按行分段：每次最多 [MAX_READ_CHARS] 字符，还有后续就回 [nextStartLine]，
 * 子代理照着这个值再读一次就能接着看。按行切而不是按字符切，是为了不把一行代码劈成两半。
 */
internal data class WorkspaceTextSlice(
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int,
    val hasMore: Boolean,
    val nextStartLine: Int?,
    val lineTruncated: Boolean,
)

/**
 * v247：从 [startLine]（1 起）开始按行取一段，单段不超过 [maxChars] 字符。
 *
 * 顶层函数是刻意的：这样能直接喂数据做单元测试，不需要造 WorkspaceRepository
 * （本项目 testImplementation 只有 junit，没有 mockk）。
 *
 * 边界处理：
 * - 单行本身就超过 [maxChars]（病态文件，例如压缩成一行的 JSON）：截断这一行并把
 *   [WorkspaceTextSlice.lineTruncated] 置真，下一段仍从下一行开始 —— 保证一定有进展，
 *   不会卡在同一行来回读。
 * - [startLine] 越界：夹到有效范围，不抛错（子代理拿着过期的 next_start_line 重试也不会崩）。
 */
internal fun sliceTextByLines(text: String, startLine: Int, maxChars: Int): WorkspaceTextSlice {
    require(maxChars > 0) { "maxChars must be positive" }
    val lines = text.split("\n")
    val totalLines = lines.size
    val from = startLine.coerceIn(1, totalLines)
    val builder = StringBuilder()
    var endLine = from
    var lineTruncated = false
    var first = true
    for (index in from - 1 until totalLines) {
        val line = lines[index]
        if (first) {
            // 每段的第一行无条件收下，否则遇到超长行会永远读不出东西。
            // 注意不能用 builder.isEmpty() 判断「是不是第一行」——
            // 空行会让 builder 保持为空，于是下一行也被当成第一行，换行符就丢了。
            if (line.length > maxChars) {
                builder.append(line.takeAvoidingSurrogatePair(maxChars))
                lineTruncated = true
            } else {
                builder.append(line)
            }
            first = false
            endLine = index + 1
            if (builder.length >= maxChars) break
            continue
        }
        if (builder.length + 1 + line.length > maxChars) break
        builder.append('\n').append(line)
        endLine = index + 1
    }
    val hasMore = endLine < totalLines
    return WorkspaceTextSlice(
        content = builder.toString(),
        startLine = from,
        endLine = endLine,
        totalLines = totalLines,
        hasMore = hasMore,
        nextStartLine = if (hasMore) endLine + 1 else null,
        lineTruncated = lineTruncated,
    )
}

/**
 * v248：截断超长行时不要切在 UTF-16 代理对中间，否则末尾会留下半个字符（emoji 变成乱码方块）。
 */
private fun String.takeAvoidingSurrogatePair(n: Int): String {
    if (length <= n || n <= 0) return take(n.coerceAtLeast(0))
    val cut = if (this[n - 1].isHighSurrogate()) n - 1 else n
    return take(cut)
}

/**
 * v248：把一段读取结果拼成工具返回的 JSON 文本。
 *
 * 抽成顶层函数就是为了能单测「序列化之后到底多长」—— v247 漏掉的正是这一步：
 * 只算了 content 的字符数，没算 JSON 转义后的总长。
 */
internal fun buildReadFilePayload(path: String, slice: WorkspaceTextSlice): String =
    buildJsonObject {
        put("path", path)
        // 字段名保留 truncated：既有提示词与门禁都认这个词
        put("truncated", slice.hasMore)
        put("start_line", slice.startLine)
        put("end_line", slice.endLine)
        put("total_lines", slice.totalLines)
        slice.nextStartLine?.let { next -> put("next_start_line", next) }
        if (slice.lineTruncated) put("line_truncated", true)
        put("content", slice.content)
    }.toString()

/**
 * v248：切一段，并保证**序列化之后**不超过 [safeChars]。
 *
 * 为什么不能只靠固定预算：转义膨胀率随内容变化 —— 普通源码约 4%，全是引号和反斜杠的
 * 压缩 JSON 可能接近翻倍。固定预算在最坏情况下照样会被外层截断，而外层截断会让模型
 * 拿着「看起来正常」的 end_line / next_start_line 直接跳段，中间内容静默丢失。
 *
 * 所以这里按实际长度回缩：每轮把预算按超出比例乘下去（再打个九折留余量），
 * 最多 6 轮，预算下限 512 字符 —— 保证一定收敛、一定有进展。
 */
internal fun sliceForToolPayload(
    path: String,
    text: String,
    startLine: Int,
    limitChars: Int = TOOL_OUTPUT_LIMIT_DEFAULT_KB * 1024,
    initialBudget: Int = agentReadBudgetChars(limitChars),
    safeChars: Int = agentReadSafeChars(limitChars),
): Pair<WorkspaceTextSlice, String> {
    var budget = initialBudget.coerceAtLeast(512)
    var slice = sliceTextByLines(text, startLine, budget)
    var payload = buildReadFilePayload(path, slice)
    var guard = 0
    while (payload.length > safeChars && guard < 6 && budget > 512) {
        val ratio = safeChars.toDouble() / payload.length
        budget = (budget * ratio * 0.9).toInt().coerceAtLeast(512)
        slice = sliceTextByLines(text, startLine, budget)
        payload = buildReadFilePayload(path, slice)
        guard++
    }
    return slice to payload
}

private fun createReadFileTool(
    workspaceId: String,
    repo: WorkspaceRepository,
    limitChars: Int,
) = Tool(
    name = "workspace_read_file",
    description = "Read a text file inside the workspace files area. One call returns a chunk cut at a line boundary (up to about ${agentReadBudgetChars(limitChars)} characters, automatically shrunk when needed so the reply is never cut off by the transport). If the reply contains next_start_line, the file has more content: call again with the same path and start_line set to that number to continue. To jump straight to a known place, search first and pass the line number you got. Read-only.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", PATH_DESC)
                })
                put("start_line", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "1-based line number to start from. Defaults to 1. Pass next_start_line from the previous reply to read the rest of a long file."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params.stringOrNull("path")?.trim().orEmpty()
        require(path.isNotBlank()) { "path is required" }
        val rel = path.replace('\\', '/').removePrefix("/workspace").trimStart('/')
        val text = repo.readText(workspaceId, rel)
        val (_, payload) = sliceForToolPayload(
            path = path,
            text = text,
            startLine = params.intOrNull("start_line") ?: 1,
            limitChars = limitChars,
        )
        listOf(UIMessagePart.Text(payload))
    },
)

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/**
 * v247：读整数参数。模型有时把数字当字符串传（"12"），所以统一按文本再转，
 * 转不动就当没给（回 null，调用方用默认值），不抛错打断整趟。
 */
private fun JsonObject.intOrNull(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()

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
