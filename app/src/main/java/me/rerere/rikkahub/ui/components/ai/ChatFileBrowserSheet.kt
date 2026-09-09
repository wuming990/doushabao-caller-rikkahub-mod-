package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileKind
import me.rerere.rikkahub.data.files.LocalFileEntry
import me.rerere.rikkahub.data.files.LocalStorageManager
import me.rerere.rikkahub.data.files.LocalStoragePlace
import me.rerere.rikkahub.data.files.LocalStoragePlaceKind
import me.rerere.rikkahub.data.files.OfficialRikkaHubStorage
import me.rerere.rikkahub.data.files.SharedStorageEntry
import me.rerere.rikkahub.data.files.SharedStorageManager
import me.rerere.rikkahub.data.files.fileKindOfMime
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface BrowserLocation {
    data object Home : BrowserLocation

    data object RecentFiles : BrowserLocation

    data class Local(
        val path: String,
        val advanced: Boolean = false,
        val rootBoundary: String? = null,
    ) : BrowserLocation

    data class Shared(
        val treeUri: String,
        val path: List<SharedStorageEntry> = emptyList(),
    ) : BrowserLocation
}

private sealed interface BrowserSelection {
    val key: String

    data class LocalFile(val entry: LocalFileEntry) : BrowserSelection {
        override val key: String get() = "local:${entry.path}"
    }

    data class SharedFile(val entry: SharedStorageEntry) : BrowserSelection {
        override val key: String get() = "shared:${entry.uri}"
    }
}

