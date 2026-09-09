package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.OfficialRikkaHubStorage
import me.rerere.rikkahub.data.files.OfficialImportOutcome
import me.rerere.rikkahub.data.files.OfficialSourceEntry
import me.rerere.rikkahub.data.files.OfficialTransferInfo
import me.rerere.rikkahub.data.files.OfficialWorkspaceMigrationPhase
import me.rerere.rikkahub.data.files.OfficialWorkspaceMigrationProgress
import me.rerere.rikkahub.data.files.SharedStorageManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.OfficialConnectFailedDialog
import me.rerere.rikkahub.ui.components.ui.OfficialConnectGuideDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun WorkspacePage(
    vm: WorkspaceVM = koinViewModel(),
    sharedStorageManager: SharedStorageManager = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val migrationState by vm.officialMigration.collectAsStateWithLifecycle()
    val importState by vm.officialImport.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var showOfficialPicker by remember { mutableStateOf(false) }
    var showOfficialConnectGuide by remember { mutableStateOf(false) }
    var showOfficialDisconnectConfirm by remember { mutableStateOf(false) }
    var showOpenOfficialConfirm by remember { mutableStateOf(false) }
    var showDiscardTransferConfirm by remember { mutableStateOf(false) }
    var pendingOfficialImport by remember { mutableStateOf<OfficialSourceEntry?>(null) }
    var officialConnectionError by remember { mutableStateOf<String?>(null) }

    val officialTreeUri = settings.sharedStorageTreeUris
        .map(Uri::parse)
        .firstOrNull(OfficialRikkaHubStorage::isOfficialTree)

    fun openOfficialApp() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(OfficialRikkaHubStorage.PACKAGE_NAME)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.workspace_official_app_missing),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun refreshOfficial() {
        val uri = officialTreeUri ?: return
        vm.loadOfficialWorkspaces(uri)
        // 官方 Provider 首次唤醒可能较慢，延迟再查一次，减少“空白”误报。
        scope.launch {
            delay(600)
            vm.loadOfficialWorkspaces(uri)
        }
    }

    fun replaceOfficialTreeUri(newUri: Uri) {
        val oldUri = officialTreeUri
        sharedStorageManager.persistTreeUri(newUri, readOnly = true)
        scope.launch {
            settingsStore.update { current ->
                current.copy(
                    sharedStorageTreeUris = (
                        current.sharedStorageTreeUris
                            .filterNot { OfficialRikkaHubStorage.isOfficialTree(Uri.parse(it)) } +
                        newUri.toString()
                        ).distinct()
                )
            }
            vm.resetOfficialConnectionState()
            vm.loadOfficialWorkspaces(newUri)
            showOfficialPicker = true
            if (oldUri != null && oldUri != newUri) {
                sharedStorageManager.releaseTreeUri(oldUri, readOnly = true)
            }
        }
    }

    val officialFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!OfficialRikkaHubStorage.isOfficialTree(uri)) {
            officialConnectionError = "wrong_location"
            return@rememberLauncherForActivityResult
        }
        replaceOfficialTreeUri(uri)
    }

    LaunchedEffect(officialTreeUri) {
        officialTreeUri?.let(vm::loadOfficialWorkspaces)
    }

    LaunchedEffect(importState.outcome) {
        if (importState.outcome != null) {
            pendingOfficialImport = null
            showOfficialPicker = false
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.workspace_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OfficialRikkaHubWorkspaceCard(
                    connected = officialTreeUri != null,
                    connectedName = officialTreeUri
                        ?.let { sharedStorageManager.queryRoot(it)?.name },
                    importing = importState.importing,
                    resuming = importState.resuming,
                    discarding = importState.discarding,
                    progress = importState.progress,
                    resumable = importState.resumable,
                    onConnect = { showOfficialConnectGuide = true },
                    onReselect = { showOfficialConnectGuide = true },
                    onDisconnect = { showOfficialDisconnectConfirm = true },
                    onOpenOfficial = { showOpenOfficialConfirm = true },
                    onRefresh = { refreshOfficial() },
                    onResume = { vm.resumeOfficialTransfer() },
                    onPause = { vm.cancelOfficialImport() },
                    onDiscard = { showDiscardTransferConfirm = true },
                    onImport = {
                        officialTreeUri?.let(vm::loadOfficialWorkspaces)
                        showOfficialPicker = true
                    },
                )
            }

            if (workspaces.isEmpty()) {
                item {
                    EmptyWorkspaceState()
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                )
            }
        }
    }

    if (showOfficialPicker) {
        AlertDialog(
            onDismissRequest = { if (!importState.importing) showOfficialPicker = false },
            title = { Text(stringResource(R.string.workspace_official_import_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.workspace_official_import_desc))
                    Text(
                        stringResource(R.string.workspace_official_import_keep_open),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        migrationState.loading -> CircularProgressIndicator()
                        migrationState.loadError != null -> Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.workspace_official_import_load_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                migrationState.loadError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { openOfficialApp() }) {
                                Text(stringResource(R.string.workspace_official_open_confirm))
                            }
                            OutlinedButton(onClick = { refreshOfficial() }) {
                                Text(stringResource(R.string.workspace_official_refresh))
                            }
                        }
                        migrationState.candidates.isEmpty() -> Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.workspace_official_import_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { openOfficialApp() }) {
                                Text(stringResource(R.string.workspace_official_open_confirm))
                            }
                            OutlinedButton(onClick = { refreshOfficial() }) {
                                Text(stringResource(R.string.workspace_official_refresh))
                            }
                        }
                        else -> migrationState.candidates.forEach { candidate ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !importState.importing) {
                                        showOfficialPicker = false
                                        pendingOfficialImport = candidate
                                    },
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(candidate.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        stringResource(R.string.workspace_official_import_action),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.workspace_official_import_files_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    enabled = !importState.importing,
                    onClick = { showOfficialPicker = false },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingOfficialImport?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingOfficialImport = null },
            title = { Text(stringResource(R.string.workspace_official_import_confirm_title)) },
            text = {
                Text(stringResource(R.string.workspace_official_import_confirm_desc, candidate.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingOfficialImport = null
                        showOfficialPicker = false
                        officialTreeUri?.let { vm.importOfficialWorkspace(it, candidate) }
                    },
                ) {
                    Text(stringResource(R.string.workspace_official_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOfficialImport = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    (importState.outcome as? OfficialImportOutcome.Success)?.let { success ->
        AlertDialog(
            onDismissRequest = { vm.dismissImportOutcome() },
            title = {
                Text(
                    if (success.skippedCount == 0) {
                        stringResource(R.string.workspace_official_import_success_title)
                    } else {
                        stringResource(R.string.workspace_official_import_success_partial_title)
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.workspace_official_import_success_desc, success.workspace.name),
                    )
                    Text(
                        stringResource(R.string.workspace_official_import_rootfs_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (success.skippedCount > 0) {
                        Text(
                            stringResource(
                                R.string.workspace_official_import_skipped_desc,
                                success.skippedCount,
                                success.skipped.size,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                        success.skipped.forEach { entry ->
                            Text(
                                "${entry.path}（${entry.operation}：${entry.reason}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissImportOutcome()
                    navController.navigate(Screen.WorkspaceDetail(success.workspace.id))
                }) {
                    Text(stringResource(R.string.workspace_official_import_open))
                }
            },
        )
    }

    (importState.outcome as? OfficialImportOutcome.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = { vm.dismissImportOutcome() },
            title = { Text(stringResource(R.string.workspace_official_import_failed_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(failed.reason)
                    Text(
                        stringResource(R.string.workspace_official_import_failed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissImportOutcome() }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    if (importState.outcome is OfficialImportOutcome.Cancelled) {
        AlertDialog(
            onDismissRequest = { vm.dismissImportOutcome() },
            title = { Text(stringResource(R.string.workspace_official_import_cancelled_title)) },
            text = { Text(stringResource(R.string.workspace_official_import_cancelled_desc)) },
            confirmButton = {
                TextButton(onClick = { vm.dismissImportOutcome() }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    if (showOfficialConnectGuide) {
        OfficialConnectGuideDialog(
            onStart = {
                showOfficialConnectGuide = false
                officialFolderPicker.launch(OfficialRikkaHubStorage.initialRootUri)
            },
            onDismiss = { showOfficialConnectGuide = false },
        )
    }

    if (showDiscardTransferConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.workspace_official_transfer_discard_title),
            confirmText = stringResource(R.string.workspace_official_transfer_discard),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                showDiscardTransferConfirm = false
                vm.discardOfficialTransfer()
            },
            onDismiss = { showDiscardTransferConfirm = false },
        ) {
            Text(stringResource(R.string.workspace_official_transfer_discard_desc))
        }
    }

    if (showOfficialDisconnectConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.workspace_official_disconnect_title),
            confirmText = stringResource(R.string.workspace_official_disconnect_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                showOfficialDisconnectConfirm = false
                officialTreeUri?.let { uri ->
                    scope.launch {
                        settingsStore.update { current ->
                            current.copy(
                                sharedStorageTreeUris = current.sharedStorageTreeUris
                                    .filterNot {
                                        OfficialRikkaHubStorage.isOfficialTree(Uri.parse(it))
                                    },
                            )
                        }
                        vm.resetOfficialConnectionState()
                        sharedStorageManager.releaseTreeUri(uri, readOnly = true)
                    }
                }
            },
            onDismiss = { showOfficialDisconnectConfirm = false },
        ) {
            Text(stringResource(R.string.workspace_official_disconnect_desc))
        }
    }

    if (showOpenOfficialConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.workspace_official_open_title),
            confirmText = stringResource(R.string.workspace_official_open_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                showOpenOfficialConfirm = false
                openOfficialApp()
            },
            onDismiss = { showOpenOfficialConfirm = false },
        ) {
            Text(stringResource(R.string.workspace_official_open_desc))
        }
    }

    if (officialConnectionError != null) {
        OfficialConnectFailedDialog(
            onRetry = {
                officialConnectionError = null
                officialFolderPicker.launch(OfficialRikkaHubStorage.initialRootUri)
            },
            onDismiss = { officialConnectionError = null },
        )
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                vm.create(name)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            onDismiss = { editTarget = null },
            onConfirm = { name ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }
}

@Composable
private fun OfficialRikkaHubWorkspaceCard(
    connected: Boolean,
    connectedName: String?,
    importing: Boolean,
    resuming: Boolean,
    discarding: Boolean,
    progress: OfficialWorkspaceMigrationProgress?,
    resumable: OfficialTransferInfo?,
    onConnect: () -> Unit,
    onReselect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenOfficial: () -> Unit,
    onRefresh: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.workspace_official_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (connected) {
                    stringResource(R.string.workspace_official_card_connected)
                } else {
                    stringResource(R.string.workspace_official_card_disconnected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connected && connectedName != null) {
                Text(
                    stringResource(R.string.workspace_official_selected, connectedName),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (importing) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(
                            if (resuming) {
                                R.string.workspace_official_transfer_resuming
                            } else {
                                R.string.workspace_official_transfer_running
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                progress?.let {
                    Text(
                        stringResource(
                            R.string.workspace_official_transfer_progress_detail,
                            it.processedFiles,
                            it.transferredFiles,
                            it.processedDirectories,
                            it.skippedEntries,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            when (it.phase) {
                                OfficialWorkspaceMigrationPhase.SCANNING_DIRECTORY ->
                                    R.string.workspace_official_transfer_phase_scanning
                                OfficialWorkspaceMigrationPhase.DISCOVERING_DIRECTORY ->
                                    R.string.workspace_official_transfer_phase_discovering
                                OfficialWorkspaceMigrationPhase.CHECKING_EXISTING_FILE ->
                                    R.string.workspace_official_transfer_phase_checking
                                OfficialWorkspaceMigrationPhase.COPYING_FILE ->
                                    R.string.workspace_official_transfer_phase_copying
                            },
                            it.currentItemName,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_official_transfer_pause))
                }
            } else if (resumable != null) {
                Text(
                    stringResource(R.string.workspace_official_transfer_interrupted, resumable.workspaceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (resumable.processedFiles > 0 || resumable.processedDirectories > 0) {
                    Text(
                        stringResource(
                            R.string.workspace_official_transfer_saved_progress,
                            resumable.processedFiles,
                            resumable.processedDirectories,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                resumable.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        stringResource(R.string.workspace_official_transfer_last_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onResume,
                        enabled = !discarding,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_official_transfer_resume))
                    }
                    OutlinedButton(
                        onClick = onDiscard,
                        enabled = !discarding,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (discarding) {
                                    R.string.workspace_official_transfer_discarding
                                } else {
                                    R.string.workspace_official_transfer_discard
                                }
                            )
                        )
                    }
                }
            }

            Button(
                onClick = if (connected) onImport else onConnect,
                enabled = !importing && !discarding && resumable == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (connected) {
                        stringResource(R.string.workspace_official_card_import)
                    } else {
                        stringResource(R.string.workspace_official_card_connect)
                    }
                )
            }
            if (connected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onReselect,
                        enabled = !importing && resumable == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_official_reselect))
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !importing && resumable == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_official_disconnect))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenOfficial,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_official_open_confirm))
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !importing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_official_refresh))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.File02,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_page_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_page_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.File02,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = workspace.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = workspace.shellStatus.toShellStatusLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_page_name)) },
                singleLine = true,
                isError = isDuplicate,
                supportingText = if (isDuplicate) {
                    { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = name.isNotBlank() && !isDuplicate,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
