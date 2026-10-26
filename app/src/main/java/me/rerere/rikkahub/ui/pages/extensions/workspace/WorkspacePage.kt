package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.OfficialConnectGuideDialog
import me.rerere.rikkahub.ui.components.ui.OfficialConnectFailedDialog
import me.rerere.rikkahub.data.files.SharedStorageManager
import me.rerere.rikkahub.data.files.OfficialSourceEntry
import me.rerere.rikkahub.data.files.OfficialRikkaHubStorage
import me.rerere.rikkahub.data.files.OfficialImportOutcome
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.net.Uri

@Composable
fun WorkspacePage(
    vm: WorkspaceVM = koinViewModel(),
    sharedStorageManager: SharedStorageManager = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    // v1015：这三个局部量是「复制官方工作区」接线要的，合并时被官方签名一起抹掉了。
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()

    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val migrationState by vm.officialMigration.collectAsStateWithLifecycle()
    val importState by vm.officialImport.collectAsStateWithLifecycle()
    // v1015：scrollBehavior / showAddDialog / editTarget / deleteTarget 这四行官方文件里本来就有，
    // 第一次搬接线段时把它们也带了一遍 → Conflicting declarations。这里只留官方那份，勿再加回。
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

    // v1015（二审计出）：connectedName 原先直接在 Composable 里同步查——复制进行中进度约
    // 300ms 推一次就重组一次，每回都要跨进程向官方 App 的 DocumentsProvider 问一次根目录名，
    // 官方 App 卡顿时会拖住主线程。改成 produceState：只在目录句柄变化时到 IO 线程取一次，
    // 取不到就不显示名字（runCatching 兜 binder 异常，不能让它掀翻整页组合）。
    val connectedName by produceState<String?>(initialValue = null, officialTreeUri) {
        value = withContext(Dispatchers.IO) {
            officialTreeUri?.let { uri -> runCatching { sharedStorageManager.queryRoot(uri)?.name }.getOrNull() }
        }
    }

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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    // v1017 合并官方 3.1.1：新建工作区要先选类型（本地 / SSH 远程）
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }

    // v1017 合并官方 3.1.1：没有这个入口，SSH 工作区就建不出来 ——
    // 路由 Screen.WorkspaceSshEditor、编辑器页、DI、数据库 type/ssh_config 都已就位，
    // 唯独缺这里的「新建时选类型」入口（上一轮只做了详情页的分支渲染，属于半成品）。
    if (showTypeSheet) {
        ModalBottomSheet(onDismissRequest = { showTypeSheet = false }) {
            CardGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                title = { Text(stringResource(R.string.workspace_page_create)) },
            ) {
                item(
                    onClick = {
                        showTypeSheet = false
                        showAddDialog = true
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                    supportingContent = { Text(stringResource(R.string.workspace_page_type_local_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_page_type_local)) },
                )
                item(
                    onClick = {
                        showTypeSheet = false
                        navController.navigate(Screen.WorkspaceSshEditor())
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
                    supportingContent = { Text(stringResource(R.string.workspace_page_type_ssh_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_page_type_ssh)) },
                )
            }
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
            FloatingActionButton(onClick = { showTypeSheet = true }) {
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
                    connectedName = connectedName,
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
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_page_empty),
                        description = stringResource(R.string.workspace_page_empty_desc),
                        icon = HugeIcons.FolderCode,
                    )
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceItem(
                    workspace = workspace,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                )
            }
        }
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
}

@Composable
private fun WorkspaceItem(
    workspace: WorkspaceEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(if (workspace.isSsh()) HugeIcons.CloudServer else HugeIcons.FolderCode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workspace.statusLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.common_rename),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onRename,
                    ),
                    ItemAction(
                        text = stringResource(R.string.common_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
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
