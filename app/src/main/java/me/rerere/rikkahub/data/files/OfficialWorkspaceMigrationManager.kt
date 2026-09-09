package me.rerere.rikkahub.data.files

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceStorageArea
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

/**
 * 从官方 RikkaHub DocumentsProvider 把“工作区普通文件”复制到指定的二改工作区。
 *
 * 安全与大规模迁移边界：
 * - 只读官方 Provider，永远不写官方数据；
 * - 不用会拒绝合法大工作区的固定文件/目录数量上限；
 * - 深度优先处理目录，只保留当前路径的防循环集合，避免已访问目录永久堆在内存；
 * - 跳过项只在内存保留有限示例，同时准确累计总数；
 * - 续传时跳过二改侧已存在且大小相同的文件；
 * - 写二改侧失败视为真正失败，但半成品保留供续传。
 */
class OfficialWorkspaceMigrationManager(
    private val source: OfficialWorkspaceSource,
    private val destination: OfficialWorkspaceDestination,
    private val officialTreeChecker: (Uri) -> Boolean = OfficialRikkaHubStorage::isOfficialTree,
) {
    suspend fun listCandidates(treeUri: Uri): List<OfficialSourceEntry> = withContext(Dispatchers.IO) {
        require(officialTreeChecker(treeUri)) { "Not an official RikkaHub location" }
        val selectedRoot = source.queryRoot(treeUri)
            ?: error("Unable to read the selected official RikkaHub location")
        if (selectedRoot.documentId == "root") {
            source
                .listChildren(treeUri, selectedRoot.documentId)
                .filter { it.isDirectory }
        } else {
            listOf(selectedRoot).filter { it.isDirectory }
        }
    }

    suspend fun importIntoExisting(
        treeUri: Uri?,
        sourceEntry: OfficialSourceEntry,
        destinationWorkspace: WorkspaceEntity,
        resume: Boolean,
        onProgress: (OfficialWorkspaceMigrationProgress) -> Unit = {},
    ): OfficialWorkspaceMigrationResult = withContext(Dispatchers.IO) {
        require(treeUri == null || officialTreeChecker(treeUri)) { "Not an official RikkaHub location" }
        require(sourceEntry.isDirectory) { "The selected official workspace is not a directory" }

        var processedFiles = 0
        var transferredFiles = 0
        var processedDirectories = 0
        var copiedBytes = 0L
        val skipped = BoundedSkippedEntries()
        val queue = ArrayDeque<DirectoryWorkItem>()
        val activePathDocumentIds = mutableSetOf<String>()
        queue.addFirst(DirectoryWorkItem.Enter(sourceEntry, ""))

        fun emitProgress(
            phase: OfficialWorkspaceMigrationPhase,
            currentItemName: String,
        ) {
            onProgress(
                OfficialWorkspaceMigrationProgress(
                    workspaceName = sourceEntry.name,
                    currentItemName = currentItemName,
                    phase = phase,
                    processedFiles = processedFiles,
                    transferredFiles = transferredFiles,
                    processedDirectories = processedDirectories,
                    copiedBytes = copiedBytes,
                    skippedEntries = skipped.totalCount,
                    resuming = resume,
                )
            )
        }

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            when (val item = queue.removeFirst()) {
                is DirectoryWorkItem.Exit -> {
                    activePathDocumentIds.remove(item.documentId)
                }

                is DirectoryWorkItem.Enter -> {
                    val directory = item.directory
                    val destinationPath = item.destinationPath
                    if (!activePathDocumentIds.add(directory.documentId)) {
                        skipped.add(
                            OfficialWorkspaceSkippedEntry(
                                path = destinationPath.ifBlank { directory.name },
                                operation = "读取文件夹",
                                reason = "检测到目录循环，已跳过该分支",
                            )
                        )
                        continue
                    }
                    queue.addFirst(DirectoryWorkItem.Exit(directory.documentId))
                    processedDirectories++
                    emitProgress(
                        phase = OfficialWorkspaceMigrationPhase.SCANNING_DIRECTORY,
                        currentItemName = destinationPath.ifBlank { directory.name },
                    )

                    val children = try {
                        source.listChildren(treeUri, directory.documentId)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        skipped.add(
                            OfficialWorkspaceSkippedEntry(
                                path = destinationPath.ifBlank { directory.name },
                                operation = "读取文件夹",
                                reason = readableReason(error),
                            )
                        )
                        emptyList()
                    }

                    val childDirectories = ArrayList<DirectoryWorkItem.Enter>()
                    for (child in children) {
                        coroutineContext.ensureActive()
                        val safeName = child.name.replace('/', '_').ifBlank { "unnamed" }
                        val currentPath = joinPath(destinationPath, safeName)
                        if (child.isDirectory) {
                            try {
                                destination.ensureDirectory(
                                    destinationWorkspace.id,
                                    WorkspaceStorageArea.FILES,
                                    currentPath,
                                )
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                throw migrationFailure(
                                    processedFiles,
                                    processedDirectories,
                                    currentPath,
                                    "创建二改文件夹",
                                    error,
                                )
                            }
                            childDirectories += DirectoryWorkItem.Enter(child, currentPath)
                            emitProgress(
                                phase = OfficialWorkspaceMigrationPhase.DISCOVERING_DIRECTORY,
                                currentItemName = currentPath,
                            )
                            continue
                        }

                        emitProgress(
                            phase = if (resume) {
                                OfficialWorkspaceMigrationPhase.CHECKING_EXISTING_FILE
                            } else {
                                OfficialWorkspaceMigrationPhase.COPYING_FILE
                            },
                            currentItemName = currentPath,
                        )

                        if (resume) {
                            val existing = runCatching {
                                destination.existingSize(destinationWorkspace.id, currentPath)
                            }.getOrNull()
                            if (existing != null && child.sizeBytes != null && existing == child.sizeBytes) {
                                processedFiles++
                                copiedBytes += existing
                                continue
                            }
                        }

                        val input = try {
                            source.openInputStream(treeUri, child)
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            skipped.add(
                                OfficialWorkspaceSkippedEntry(
                                    path = currentPath,
                                    operation = "读取官方文件",
                                    reason = readableReason(error),
                                )
                            )
                            continue
                        }
                        if (input == null) {
                            skipped.add(
                                OfficialWorkspaceSkippedEntry(
                                    path = currentPath,
                                    operation = "读取官方文件",
                                    reason = "系统没有返回可读取的文件流",
                                )
                            )
                            continue
                        }
                        try {
                            input.use { sourceStream ->
                                destination.importFile(
                                    id = destinationWorkspace.id,
                                    area = WorkspaceStorageArea.FILES,
                                    destinationPath = destinationPath,
                                    fileName = safeName,
                                    inputStream = sourceStream,
                                )
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            throw migrationFailure(
                                processedFiles,
                                processedDirectories,
                                currentPath,
                                "写入二改工作区",
                                error,
                            )
                        }
                        processedFiles++
                        transferredFiles++
                        copiedBytes += child.sizeBytes ?: 0L
                    }

                    // 逆序压栈后仍按 Provider 给出的顺序逐支深入，避免按层积压整棵目录树。
                    for (childDirectory in childDirectories.asReversed()) {
                        queue.addFirst(childDirectory)
                    }
                }
            }
        }

        OfficialWorkspaceMigrationResult(
            workspace = destinationWorkspace,
            skipped = skipped.samples.toList(),
            skippedCount = skipped.totalCount,
        )
    }

    private fun readableReason(error: Throwable): String =
        error.message?.trim().orEmpty().ifBlank { "系统没有提供具体原因" }

    private fun migrationFailure(
        processedFiles: Int,
        processedDirectories: Int,
        currentPath: String,
        operation: String,
        cause: Throwable,
    ): OfficialWorkspaceMigrationException = OfficialWorkspaceMigrationException(
        copiedFiles = processedFiles,
        copiedDirectories = processedDirectories,
        currentPath = currentPath,
        operation = operation,
        cause = cause,
    )

    private fun joinPath(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent/$child"
}

private sealed interface DirectoryWorkItem {
    data class Enter(
        val directory: OfficialSourceEntry,
        val destinationPath: String,
    ) : DirectoryWorkItem

    data class Exit(val documentId: String) : DirectoryWorkItem
}

private class BoundedSkippedEntries(
    private val sampleLimit: Int = MAX_SKIPPED_SAMPLES,
) {
    var totalCount: Int = 0
        private set
    val samples = mutableListOf<OfficialWorkspaceSkippedEntry>()

    fun add(entry: OfficialWorkspaceSkippedEntry) {
        totalCount++
        if (samples.size < sampleLimit) samples += entry
    }

    private companion object {
        const val MAX_SKIPPED_SAMPLES = 50
    }
}

class OfficialWorkspaceMigrationException(
    val copiedFiles: Int,
    val copiedDirectories: Int,
    val currentPath: String,
    val operation: String,
    cause: Throwable,
) : IllegalStateException(
    OfficialWorkspaceMigrationErrorFormatter.format(
        copiedFiles = copiedFiles,
        copiedDirectories = copiedDirectories,
        currentPath = currentPath,
        operation = operation,
        causeMessage = cause.message,
    ),
    cause,
)

internal object OfficialWorkspaceMigrationErrorFormatter {
    fun format(
        copiedFiles: Int,
        copiedDirectories: Int,
        currentPath: String,
        operation: String,
        causeMessage: String?,
    ): String {
        val reason = causeMessage?.trim().orEmpty().ifBlank { "系统没有提供具体原因" }
        return "复制失败：已处理 $copiedFiles 个文件、$copiedDirectories 个文件夹；" +
            "在$operation“${currentPath.ifBlank { "工作区根目录" }}”时失败；原因：$reason"
    }
}

data class OfficialWorkspaceMigrationResult(
    val workspace: WorkspaceEntity,
    val skipped: List<OfficialWorkspaceSkippedEntry>,
    val skippedCount: Int = skipped.size,
)

data class OfficialWorkspaceSkippedEntry(
    val path: String,
    val operation: String,
    val reason: String,
)

enum class OfficialWorkspaceMigrationPhase {
    SCANNING_DIRECTORY,
    DISCOVERING_DIRECTORY,
    CHECKING_EXISTING_FILE,
    COPYING_FILE,
}

data class OfficialWorkspaceMigrationProgress(
    val workspaceName: String,
    val currentItemName: String,
    val phase: OfficialWorkspaceMigrationPhase,
    val processedFiles: Int,
    val transferredFiles: Int,
    val processedDirectories: Int,
    val copiedBytes: Long,
    val skippedEntries: Int,
    val resuming: Boolean,
)
