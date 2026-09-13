package me.rerere.rikkahub.ui.pages.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    // v302：本地导入/导出改跑在应用级作用域，需要 Context 才能读写用户选中的文件
    private val context: Context,
    private val appScope: AppScope,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    // v302：本地导入/导出的进行状态。放在 ViewModel 而不是页面的 rememberCoroutineScope 里，
    // 页面被切标签 / 旋转 / 重建都不会中断，回到本页还能看到真实结果。
    private val _localTransfer = MutableStateFlow<LocalTransferState>(LocalTransferState.Idle)
    val localTransfer: StateFlow<LocalTransferState> = _localTransfer.asStateFlow()

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value)
        )
        recordBackupTime()
        return file
    }

    // v302：本地导入/导出统一从这里发起，工作跑在应用级作用域（appScope）而不是页面作用域。
    // 根因：原先这两件事跑在页面的 rememberCoroutineScope 上，Compose 1.12 在页面离开组合时
    // 会以 ForgottenCoroutineScopeException（"rememberCoroutineScope left the composition"）取消它，
    // 再被 runCatching 当成"恢复失败"报出来 —— 官方备份导入失败就是这么来的。
    fun importFrom(sourceUri: Uri, kind: LocalTransferKind) = beginLocalTransfer(kind) {
        val file = copyToCache(sourceUri, "import")
        try {
            withContext(Dispatchers.IO) {
                when (kind) {
                    LocalTransferKind.IMPORT_LOCAL -> restoreFromLocalFile(file)
                    LocalTransferKind.IMPORT_CHATBOX -> restoreFromChatBox(file)
                    LocalTransferKind.IMPORT_CHERRY -> restoreFromCherryStudio(file)
                    LocalTransferKind.EXPORT -> Unit
                }
            }
        } finally {
            file.delete()
        }
        // 导入完成后统一提示重启（保持官方行为：本地整包恢复必须重启才生效）
        kind != LocalTransferKind.EXPORT
    }

    fun exportTo(targetUri: Uri) = beginLocalTransfer(LocalTransferKind.EXPORT) {
        val file = exportToFile()
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                } ?: error("Cannot write to the selected file")
            }
        } finally {
            file.delete()
        }
        false
    }

    /** 结果已经报告给用户后清掉，避免回到本页时重复弹提示。 */
    fun consumeLocalTransfer() {
        _localTransfer.value = LocalTransferState.Idle
    }

    private fun beginLocalTransfer(kind: LocalTransferKind, work: suspend () -> Boolean) {
        if (_localTransfer.value is LocalTransferState.Running) {
            Log.i(TAG, "local transfer already running, ignore $kind")
            return
        }
        _localTransfer.value = LocalTransferState.Running(kind)
        appScope.launch {
            try {
                _localTransfer.value = LocalTransferState.Done(kind, needsRestart = work())
            } catch (e: CancellationException) {
                // 取消不是失败：原样上抛，不吞成"恢复失败"（吞掉还会让停止按钮失效）
                _localTransfer.value = LocalTransferState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "local transfer failed: $kind", e)
                _localTransfer.value =
                    LocalTransferState.Failed(kind, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun copyToCache(sourceUri: Uri, prefix: String): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.zip")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: error("Cannot read the selected file")
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    // v302：改成挂起函数并直接等落库 —— 以前走 viewModelScope，ViewModel 一旦被回收这次写入会被丢掉
    suspend fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        settingsStore.update { settings ->
            settings.copy(providers = importProviders + settings.providers)
        }
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)

/** v302：本地导入（含 Chatbox / Cherry Studio）与本地导出的类型。 */
enum class LocalTransferKind {
    IMPORT_LOCAL,
    IMPORT_CHATBOX,
    IMPORT_CHERRY,
    EXPORT,
}

/**
 * v302：本地导入/导出的进行状态。
 * 工作跑在应用级作用域、状态放在 ViewModel 里，页面被回收既不会中断工作，也不会误报"恢复失败"。
 */
sealed interface LocalTransferState {
    data object Idle : LocalTransferState
    data class Running(val kind: LocalTransferKind) : LocalTransferState

    /** [needsRestart] 为真表示要重启 App 才生效（本地整包恢复）。 */
    data class Done(val kind: LocalTransferKind, val needsRestart: Boolean) : LocalTransferState

    data class Failed(val kind: LocalTransferKind, val message: String) : LocalTransferState
}
