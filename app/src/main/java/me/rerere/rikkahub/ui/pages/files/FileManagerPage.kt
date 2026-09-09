package me.rerere.rikkahub.ui.pages.files

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SharedStorageEntry
import me.rerere.rikkahub.data.files.SharedStorageManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.util.Locale

@Composable
fun FileManagerPage(
    filesManager: FilesManager = koinInject(),
    sharedStorageManager: SharedStorageManager = koinInject(),
    settingsStore: SettingsStore = koinInject(),
    workspaceRepository: WorkspaceRepository = koinInject(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTreeUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pathStack by remember { mutableStateOf(emptyList<SharedStorageEntry>()) }
    var entries by remember { mutableStateOf(emptyList<SharedStorageEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SharedStorageEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<SharedStorageEntry?>(null) }
    var exportTarget by remember { mutableStateOf<SharedStorageEntry?>(null) }

    val selectedTreeUri = selectedTreeUriString?.let(Uri::parse)
    val currentDocumentId = selectedTreeUri?.let { treeUri ->
        pathStack.lastOrNull()?.documentId ?: sharedStorageManager.rootDocumentId(treeUri)
    }
    val currentTitle = pathStack.lastOrNull()?.name
        ?: selectedTreeUri?.let { sharedStorageManager.queryRoot(it)?.name }
        ?: stringResource(R.string.file_manager_title)

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sharedStorageManager.persistTreeUri(uri)
        scope.launch {
            settingsStore.update { current ->
                current.copy(
                    sharedStorageTreeUris = (current.sharedStorageTreeUris + uri.toString()).distinct()
                )
            }
            selectedTreeUriString = uri.toString()
            pathStack = emptyList()
            refreshToken++
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        val treeUri = selectedTreeUri
        val parentId = currentDocumentId
        if (sourceUri == null || treeUri == null || parentId == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val displayName = sharedStorageManager.displayName(sourceUri)
                    ?: sourceUri.lastPathSegment
                    ?: "imported_file"
                sharedStorageManager.importFile(
                    treeUri = treeUri,
                    parentDocumentId = parentId,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    mimeType = sharedStorageManager.mimeType(sourceUri),
                )
            }.onSuccess {
                refreshToken++
            }.onFailure {
                error = it.message ?: context.getString(R.string.file_manager_operation_failed)
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri ->
        val target = exportTarget.also { exportTarget = null }
        if (destinationUri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = sharedStorageManager.openInputStream(target.uri)
                        ?: error("Unable to open source file")
                    val output = context.contentResolver.openOutputStream(destinationUri)
                        ?: error("Unable to open destination file")
                    input.use { source -> output.use { destination -> source.copyTo(destination) } }
                }
            }.onFailure {
                error = it.message ?: context.getString(R.string.file_manager_operation_failed)
            }
        }
    }

    LaunchedEffect(selectedTreeUriString, currentDocumentId, refreshToken) {
        val treeUri = selectedTreeUri
        val documentId = currentDocumentId
        if (treeUri == null || documentId == null) {
            entries = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                sharedStorageManager.listChildren(treeUri, documentId)
            }
        }.onSuccess { result ->
            entries = result.sortedWith(compareBy<SharedStorageEntry> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) })
            loading = false
        }.onFailure {
            entries = emptyList()
            loading = false
            error = it.message ?: context.getString(R.string.file_manager_load_failed)
        }
    }

    BackHandler(enabled = selectedTreeUri != null) {
        if (pathStack.isNotEmpty()) {
            pathStack = pathStack.dropLast(1)
        } else {
            selectedTreeUriString = null
        }
    }

    if (showNewFolderDialog && selectedTreeUri != null && currentDocumentId != null) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                showNewFolderDialog = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            sharedStorageManager.createFolder(selectedTreeUri, currentDocumentId, name)
                                ?: error("Unable to create folder")
                        }
                    }.onSuccess { refreshToken++ }.onFailure {
                        error = it.message ?: context.getString(R.string.file_manager_operation_failed)
                    }
                }
            },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            sharedStorageManager.rename(target, name) ?: error("Unable to rename")
                        }
                    }.onSuccess { refreshToken++ }.onFailure {
                        error = it.message ?: context.getString(R.string.file_manager_operation_failed)
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.file_manager_delete_title)) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    check(sharedStorageManager.delete(target)) { "Unable to delete" }
                                }
                            }.onSuccess { refreshToken++ }.onFailure {
                                error = it.message ?: context.getString(R.string.file_manager_operation_failed)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton() },
                actions = {
                    if (selectedTreeUri != null) {
                        IconButton(onClick = { refreshToken++ }) {
                            Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.file_manager_refresh))
                        }
                        IconButton(onClick = { treePicker.launch(selectedTreeUri) }) {
                            Icon(HugeIcons.Folder01, contentDescription = stringResource(R.string.file_manager_add_root))
                        }
                    } else {
                        IconButton(onClick = { treePicker.launch(null) }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.file_manager_add_root))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            if (selectedTreeUri != null) {
                FloatingActionButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                    Icon(HugeIcons.FileImport, contentDescription = stringResource(R.string.file_manager_import))
                }
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
            if (selectedTreeUri == null) {
                item {
                    ManagerIntroCard(
                        onAddRoot = { treePicker.launch(null) },
                    )
                }
                item { SectionTitle(stringResource(R.string.file_manager_internal_section)) }
                item {
                    ManagerEntryCard(
                        icon = HugeIcons.File02,
                        title = stringResource(R.string.file_manager_chat_files),
                        description = stringResource(R.string.file_manager_chat_files_desc),
                        onClick = { navController.navigate(Screen.SettingFiles) },
                    )
                }
                item { SectionTitle(stringResource(R.string.file_manager_workspace_section)) }
                if (workspaces.isEmpty()) {
                    item {
                        ManagerEntryCard(
                            icon = HugeIcons.Folder01,
                            title = stringResource(R.string.file_manager_no_workspace),
                            description = stringResource(R.string.file_manager_no_workspace_desc),
                            onClick = { navController.navigate(Screen.Workspaces) },
                        )
                    }
                } else {
                    items(workspaces, key = { it.id }) { workspace ->
                        ManagerEntryCard(
                            icon = HugeIcons.Folder01,
                            title = workspace.name,
                            description = stringResource(R.string.file_manager_workspace_desc),
                            onClick = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                        )
                    }
                }
                item { SectionTitle(stringResource(R.string.file_manager_shared_section)) }
                if (settings.sharedStorageTreeUris.isEmpty()) {
                    item {
                        ManagerEntryCard(
                            icon = HugeIcons.Folder01,
                            title = stringResource(R.string.file_manager_no_shared_root),
                            description = stringResource(R.string.file_manager_no_shared_root_desc),
                            onClick = { treePicker.launch(null) },
                        )
                    }
                } else {
                    items(settings.sharedStorageTreeUris, key = { it }) { uriString ->
                        val uri = Uri.parse(uriString)
                        val rootName = sharedStorageManager.queryRoot(uri)?.name
                            ?: stringResource(R.string.file_manager_shared_root)
                        ManagerEntryCard(
                            icon = HugeIcons.Folder01,
                            title = rootName,
                            description = uriString,
                            onClick = {
                                selectedTreeUriString = uriString
                                pathStack = emptyList()
                                refreshToken++
                            },
                            onRemove = {
                                sharedStorageManager.releaseTreeUri(uri)
                                scope.launch {
                                    settingsStore.update { current ->
                                        current.copy(sharedStorageTreeUris = current.sharedStorageTreeUris.filterNot { it == uriString })
                                    }
                                }
                            },
                        )
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            enabled = pathStack.isNotEmpty(),
                            onClick = { if (pathStack.isNotEmpty()) pathStack = pathStack.dropLast(1) },
                        ) {
                            Icon(HugeIcons.ArrowTurnBackward, contentDescription = stringResource(R.string.file_manager_up))
                        }
                        Text(
                            text = if (pathStack.isEmpty()) "/" else pathStack.joinToString(" / ") { it.name },
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { showNewFolderDialog = true }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.file_manager_new_folder))
                        }
                    }
                }
                error?.let { message ->
                    item { ErrorCard(message) }
                }
                if (loading) {
                    item { Text(stringResource(R.string.file_manager_loading)) }
                } else if (entries.isEmpty() && error == null) {
                    item { EmptyState() }
                }
                items(entries, key = { it.documentId }) { entry ->
                    SharedEntryCard(
                        entry = entry,
                        onOpen = {
                            if (entry.isDirectory) {
                                pathStack = pathStack + entry
                            } else {
                                openExternal(context, entry)
                            }
                        },
                        onExport = { exportTarget = entry; exportPicker.launch(entry.name) },
                        onShare = { shareExternal(context, entry) },
                        onRename = { renameTarget = entry },
                        onDelete = { deleteTarget = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagerIntroCard(onAddRoot: () -> Unit) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.file_manager_intro_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.file_manager_intro_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAddRoot) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.file_manager_add_root))
            }
        }
    }
}

@Composable
private fun ManagerEntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.file_manager_remove_root))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SharedEntryCard(
    entry: SharedStorageEntry,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = if (entry.isDirectory) {
                    stringResource(R.string.file_manager_folder)
                } else {
                    entry.sizeBytes?.fileSizeToString() ?: entry.mimeType
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = { Icon(HugeIcons.FileImport, contentDescription = null) },
                            onClick = { menuExpanded = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = { Icon(HugeIcons.Share08, contentDescription = null) },
                            onClick = { menuExpanded = false; onShare() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_rename)) },
                        leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(HugeIcons.Delete01, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_manager_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.file_manager_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.common_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RenameDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_rename)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(HugeIcons.Folder01, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.file_manager_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    }
}

private fun openExternal(context: android.content.Context, entry: SharedStorageEntry) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(entry.uri, entry.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun shareExternal(context: android.content.Context, entry: SharedStorageEntry) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = entry.mimeType
        putExtra(Intent.EXTRA_STREAM, entry.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
