package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    /**
     * v248：按文件名/通配模式查找。
     *
     * 遍历受控（目录剪枝 + 时间预算），[report] 传进来就能拿到「扫了多少、跳过多少、有没有扫完」。
     */
    fun glob(
        root: File,
        pattern: String,
        path: String = "",
        report: WorkspaceScanReport? = null,
        /**
         * v248：是否启用「目录剪枝 + 时间/条目预算」。
         *
         * ⚠️ **默认 false，也就是与上游逐字节同样的行为。** 用户明确要求主对话相关的东西
         * 一律不许改动，所以这层刹车只对**明确开启它的调用方**生效（目前只有子代理）。
         */
        budgeted: Boolean = false,
    ): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val rootPath = root.toPath()
        val out = ArrayList<WorkspaceFileEntry>()
        walkControlled(start.toPath(), report, budgeted) { entry ->
            if (!entry.name.startsWith(".l2s.") &&
                matcher.matches(rootPath.relativize(entry).normalizeForMatch())
            ) {
                out += entry.toFile().toEntry(root)
            }
            out.size < config.maxListEntries
        }
        return out
    }

    /**
     * v248：按内容搜索。遍历同样受控，[report] 用法与 [glob] 一致。
     */
    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        report: WorkspaceScanReport? = null,
        /** v248：同 [glob] —— 默认 false，保持上游行为，只有子代理开启。 */
        budgeted: Boolean = false,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val rootPath = root.toPath()

        val results = mutableListOf<WorkspaceSearchMatch>()
        walkControlled(start.toPath(), report, budgeted) { entry ->
            if (Files.isRegularFile(entry) && !entry.name.startsWith(".l2s.")) {
                val included = includeMatcher == null ||
                    includeMatcher.matches(rootPath.relativize(entry).normalizeForMatch())
                if (included) {
                    val file = entry.toFile()
                    if (file.length() <= config.maxReadBytes) {
                        file.useLines(StandardCharsets.UTF_8) { lines ->
                            lines.forEachIndexed { index, line ->
                                if (results.size >= config.maxSearchResults) return@useLines
                                if (matcher.containsMatchIn(line)) {
                                    results += WorkspaceSearchMatch(
                                        path = file.relativePath(root),
                                        line = index + 1,
                                        text = line,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            results.size < config.maxSearchResults
        }
        return results
    }

    /**
     * v248：受控遍历 —— 目录剪枝 + 时间预算 + 条目预算。
     *
     * 旧实现是 `Files.walk(...)` 直接铺平成序列，一旦匹配额度凑不满就会走完整棵树。
     * 真机实测：工作区整棵树 44 万个条目，而「双星号斜杠 + 一个固定文件名」全树只有 1 个匹配，
     * 于是一条子代理开局并发 3 个这种查找，跑了 8 分 35 秒还没回来。
     *
     * 现在改用 `Files.walkFileTree`，因为只有它能在 `preVisitDirectory` 里返回
     * `SKIP_SUBTREE` 做**目录级剪枝**（跳过 build / .git / node_modules 之类，本机实测能砍掉 83%）。
     * 剪枝砍不掉的部分（例如用户自己的大目录）由**时间预算**兜住 —— 到点就带着已找到的结果返回，
     * 并通过 [report] 如实告知「没扫完」，避免模型把空结果当成「文件不存在」。
     *
     * @param onEntry 回调返回 false 表示调用方已经收够了，立刻停止遍历。
     */
    private fun walkControlled(
        start: Path,
        report: WorkspaceScanReport?,
        budgeted: Boolean,
        onEntry: (Path) -> Boolean,
    ) {
        // budgeted=false 时不设期限、不剪枝 —— 与上游行为一致
        val deadline =
            if (budgeted) System.currentTimeMillis() + config.walkTimeBudgetMillis else Long.MAX_VALUE
        var visited = 0
        var skipped = 0
        var stopReason: String? = null

        fun budgetState(): FileVisitResult {
            if (!budgeted) return FileVisitResult.CONTINUE
            if (visited >= config.maxWalkEntries) {
                stopReason = "entry-budget"
                return FileVisitResult.TERMINATE
            }
            // 每 512 个条目才看一次时钟：取系统时间本身有开销，没必要每个文件都问
            if ((visited and 0x1FF) == 0 && System.currentTimeMillis() > deadline) {
                stopReason = "time-budget"
                return FileVisitResult.TERMINATE
            }
            return FileVisitResult.CONTINUE
        }

        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // 起点目录本身永远不剪，否则用户明确指定 path 也会被跳过
                if (dir != start) {
                    if (budgeted && shouldSkipSearchDirectory(dir.fileName?.toString().orEmpty())) {
                        skipped++
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    visited++
                    val budget = budgetState()
                    if (budget != FileVisitResult.CONTINUE) return budget
                    if (!onEntry(dir)) return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visited++
                val budget = budgetState()
                if (budget != FileVisitResult.CONTINUE) return budget
                return if (onEntry(file)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }

            // 读不了的条目（权限、坏链接）跳过就好，不要让整趟检索失败
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })

        report?.let {
            it.visitedEntries = visited
            it.skippedDirectories = skipped
            it.stoppedEarly = stopReason != null
            it.stopReason = stopReason
        }
    }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}
