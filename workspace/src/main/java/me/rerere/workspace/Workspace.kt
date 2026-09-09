package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
    /**
     * v248：一次检索最多访问多少个目录项。
     *
     * 真机实测：这台设备的工作区整棵树有 **44 万个条目**（其中便携工具链目录一家就占 14 万），
     * 而「双星号斜杠 + 一个固定文件名」这类模式全树往往只有 1 个匹配 —— 匹配额度永远凑不满，
     * 旧实现于是必然走完 44 万个条目。真实后果：一条子代理开局并行发了 3 个这种查找，
     * 跑了 8 分 35 秒还没返回，最后被人工停止，什么活都没干成。
     */
    val maxWalkEntries: Int = 200_000,
    /**
     * v248：一次检索最多花多少毫秒。**这是主防线**。
     *
     * 为什么以时间为主而不是以条目数为主：条目数换算成耗时取决于设备与文件系统，
     * 而"最多等 10 秒"是用户能直接感知、跨设备都成立的约束。
     * 到点就返回已经找到的，并如实告知"没扫完、请用 path 缩小范围"。
     */
    val walkTimeBudgetMillis: Long = 10_000,
)

/**
 * v248：检索时默认跳过的目录名（构建产物与各类缓存）。
 *
 * 剪枝效果实测（本机工作区）：源码树 91,819 个条目 → **15,547 个**，降到 17%，
 * 因为 Gradle 的 `build` 目录占了 83%。
 *
 * 清单刻意保守 —— 只收公认「不该在里面找源码」的目录：
 * - `.git` / `.gradle` / `.idea` / `.vscode` / `.kotlin` / `.cxx`：版本库与 IDE、编译缓存
 * - `build`：Gradle 与 Android 的产物目录，每个模块一个，是本项目里最大的一块无用遍历
 * - `node_modules` / `__pycache__` / `.venv` / `venv` / `.dart_tool` / `.pub-cache`：包管理与语言缓存
 * - `.cache` / `.m2` / `.npm`：下载缓存
 *
 * **刻意不收**的：`dist`（用户放安装包，可能真要找）、`tools`（用户自己的目录名）、
 * `patches`、`logs` —— 这些是用户资产，不能替用户决定跳过。它们靠时间预算兜住。
 */
val DEFAULT_SEARCH_SKIP_DIRS: Set<String> = setOf(
    ".git", ".gradle", ".idea", ".vscode", ".kotlin", ".cxx",
    "build", "node_modules", "__pycache__", ".venv", "venv",
    ".dart_tool", ".pub-cache", ".cache", ".m2", ".npm",
)

/** v248：检索遍历要不要跳过这个目录。顶层纯函数，可直接单测。 */
fun shouldSkipSearchDirectory(name: String): Boolean = name in DEFAULT_SEARCH_SKIP_DIRS

/**
 * v248：一次检索遍历的实况，用来如实告诉模型「有没有扫完」。
 *
 * 为什么必须告知：不告知的话，模型拿到空结果会以为「这个文件不存在」，
 * 于是基于错误前提继续往下做 —— 这比慢更危险。
 */
data class WorkspaceScanReport(
    var visitedEntries: Int = 0,
    var skippedDirectories: Int = 0,
    var stoppedEarly: Boolean = false,
    var stopReason: String? = null,
) {
    /** 给模型看的一句话；扫完了就返回 null（不必啰嗦）。 */
    fun hintOrNull(): String? = when {
        !stoppedEarly -> null
        stopReason == "time-budget" ->
            "搜索超时，只扫了 $visitedEntries 个条目就停了，结果**可能不完整**。请用 path 参数把范围限定到具体目录再搜。"
        else ->
            "搜索达到条目上限（已扫 $visitedEntries 个），结果**可能不完整**。请用 path 参数把范围限定到具体目录再搜。"
    }
}

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
