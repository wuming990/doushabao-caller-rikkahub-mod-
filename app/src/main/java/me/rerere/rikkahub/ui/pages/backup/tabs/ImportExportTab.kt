package me.rerere.rikkahub.ui.pages.backup.tabs

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.LocalTransferKind
import me.rerere.rikkahub.ui.pages.backup.LocalTransferState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()

    // v302：导入/导出的进行状态与结果都放在 ViewModel 里（工作跑在应用级作用域）。
    // 旧写法用 rememberCoroutineScope，页面被切标签/旋转/重建时 Compose 1.12 会直接取消整个导入，
    // 再被 runCatching 报成"恢复失败: rememberCoroutineScope left the composition"。
    val transfer by vm.localTransfer.collectAsStateWithLifecycle()
    val runningKind = (transfer as? LocalTransferState.Running)?.kind
    val isExporting = runningKind == LocalTransferKind.EXPORT
    val isRestoring = runningKind != null && !isExporting
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    // 导入类型：IMPORT_LOCAL 为本地备份，IMPORT_CHATBOX 为 Chatbox 导入，IMPORT_CHERRY 为 Cherry Studio 导入
    var importKind by remember { mutableStateOf(LocalTransferKind.IMPORT_LOCAL) }

    // 结果统一在这里报告。状态存在 ViewModel 里，页面被回收后再回来也不会漏报。
    LaunchedEffect(transfer) {
        when (val state = transfer) {
            is LocalTransferState.Done -> {
                toaster.show(
                    context.getString(
                        if (state.kind == LocalTransferKind.EXPORT) {
                            R.string.backup_page_backup_success
                        } else {
                            R.string.backup_page_restore_success
                        }
                    ),
                    type = ToastType.Success
                )
                if (state.needsRestart) onShowRestartDialog()
                vm.consumeLocalTransfer()
            }

            is LocalTransferState.Failed -> {
                toaster.show(
                    context.getString(R.string.backup_page_restore_failed, state.message),
                    type = ToastType.Error
                )
                vm.consumeLocalTransfer()
            }

            else -> Unit
        }
    }

    // 创建文件保存的launcher（导出）
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        // v302：导出交给 ViewModel 在应用级作用域里跑，页面被回收不再中断，也不会留下半个坏备份
        uri?.let { vm.exportTo(it) }
    }

    // 创建文件选择的launcher（导入）
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // v302：导入交给 ViewModel 在应用级作用域里跑，页面被回收不再中断，
        // 也不会再把"页面被回收"误报成"恢复失败"。
        uri?.let { vm.importFrom(it, importKind) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                        MultiChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            WebDavConfig.BackupItem.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = WebDavConfig.BackupItem.entries.size
                                    ),
                                    onCheckedChange = { checked ->
                                        val newItems = if (checked) {
                                            selectedBackupItems + item
                                        } else {
                                            selectedBackupItems - item
                                        }
                                        vm.updateLocalBackupItems(newItems)
                                    },
                                    checked = item in selectedBackupItems
                                ) {
                                    Text(
                                        when (item) {
                                            WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                            WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                        }
                                    )
                                }
                            }
                        }
                    },
                )
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            showImportConfirmDialog = true
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importKind = LocalTransferKind.IMPORT_CHATBOX
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importKind == LocalTransferKind.IMPORT_CHATBOX) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importKind = LocalTransferKind.IMPORT_CHERRY
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importKind == LocalTransferKind.IMPORT_CHERRY) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        importKind = LocalTransferKind.IMPORT_LOCAL
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
