package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.OfficialImportSession
import me.rerere.rikkahub.data.files.OfficialSourceEntry
import me.rerere.rikkahub.data.files.OfficialWorkspaceMigrationManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.OfficialTransferService

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val officialMigrationManager: OfficialWorkspaceMigrationManager,
    private val officialImportSession: OfficialImportSession,
    private val context: Context,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 复制任务的进度/结果来自应用级会话，页面被回收也不丢。 */
    val officialImport = officialImportSession.state

    private val _officialMigration = MutableStateFlow(OfficialWorkspaceMigrationUiState())
    val officialMigration = _officialMigration.asStateFlow()

    fun loadOfficialWorkspaces(treeUri: Uri) {
        viewModelScope.launch {
            _officialMigration.update { it.copy(loading = true, loadError = null) }
            runCatching { officialMigrationManager.listCandidates(treeUri) }
                .onSuccess { candidates ->
                    _officialMigration.update {
                        it.copy(loading = false, candidates = candidates, loadError = null)
                    }
                }
                .onFailure { error ->
                    _officialMigration.update {
                        it.copy(loading = false, candidates = emptyList(), loadError = error.message)
                    }
                }
        }
    }

    fun importOfficialWorkspace(treeUri: Uri, source: OfficialSourceEntry) {
        if (officialImportSession.start(treeUri, source)) {
            startTransferService()
        }
    }

    fun resumeOfficialTransfer() {
        if (officialImportSession.resume()) {
            startTransferService()
        }
    }

    fun discardOfficialTransfer() {
        officialImportSession.discard()
    }

    fun cancelOfficialImport() {
        officialImportSession.cancel()
    }

    fun dismissImportOutcome() {
        officialImportSession.dismissOutcome()
    }

    private fun startTransferService() {
        runCatching {
            context.startForegroundService(
                Intent(context, OfficialTransferService::class.java)
            )
        }
    }

    fun clearOfficialMigrationMessage() {
        _officialMigration.update {
            it.copy(loadError = null)
        }
    }

    fun resetOfficialConnectionState() {
        _officialMigration.value = OfficialWorkspaceMigrationUiState()
    }

    fun create(name: String) {
        viewModelScope.launch {
            runCatching { repository.create(name) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            repository.delete(workspace.id)
            terminalSessionManager.closeWorkspace(workspace.root)
        }
    }
}

data class OfficialWorkspaceMigrationUiState(
    val loading: Boolean = false,
    val candidates: List<OfficialSourceEntry> = emptyList(),
    val loadError: String? = null,
)