@Composable
internal fun ChatFileBrowserSheet(
    onDismiss: () -> Unit,
    onFilesSelected: (List<Uri>) -> Unit,
    sharedStorageManager: SharedStorageManager = koinInject(),
    localStorageManager: LocalStorageManager = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()

    // v259：打开后直接落在「最近文件」列表，不再先看一堆入口卡片。
    var location by remember { mutableStateOf<BrowserLocation>(BrowserLocation.RecentFiles) }
    var localEntries by remember { mutableStateOf(emptyList<LocalFileEntry>()) }
    var localRoots by remember { mutableStateOf(emptyList<File>()) }
    var commonPlaces by remember { mutableStateOf(emptyList<LocalStoragePlace>()) }
    var recentEntries by remember { mutableStateOf(emptyList<LocalFileEntry>()) }
    var sharedEntries by remember { mutableStateOf(emptyList<SharedStorageEntry>()) }
    var searchResults by remember { mutableStateOf(emptyList<LocalFileEntry>()) }
    var selected by remember { mutableStateOf<Map<String, BrowserSelection>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    var showAllFilesPermissionDialog by remember { mutableStateOf(false) }
    var pendingPermissionLocation by remember { mutableStateOf<BrowserLocation?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    var homeQuery by remember { mutableStateOf("") }
    var recentQuery by remember { mutableStateOf("") }

    val otherAuthorizedTrees = remember(settings.sharedStorageTreeUris) {
        settings.sharedStorageTreeUris.filterNot {
            OfficialRikkaHubStorage.isOfficialTree(Uri.parse(it))
        }
    }

    // 零权限退路：直接用系统自带文件选择器（自带搜索、最近文件、多选）
    val systemFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onFilesSelected(uris)
            onDismiss()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
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
            location = BrowserLocation.Shared(uri.toString())
            refreshToken++
        }
    }

    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (localStorageManager.hasAllFilesAccess()) {
            pendingPermissionLocation?.let { location = it }
            pendingPermissionLocation = null
            refreshToken++
        }
    }

    // v259：打开即落在最近文件。没有「所有文件」权限时沿用原来的说明弹窗，
    // 并保留「改用系统选择器」退路，避免列表空白却不知道该怎么办。
    LaunchedEffect(Unit) {
        if (!localStorageManager.hasAllFilesAccess()) {
            pendingPermissionLocation = BrowserLocation.RecentFiles
            showAllFilesPermissionDialog = true
        }
    }

    fun requestPermissionLocation(target: BrowserLocation) {
        if (localStorageManager.hasAllFilesAccess()) {
            location = target
        } else {
            pendingPermissionLocation = target
            showAllFilesPermissionDialog = true
        }
    }

    LaunchedEffect(location, refreshToken) {
        loading = true
        errorMessage = null
        when (val current = location) {
            BrowserLocation.Home -> {
                localEntries = emptyList()
                recentEntries = emptyList()
                sharedEntries = emptyList()
                localRoots = localStorageManager.storageRoots()
                commonPlaces = localStorageManager.commonPlaces()
                loading = false
            }

            BrowserLocation.RecentFiles -> {
                localEntries = emptyList()
                sharedEntries = emptyList()
                // 实际加载与搜索由下面的专用 effect 负责（带防抖）。
                // 这里保持 loading = true，避免列表在结果回来前先闪出“没有文件”。
            }

            is BrowserLocation.Local -> {
                if (current.path.isBlank()) {
                    localEntries = emptyList()
                    localRoots = localStorageManager.storageRoots()
                    loading = false
                } else {
                    withContext(Dispatchers.IO) {
                        localStorageManager.list(File(current.path), advanced = current.advanced)
                    }.let { entries ->
                        localEntries = entries
                        loading = false
                    }
                }
            }

            is BrowserLocation.Shared -> {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val treeUri = Uri.parse(current.treeUri)
                        val parentId = current.path.lastOrNull()?.documentId
                            ?: sharedStorageManager.rootDocumentId(treeUri)
                        sharedStorageManager.listChildren(treeUri, parentId)
                    }
                }.onSuccess { entries ->
                    sharedEntries = entries.sortedWith(
                        compareBy<SharedStorageEntry> { !it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.getDefault()) }
                    )
                }.onFailure {
                    sharedEntries = emptyList()
                    errorMessage = it.message ?: context.getString(R.string.chat_file_browser_load_failed)
                }
                loading = false
            }
        }
    }

    // “最近文件”加载与搜索：交给系统媒体库索引查询，输入时防抖，避免每敲一个字都查一次。
    LaunchedEffect(location, recentQuery, refreshToken) {
        if (location != BrowserLocation.RecentFiles) return@LaunchedEffect
        loading = true
        if (recentQuery.isNotBlank()) delay(250)
        val result = withContext(Dispatchers.IO) {
            localStorageManager.recentFiles(query = recentQuery)
        }
        recentEntries = result
        loading = false
    }

    // 首页搜索：常用目录 + 手机存储根目录一层
    LaunchedEffect(location, homeQuery, refreshToken) {
        if (location == BrowserLocation.Home && homeQuery.isNotBlank()) {
            searchResults = withContext(Dispatchers.IO) {
                localStorageManager.search(homeQuery)
            }
        } else {
            searchResults = emptyList()
        }
    }

    fun goBack() {
        location = when (val current = location) {
            BrowserLocation.Home -> BrowserLocation.RecentFiles
            BrowserLocation.RecentFiles -> BrowserLocation.RecentFiles
            is BrowserLocation.Local -> {
                if (
                    current.path.isBlank() ||
                    current.rootBoundary?.let { boundary ->
                        runCatching {
                            File(current.path).canonicalPath == File(boundary).canonicalPath
                        }.getOrDefault(current.path == boundary)
                    } == true
                ) {
                    BrowserLocation.Home
                } else {
                    val parent = File(current.path).parentFile
                    if (parent == null) current.copy(path = "")
                    else current.copy(path = parent.absolutePath)
                }
            }
            is BrowserLocation.Shared -> {
                if (current.path.isEmpty()) BrowserLocation.Home
                else current.copy(path = current.path.dropLast(1))
            }
        }
    }

    fun toggle(selection: BrowserSelection) {
        selected = selected.toMutableMap().apply {
            if (containsKey(selection.key)) remove(selection.key) else put(selection.key, selection)
        }
    }

    fun finishSelection(single: BrowserSelection? = null) {
        val items = if (single != null) listOf(single) else selected.values.toList()
        if (items.isEmpty()) return
        val uris = items.map { item ->
            when (item) {
                is BrowserSelection.LocalFile -> Uri.fromFile(File(item.entry.path))
                is BrowserSelection.SharedFile -> item.entry.uri
            }
        }
        onFilesSelected(uris)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                BrowserHeader(
                    title = when (val current = location) {
                        BrowserLocation.Home -> stringResource(R.string.chat_file_browser_browse_folders)
                        BrowserLocation.RecentFiles -> stringResource(R.string.chat_file_browser_title)
                        is BrowserLocation.Local -> {
                            if (current.path.isBlank()) {
                                stringResource(R.string.chat_file_browser_phone_storage)
                            } else {
                                File(current.path).name
                            }
                        }
                        is BrowserLocation.Shared -> current.path.lastOrNull()?.name
                            ?: sharedStorageManager.queryRoot(Uri.parse(current.treeUri))?.name
                            ?: stringResource(R.string.chat_file_browser_phone_folder)
                    },
                    isHome = location == BrowserLocation.Home,
                    showClose = location == BrowserLocation.RecentFiles,
                    multiSelect = multiSelect,
                    selectedCount = selected.size,
                    onBack = ::goBack,
                    onClose = onDismiss,
                    onRefresh = { refreshToken++ },
                    onToggleMultiSelect = {
                        if (multiSelect) selected = emptyMap()
                        multiSelect = !multiSelect
                    },
                    onConfirm = { finishSelection() },
                )

                // 本地目录的面包屑路径
                val currentLocal = location as? BrowserLocation.Local
                if (currentLocal != null && currentLocal.path.isNotBlank()) {
                    BreadcrumbRow(
                        path = currentLocal.path,
                        onNavigate = { target ->
                            location = currentLocal.copy(path = target)
                            refreshToken++
                        },
                    )
                }

                HorizontalDivider()

                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            errorMessage.orEmpty(),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (val current = location) {
                        BrowserLocation.Home -> BrowserHome(
                            commonPlaces = commonPlaces,
                            searchResults = searchResults,
                            homeQuery = homeQuery,
                            onQueryChange = { homeQuery = it },
                            selected = selected,
                            multiSelect = multiSelect,
                            onOpenCommonPlace = { place ->
                                requestPermissionLocation(
                                    BrowserLocation.Local(
                                        path = place.directory.absolutePath,
                                        advanced = false,
                                        rootBoundary = place.directory.absolutePath,
                                    )
                                )
                            },
                            onOpenPhoneStorage = {
                                requestPermissionLocation(BrowserLocation.Local("", advanced = false))
                            },
                            onOpenAdvanced = { showAdvancedDialog = true },
                            onToggleFile = { toggle(BrowserSelection.LocalFile(it)) },
                            onPickFileDirect = { entry -> finishSelection(BrowserSelection.LocalFile(entry)) },
                        )

                        BrowserLocation.RecentFiles -> RecentFilesList(
                            entries = recentEntries,
                            query = recentQuery,
                            loading = loading,
                            onQueryChange = { recentQuery = it },
                            selected = selected,
                            multiSelect = multiSelect,
                            onToggleFile = { toggle(BrowserSelection.LocalFile(it)) },
                            onPickFileDirect = { entry -> finishSelection(BrowserSelection.LocalFile(entry)) },
                            onBrowseFolders = {
                                requestPermissionLocation(BrowserLocation.Home)
                            },
                            onUseSystemPicker = { systemFilePicker.launch(arrayOf("*/*")) },
                        )

                        is BrowserLocation.Local -> LocalBrowserList(
                            roots = if (current.path.isBlank()) localRoots else emptyList(),
                            entries = localEntries,
                            selected = selected,
                            multiSelect = multiSelect,
                            onOpenRoot = {
                                location = current.copy(path = it.absolutePath)
                            },
                            onOpenFolder = {
                                location = current.copy(path = it.path)
                            },
                            onToggleFile = { toggle(BrowserSelection.LocalFile(it)) },
                            onPickFileDirect = { entry -> finishSelection(BrowserSelection.LocalFile(entry)) },
                        )

                        is BrowserLocation.Shared -> SharedBrowserList(
                            entries = sharedEntries,
                            selected = selected,
                            multiSelect = multiSelect,
                            onOpenFolder = { location = current.copy(path = current.path + it) },
                            onToggleFile = { toggle(BrowserSelection.SharedFile(it)) },
                            onPickFileDirect = { entry -> finishSelection(BrowserSelection.SharedFile(entry)) },
                        )
                    }
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }

                // 多选模式底部汇总条
                if (multiSelect && selected.isNotEmpty()) {
                    SelectionSummaryBar(
                        selectedCount = selected.size,
                        totalBytes = selected.values.sumOf { item ->
                            when (item) {
                                is BrowserSelection.LocalFile -> item.entry.sizeBytes ?: 0L
                                is BrowserSelection.SharedFile -> item.entry.sizeBytes ?: 0L
                            }
                        },
                        onClear = { selected = emptyMap() },
                        onConfirm = { finishSelection() },
                    )
                }
            }
        }
    }

    if (showAllFilesPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showAllFilesPermissionDialog = false
                pendingPermissionLocation = null
            },
            title = { Text(stringResource(R.string.chat_file_browser_all_files_permission_title)) },
            text = { Text(stringResource(R.string.chat_file_browser_all_files_permission_desc_safe)) },
            confirmButton = {
                Button(onClick = {
                    showAllFilesPermissionDialog = false
                    allFilesSettingsLauncher.launch(localStorageManager.allFilesAccessIntent())
                }) {
                    Text(stringResource(R.string.chat_file_browser_all_files_permission_open))
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        showAllFilesPermissionDialog = false
                        pendingPermissionLocation = null
                        systemFilePicker.launch(arrayOf("*/*"))
                    }) {
                        Text(stringResource(R.string.chat_file_browser_use_system_picker))
                    }
                    TextButton(onClick = {
                        showAllFilesPermissionDialog = false
                        pendingPermissionLocation = null
                    }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
        )
    }

    if (showAdvancedDialog) {
        AlertDialog(
            onDismissRequest = { showAdvancedDialog = false },
            title = { Text(stringResource(R.string.chat_file_browser_advanced_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.chat_file_browser_advanced_warning))
                    Button(
                        onClick = {
                            showAdvancedDialog = false
                            requestPermissionLocation(BrowserLocation.Local("", advanced = true))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.chat_file_browser_browse_system_files))
                    }
                    OutlinedButton(
                        onClick = {
                            showAdvancedDialog = false
                            folderPicker.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.chat_file_browser_add_other_location))
                    }
                    if (otherAuthorizedTrees.isNotEmpty()) {
                        Text(stringResource(R.string.chat_file_browser_other_locations))
                        otherAuthorizedTrees.forEach { tree ->
                            TextButton(onClick = {
                                showAdvancedDialog = false
                                location = BrowserLocation.Shared(tree)
                            }) {
                                Text(
                                    sharedStorageManager.queryRoot(Uri.parse(tree))?.name
                                        ?: stringResource(R.string.chat_file_browser_phone_folder)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAdvancedDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun BrowserHeader(
    title: String,
    isHome: Boolean,
    showClose: Boolean,
    multiSelect: Boolean,
    selectedCount: Int,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onToggleMultiSelect: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showClose) {
            IconButton(onClick = onClose) {
                Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.chat_file_browser_close))
            }
        } else {
            IconButton(onClick = onBack) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.chat_file_browser_back))
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!isHome) {
            IconButton(onClick = onRefresh) {
                Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.file_manager_refresh))
            }
        }
        TextButton(onClick = onToggleMultiSelect) {
            Text(
                if (multiSelect) stringResource(R.string.chat_file_browser_multi_select_on)
                else stringResource(R.string.chat_file_browser_multi_select),
                color = if (multiSelect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (multiSelect) {
            Button(onClick = onConfirm, enabled = selectedCount > 0) {
                Text(stringResource(R.string.chat_file_browser_add_count, selectedCount))
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(
    path: String,
    onNavigate: (String) -> Unit,
) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, seg ->
            Text(
                "/",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    onNavigate("/" + segments.take(index + 1).joinToString("/"))
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    seg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == segments.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BrowserHome(
    commonPlaces: List<LocalStoragePlace>,
    searchResults: List<LocalFileEntry>,
    homeQuery: String,
    onQueryChange: (String) -> Unit,
    selected: Map<String, BrowserSelection>,
    multiSelect: Boolean,
    onOpenCommonPlace: (LocalStoragePlace) -> Unit,
    onOpenPhoneStorage: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onToggleFile: (LocalFileEntry) -> Unit,
    onPickFileDirect: (LocalFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = homeQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_file_browser_search_hint)) },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                singleLine = true,
            )
        }

        if (homeQuery.isNotBlank()) {
            if (searchResults.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chat_file_browser_no_results),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(searchResults, key = { "search:${it.path}" }) { entry ->
                    val isDirectory = entry.isDirectory
                    val parentName = File(entry.path).parentFile?.name ?: ""
                    FileBrowserRow(
                        key = "search:${entry.path}",
                        name = entry.name,
                        isDirectory = isDirectory,
                        kind = entry.kind,
                        detail = if (isDirectory) null else {
                            listOfNotNull(
                                parentName,
                                entry.sizeBytes?.fileSizeToString(),
                                formatModifiedTime(
                                    entry.lastModified,
                                    stringResource(R.string.chat_file_browser_yesterday),
                                ),
                            ).joinToString(" · ")
                        },
                        selected = multiSelect && selected.containsKey("local:${entry.path}"),
                        showCheckbox = multiSelect && !isDirectory,
                        onClick = {
                            if (isDirectory) {
                                onOpenCommonPlace(LocalStoragePlace(LocalStoragePlaceKind.DOCUMENTS, File(entry.path)))
                            } else if (multiSelect) {
                                onToggleFile(entry)
                            } else {
                                onPickFileDirect(entry)
                            }
                        },
                    )
                }
            }
        } else {
            item {
                Text(
                    stringResource(R.string.chat_file_browser_friendly_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item { BrowserSectionTitle(stringResource(R.string.chat_file_browser_common_files_section)) }
            if (commonPlaces.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chat_file_browser_common_files_empty),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(commonPlaces, key = { it.directory.absolutePath }) { place ->
                    BrowserSourceCard(
                        icon = HugeIcons.Folder01,
                        title = localStoragePlaceLabel(place.kind),
                        subtitle = stringResource(R.string.chat_file_browser_common_place_desc),
                        onClick = { onOpenCommonPlace(place) },
                    )
                }
            }

            item {
                BrowserSourceCard(
                    icon = HugeIcons.Folder01,
                    title = stringResource(R.string.chat_file_browser_phone_storage),
                    subtitle = stringResource(R.string.chat_file_browser_phone_storage_desc),
                    onClick = onOpenPhoneStorage,
                )
            }

            item {
                OutlinedButton(
                    onClick = onOpenAdvanced,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.chat_file_browser_advanced_entry))
                }
            }
        }
    }
}

