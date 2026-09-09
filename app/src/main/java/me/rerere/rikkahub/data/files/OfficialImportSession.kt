package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.DEFAULT_ROOTFS_URL
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/**
 * 应用级“复制官方工作区”会话（可续传）。
 *
 * - 页面关闭后任务仍由应用级 Scope 持有；
 * - UI 进度节流，磁盘心跳使用更长间隔，避免超大迁移时高频写状态；
 * - 取消/失败/被杀后保留半成品；
 * - 继续按钮会立即进入运行态，不再保留一个看似可重复点击的按钮；
 * - 丢弃先等待旧任务结束，再删二改侧半成品，避免旧任务把 interrupted 状态写回来。
 */
class OfficialImportSession(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val migrationManager: OfficialWorkspaceMigrationManager,
    private val destination: OfficialWorkspaceDestination,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val _state = MutableStateFlow(OfficialImportUiState())
    val state: StateFlow<OfficialImportUiState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var operationId: Long = 0L

    init {
        // 上次进程若死在传输中，把状态改成“可续传”，并保留最后心跳供诊断。
        val stored = readState()
        if (stored != null && stored.status == STATUS_RUNNING) {
            writeState(
                stored.copy(
                    status = STATUS_INTERRUPTED,
                    lastError = stored.lastError ?: "应用进程在传输期间结束",
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
        }
        _state.update { it.copy(resumable = currentResumable()) }
    }

    /** 返回 true 表示任务确实已受理。 */
    fun start(treeUri: Uri, source: OfficialSourceEntry): Boolean {
        if (_state.value.importing) return false
        if (currentResumable() != null) {
            _state.update {
                it.copy(
                    outcome = OfficialImportOutcome.Failed(
                        "已有一份未完成的工作区，请先继续传输或丢弃半成品。"
                    )
                )
            }
            return false
        }

        val token = ++operationId
        _state.value = OfficialImportUiState(
            importing = true,
            resuming = false,
            workspaceName = source.name,
        )
        job = appScope.launch {
            val workspace = try {
                val name = uniqueDestinationName(source.name)
                destination.create(name)
            } catch (error: Throwable) {
                if (token != operationId) return@launch
                _state.update {
                    it.copy(
                        importing = false,
                        outcome = OfficialImportOutcome.Failed(
                            "无法创建二改工作区：${error.message ?: "未知错误"}"
                        ),
                    )
                }
                return@launch
            }

            if (token != operationId) return@launch
            val initialState = TransferState(
                workspaceId = workspace.id,
                workspaceName = workspace.name,
                treeUri = treeUri.toString(),
                sourceDocumentId = source.documentId,
                sourceName = source.name,
                status = STATUS_RUNNING,
                updatedAtMillis = System.currentTimeMillis(),
            )
            writeState(initialState)
            _state.update {
                it.copy(
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                    resumable = null,
                )
            }
            runTransfer(treeUri, source, workspace, resume = false, token = token)
        }
        return true
    }

    /** 返回 true 表示确实找到了可继续的任务并已进入运行态。 */
    fun resume(): Boolean {
        if (_state.value.importing) return false
        val stored = readState()
        if (stored == null) {
            _state.update {
                it.copy(outcome = OfficialImportOutcome.Failed("没有找到可继续的传输状态。"))
            }
            return false
        }
        if (stored.status != STATUS_INTERRUPTED && stored.status != STATUS_PAUSED) {
            _state.update {
                it.copy(outcome = OfficialImportOutcome.Failed("当前传输状态不能继续，请重新进入工作区页。"))
            }
            return false
        }
        val treeUri = runCatching { Uri.parse(stored.treeUri) }.getOrNull()
        if (treeUri == null) {
            _state.update {
                it.copy(outcome = OfficialImportOutcome.Failed("官方工作区授权地址已损坏，无法继续。"))
            }
            return false
        }
        val source = OfficialSourceEntry(
            documentId = stored.sourceDocumentId,
            name = stored.sourceName,
            sizeBytes = null,
            isDirectory = true,
        )

        val token = ++operationId
        _state.value = OfficialImportUiState(
            importing = true,
            resuming = true,
            workspaceId = stored.workspaceId,
            workspaceName = stored.workspaceName,
            progress = stored.toProgressOrNull(resuming = true),
        )
        job = appScope.launch {
            val workspace = runCatching { destination.get(stored.workspaceId) }.getOrNull()
            if (workspace == null) {
                if (token != operationId) return@launch
                clearState()
                _state.update {
                    it.copy(
                        importing = false,
                        resumable = null,
                        outcome = OfficialImportOutcome.Failed("要续传的工作区不存在，请重新发起复制。"),
                    )
                }
                return@launch
            }
            writeState(
                stored.copy(
                    status = STATUS_RUNNING,
                    lastError = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
            runTransfer(treeUri, source, workspace, resume = true, token = token)
        }
        return true
    }

    /** 取消=暂停：保留半成品供续传，不删除。 */
    fun cancel(): Boolean {
        val activeJob = job ?: return false
        if (!activeJob.isActive) return false
        activeJob.cancel()
        return true
    }

    /** 丢弃半成品：只删除本次二改侧工作区，不碰官方源。 */
    fun discard() {
        val token = ++operationId
        val oldJob = job
        job = null
        _state.update { it.copy(discarding = true) }
        appScope.launch {
            oldJob?.cancelAndJoin()
            val stored = readState()
            if (stored != null) {
                runCatching { destination.delete(stored.workspaceId) }
            }
            clearState()
            if (token == operationId) {
                _state.value = OfficialImportUiState()
            }
        }
    }

    fun dismissOutcome() {
        _state.update { it.copy(outcome = null) }
    }

    private suspend fun runTransfer(
        treeUri: Uri,
        source: OfficialSourceEntry,
        workspace: WorkspaceEntity,
        resume: Boolean,
        token: Long,
    ) {
        val uiThrottle = ProgressThrottle()
        val heartbeatThrottle = ProgressThrottle(intervalMillis = STATE_HEARTBEAT_INTERVAL_MILLIS)
        var latestProgress: OfficialWorkspaceMigrationProgress? = null

        runCatching {
            migrationManager.importIntoExisting(treeUri, source, workspace, resume) { progress ->
                latestProgress = progress
                if (token != operationId) return@importIntoExisting
                if (uiThrottle.shouldEmit()) {
                    _state.update { it.copy(progress = progress) }
                }
                if (heartbeatThrottle.shouldEmit()) {
                    val current = readState()
                    if (current != null && current.workspaceId == workspace.id) {
                        writeState(current.withProgress(progress))
                    }
                }
            }
        }.onSuccess { result ->
            if (token != operationId) return@onSuccess
            writeState(
                TransferState(
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                    treeUri = treeUri.toString(),
                    sourceDocumentId = source.documentId,
                    sourceName = source.name,
                    status = STATUS_DONE,
                    processedFiles = latestProgress?.processedFiles ?: 0,
                    transferredFiles = latestProgress?.transferredFiles ?: 0,
                    processedDirectories = latestProgress?.processedDirectories ?: 0,
                    skippedEntries = result.skippedCount,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
            job = null
            _state.update {
                it.copy(
                    importing = false,
                    resuming = false,
                    progress = latestProgress,
                    resumable = null,
                    outcome = OfficialImportOutcome.Success(
                        result.workspace,
                        result.skipped,
                        result.skippedCount,
                    ),
                )
            }
            // 复制成功后后台安装 Linux 基础环境，让新工作区从“禁用”变“就绪”。
            appScope.launch {
                runCatching {
                    workspaceRepository.installRootfs(result.workspace.id, DEFAULT_ROOTFS_URL)
                }
            }
        }.onFailure { error ->
            if (token != operationId) return@onFailure
            val status = if (error is CancellationException) STATUS_PAUSED else STATUS_INTERRUPTED
            val current = readState()
            if (current != null && current.workspaceId == workspace.id) {
                writeState(
                    current.copy(
                        status = status,
                        lastError = if (error is CancellationException) {
                            "用户暂停传输"
                        } else {
                            error.message ?: "未知错误"
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    ).let { state ->
                        latestProgress?.let(state::withProgress) ?: state
                    }
                )
            }
            job = null
            _state.update {
                it.copy(
                    importing = false,
                    resuming = false,
                    progress = latestProgress,
                    resumable = currentResumable(),
                    outcome = if (error is CancellationException) {
                        OfficialImportOutcome.Cancelled
                    } else {
                        OfficialImportOutcome.Failed(error.message ?: "未知错误")
                    },
                )
            }
        }
    }

    private fun currentResumable(): OfficialTransferInfo? {
        val stored = readState() ?: return null
        if (stored.status != STATUS_INTERRUPTED && stored.status != STATUS_PAUSED) return null
        return OfficialTransferInfo(
            workspaceId = stored.workspaceId,
            workspaceName = stored.workspaceName,
            processedFiles = stored.processedFiles,
            processedDirectories = stored.processedDirectories,
            currentItemName = stored.currentItemName,
            lastError = stored.lastError,
        )
    }

    private suspend fun uniqueDestinationName(sourceName: String): String {
        val base = "官方 - ${sourceName.trim().ifBlank { "工作区" }}"
        var candidate = base
        var suffix = 2
        while (destination.isNameTaken(candidate, excludeId = null)) {
            candidate = "$base ($suffix)"
            suffix++
        }
        return candidate
    }

    private fun stateFile(): File = File(context.filesDir, "official_transfer_state.json")

    private fun readState(): TransferState? = runCatching {
        val file = stateFile()
        if (!file.exists()) return null
        JsonInstant.decodeFromString<TransferState>(file.readText())
    }.getOrNull()

    /** 同目录临时文件写完再替换，降低进程被杀时留下半截 JSON 的概率。 */
    private fun writeState(state: TransferState) {
        runCatching {
            val target = stateFile()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(JsonInstant.encodeToString(TransferState.serializer(), state))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun clearState() {
        runCatching { stateFile().delete() }
        runCatching { File(stateFile().parentFile, "${stateFile().name}.tmp").delete() }
    }

    private companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_PAUSED = "paused"
        const val STATUS_DONE = "done"
        const val STATE_HEARTBEAT_INTERVAL_MILLIS = 2_000L
    }
}

@Serializable
data class TransferState(
    val workspaceId: String,
    val workspaceName: String,
    val treeUri: String,
    val sourceDocumentId: String,
    val sourceName: String,
    val status: String,
    val phase: String? = null,
    val processedFiles: Int = 0,
    val transferredFiles: Int = 0,
    val processedDirectories: Int = 0,
    val copiedBytes: Long = 0L,
    val skippedEntries: Int = 0,
    val currentItemName: String? = null,
    val lastError: String? = null,
    val updatedAtMillis: Long = 0L,
) {
    fun withProgress(progress: OfficialWorkspaceMigrationProgress): TransferState = copy(
        phase = progress.phase.name,
        processedFiles = progress.processedFiles,
        transferredFiles = progress.transferredFiles,
        processedDirectories = progress.processedDirectories,
        copiedBytes = progress.copiedBytes,
        skippedEntries = progress.skippedEntries,
        currentItemName = progress.currentItemName,
        updatedAtMillis = System.currentTimeMillis(),
    )

    fun toProgressOrNull(resuming: Boolean): OfficialWorkspaceMigrationProgress? {
        val item = currentItemName ?: return null
        val parsedPhase = phase?.let {
            runCatching { OfficialWorkspaceMigrationPhase.valueOf(it) }.getOrNull()
        } ?: OfficialWorkspaceMigrationPhase.SCANNING_DIRECTORY
        return OfficialWorkspaceMigrationProgress(
            workspaceName = sourceName,
            currentItemName = item,
            phase = parsedPhase,
            processedFiles = processedFiles,
            transferredFiles = transferredFiles,
            processedDirectories = processedDirectories,
            copiedBytes = copiedBytes,
            skippedEntries = skippedEntries,
            resuming = resuming,
        )
    }
}

data class OfficialTransferInfo(
    val workspaceId: String,
    val workspaceName: String,
    val processedFiles: Int = 0,
    val processedDirectories: Int = 0,
    val currentItemName: String? = null,
    val lastError: String? = null,
)

data class OfficialImportUiState(
    val importing: Boolean = false,
    val resuming: Boolean = false,
    val discarding: Boolean = false,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val progress: OfficialWorkspaceMigrationProgress? = null,
    val outcome: OfficialImportOutcome? = null,
    val resumable: OfficialTransferInfo? = null,
)

sealed interface OfficialImportOutcome {
    data class Success(
        val workspace: WorkspaceEntity,
        val skipped: List<OfficialWorkspaceSkippedEntry>,
        val skippedCount: Int = skipped.size,
    ) : OfficialImportOutcome

    data class Failed(val reason: String) : OfficialImportOutcome

    data object Cancelled : OfficialImportOutcome
}

/** 进度节流：同一间隔内最多推送一次，从根上防止高频推送造成界面消息堆积。 */
internal class ProgressThrottle(
    private val intervalMillis: Long = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEmitMillis = Long.MIN_VALUE / 2

    fun shouldEmit(): Boolean {
        val now = clock()
        if (now - lastEmitMillis >= intervalMillis) {
            lastEmitMillis = now
            return true
        }
        return false
    }
}