@Composable
private fun localStoragePlaceLabel(kind: LocalStoragePlaceKind): String = when (kind) {
    LocalStoragePlaceKind.DOWNLOADS -> stringResource(R.string.chat_file_browser_place_downloads)
    LocalStoragePlaceKind.PICTURES -> stringResource(R.string.chat_file_browser_place_pictures)
    LocalStoragePlaceKind.CAMERA -> stringResource(R.string.chat_file_browser_place_camera)
    LocalStoragePlaceKind.DOCUMENTS -> stringResource(R.string.chat_file_browser_place_documents)
    LocalStoragePlaceKind.VIDEOS -> stringResource(R.string.chat_file_browser_place_videos)
    LocalStoragePlaceKind.MUSIC -> stringResource(R.string.chat_file_browser_place_music)
}

@Composable
private fun BrowserSourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BrowserSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RecentFilesList(
    entries: List<LocalFileEntry>,
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    selected: Map<String, BrowserSelection>,
    multiSelect: Boolean,
    onToggleFile: (LocalFileEntry) -> Unit,
    onPickFileDirect: (LocalFileEntry) -> Unit,
    onBrowseFolders: () -> Unit,
    onUseSystemPicker: () -> Unit,
) {
    // 已加载的结果在搜索防抖期间先本地过滤，避免输入时列表整块闪空。
    val visibleEntries = remember(entries, query) {
        val q = query.trim()
        if (q.isBlank()) entries else entries.filter { it.name.contains(q, ignoreCase = true) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.chat_file_browser_search_hint)) },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                singleLine = true,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onBrowseFolders,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.chat_file_browser_browse_folders))
                }
                OutlinedButton(
                    onClick = onUseSystemPicker,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.chat_file_browser_use_system_picker))
                }
            }
        }
        if (visibleEntries.isEmpty()) {
            // 正在加载时不给“没有文件”的结论，否则会和加载动画同时出现，看起来像功能坏了。
            if (!loading) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (query.isBlank()) {
                                stringResource(R.string.chat_file_browser_recent_files_empty)
                            } else {
                                stringResource(R.string.chat_file_browser_no_results)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(visibleEntries, key = { "recent:${it.path}" }) { entry ->
                val parentName = File(entry.path).parentFile?.name.orEmpty()
                FileBrowserRow(
                    key = "recent:${entry.path}",
                    name = entry.name,
                    isDirectory = false,
                    kind = entry.kind,
                    detail = listOfNotNull(
                        parentName.takeIf { it.isNotBlank() },
                        entry.sizeBytes?.fileSizeToString(),
                        formatModifiedTime(
                            entry.lastModified,
                            stringResource(R.string.chat_file_browser_yesterday),
                        ),
                    ).joinToString(" · "),
                    selected = multiSelect && selected.containsKey("local:${entry.path}"),
                    showCheckbox = multiSelect,
                    onClick = {
                        if (multiSelect) onToggleFile(entry) else onPickFileDirect(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalBrowserList(
    roots: List<File>,
    entries: List<LocalFileEntry>,
    selected: Map<String, BrowserSelection>,
    multiSelect: Boolean,
    onOpenRoot: (File) -> Unit,
    onOpenFolder: (LocalFileEntry) -> Unit,
    onToggleFile: (LocalFileEntry) -> Unit,
    onPickFileDirect: (LocalFileEntry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    if (roots.isNotEmpty() && entries.isEmpty()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                LocalSearchField(query = query, onQueryChange = { query = it })
            }
            items(roots, key = { it.absolutePath }) { root ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRoot(root) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        HugeIcons.Folder01,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        root.absolutePath,
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
        return
    }

    if (filtered.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LocalSearchField(query = query, onQueryChange = { query = it })
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(HugeIcons.Folder01, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (query.isNotBlank()) stringResource(R.string.chat_file_browser_no_results)
                        else stringResource(R.string.chat_file_browser_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            LocalSearchField(query = query, onQueryChange = { query = it })
        }
        items(filtered, key = { "local:${it.path}" }) { entry ->
            FileBrowserRow(
                key = "local:${entry.path}",
                name = entry.name,
                isDirectory = entry.isDirectory,
                kind = entry.kind,
                detail = if (entry.isDirectory) null else {
                    listOfNotNull(
                        entry.sizeBytes?.fileSizeToString(),
                        formatModifiedTime(
                            entry.lastModified,
                            stringResource(R.string.chat_file_browser_yesterday),
                        ),
                    ).joinToString(" · ")
                },
                selected = multiSelect && selected.containsKey("local:${entry.path}"),
                showCheckbox = multiSelect && !entry.isDirectory,
                onClick = {
                    if (entry.isDirectory) onOpenFolder(entry)
                    else if (multiSelect) onToggleFile(entry)
                    else onPickFileDirect(entry)
                },
            )
        }
    }
}

@Composable
private fun LocalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.chat_file_browser_search_hint)) },
        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
        singleLine = true,
    )
}

@Composable
private fun SharedBrowserList(
    entries: List<SharedStorageEntry>,
    selected: Map<String, BrowserSelection>,
    multiSelect: Boolean,
    onOpenFolder: (SharedStorageEntry) -> Unit,
    onToggleFile: (SharedStorageEntry) -> Unit,
    onPickFileDirect: (SharedStorageEntry) -> Unit,
) {
    val yesterdayLabel = stringResource(R.string.chat_file_browser_yesterday)
    BrowserEntryList(
        entries = entries.map {
            FileBrowserRowData(
                key = "shared:${it.uri}",
                name = it.name,
                isDirectory = it.isDirectory,
                kind = if (it.isDirectory) FileKind.OTHER else fileKindOfMime(it.mimeType),
                detail = if (it.isDirectory) null else {
                    listOfNotNull(
                        it.sizeBytes?.fileSizeToString(),
                        it.lastModified?.let { m -> formatModifiedTime(m, yesterdayLabel) },
                    ).joinToString(" · ")
                },
                selected = multiSelect && selected.containsKey("shared:${it.uri}"),
                showCheckbox = multiSelect && !it.isDirectory,
                onClick = {
                    if (it.isDirectory) onOpenFolder(it)
                    else if (multiSelect) onToggleFile(it)
                    else onPickFileDirect(it)
                },
            )
        },
    )
}

private data class FileBrowserRowData(
    val key: String,
    val name: String,
    val isDirectory: Boolean,
    val kind: FileKind,
    val detail: String?,
    val selected: Boolean,
    val showCheckbox: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun BrowserEntryList(
    entries: List<FileBrowserRowData>,
) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(HugeIcons.Folder01, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.chat_file_browser_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.key }) { row ->
            FileBrowserRow(
                key = row.key,
                name = row.name,
                isDirectory = row.isDirectory,
                kind = row.kind,
                detail = row.detail,
                selected = row.selected,
                showCheckbox = row.showCheckbox,
                onClick = row.onClick,
            )
        }
    }
}

@Composable
private fun FileBrowserRow(
    key: String,
    name: String,
    isDirectory: Boolean,
    kind: FileKind,
    detail: String?,
    selected: Boolean,
    showCheckbox: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val container = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = container,
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isDirectory) HugeIcons.Folder01 else fileKindIcon(kind),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isDirectory) MaterialTheme.colorScheme.primary else fileKindColor(kind),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    // v257：文件名最多两行；扩展名做成独立小标签，始终可见。
                    // v259：文件名去掉尾部扩展名（避免和标签重复），单选时不占勾选框，
                    // 把宽度还给文件名（用户反馈：选项太多、看不全文件名和格式）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayFileName(name, isDirectory),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        fileExtensionLabel(name, isDirectory)?.let { ext ->
                            Text(
                                ext,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showCheckbox) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                    )
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

/**
 * v257：从文件名里剥出扩展名标签（小写，不带点）。
 * 目录 / 没有扩展名 / 扩展名超长（≥10 字符，通常是名字本身带点）→ 返回 null 不显示标签。
 */
private fun fileExtensionLabel(name: String, isDirectory: Boolean): String? {
    if (isDirectory) return null
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return null
    val ext = name.substring(dot + 1)
    return ext.takeIf { it.length in 1..9 && it.all { c -> c.isLetterOrDigit() } }?.lowercase()
}

/** v259：有扩展名标签时，文件名不再重复带后缀，把宽度留给真正的名字。 */
private fun displayFileName(name: String, isDirectory: Boolean): String {
    val ext = fileExtensionLabel(name, isDirectory) ?: return name
    return name.dropLast(ext.length + 1).ifBlank { name }
}

@Composable
private fun SelectionSummaryBar(
    selectedCount: Int,
    totalBytes: Long,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.chat_file_browser_selected_count, selectedCount, totalBytes.fileSizeToString()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.chat_file_browser_clear))
            }
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.chat_file_browser_add_count, selectedCount))
            }
        }
    }
}

private fun fileKindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> HugeIcons.Image02
    FileKind.VIDEO -> HugeIcons.Video01
    FileKind.AUDIO -> HugeIcons.MusicNote03
    FileKind.PDF -> HugeIcons.File02
    FileKind.SHEET -> HugeIcons.Files02
    FileKind.DOCUMENT -> HugeIcons.Text
    FileKind.ARCHIVE -> HugeIcons.Package01
    FileKind.CODE -> HugeIcons.ComputerTerminal01
    FileKind.OTHER -> HugeIcons.File02
}

private fun fileKindColor(kind: FileKind): Color = when (kind) {
    FileKind.IMAGE -> Color(0xFF43A047)
    FileKind.VIDEO -> Color(0xFF7E57C2)
    FileKind.AUDIO -> Color(0xFFEC407A)
    FileKind.PDF -> Color(0xFFE53935)
    FileKind.SHEET -> Color(0xFF2E7D32)
    FileKind.DOCUMENT -> Color(0xFF1E88E5)
    FileKind.ARCHIVE -> Color(0xFFF57C00)
    FileKind.CODE -> Color(0xFF546E7A)
    FileKind.OTHER -> Color(0xFF757575)
}

private fun formatModifiedTime(millis: Long, yesterdayLabel: String): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameDay = calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = calendar.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        calendar.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        isYesterday -> yesterdayLabel
        calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}
