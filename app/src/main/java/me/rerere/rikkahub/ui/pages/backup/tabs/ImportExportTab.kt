package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.LocalTransferState
import me.rerere.rikkahub.ui.pages.backup.LocalTransferKind
import me.rerere.rikkahub.ui.pages.backup.components.BackupActionIcon
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
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

    // v1015：这里是 v302 那套修复，合并官方 3.1.0 时本文件被官方原样整覆盖过，
    // 于是又退回 rememberCoroutineScope —— 而 BackupVM 里的后台机制（AppScope + localTransfer）
    // 一行没丢、只是没人调用。Compose 1.12 会在页面离开组合时以
    // ForgottenCoroutineScopeException（“rememberCoroutineScope left the composition”）
    // 取消仍在跑的导入，再被 runCatching 当成「恢复失败」报出来 —— 这就是官方备份导不进来的根因。
    // 现在：进行状态与结果一律从 ViewModel 的 localTransfer 派生，页面只做转发与显示。
    val transfer by vm.localTransfer.collectAsStateWithLifecycle()
    val runningKind = (transfer as? LocalTransferState.Running)?.kind
    val isExporting = runningKind == LocalTransferKind.EXPORT
    val isRestoring = runningKind != null && !isExporting
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    // 导入类型：IMPORT_LOCAL 本地备份 / IMPORT_CHATBOX Chatbox 导入 / IMPORT_CHERRY Cherry Studio 导入
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
        // v302/v1015：导出交给 ViewModel 在应用级作用域里跑，页面被回收不再中断，也不会留下半个坏备份
        uri?.let { vm.exportTo(it) }
    }

    // 创建文件选择的launcher（导入）
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // v302/v1015：导入交给 ViewModel 在应用级作用域里跑，页面被回收不再中断，
        // 也不会再把「页面被回收」误报成「恢复失败」。临时文件拷贝、分发与 finally 删除都在 BackupVM 里。
        uri?.let { vm.importFrom(it, importKind) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(
            title = { Text(stringResource(R.string.backup_page_local_backup_export)) },
        ) {
            backupContentItem(
                options = WebDavConfig.BackupItem.entries,
                selected = selectedBackupItems,
                onChange = { vm.updateLocalBackupItems(it) },
            ) { item ->
                when (item) {
                    WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                    WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                }
            }
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
                leadingContent = { BackupActionIcon(HugeIcons.FileExport, loading = isExporting) },
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
                        if (isRestoring && runningKind == LocalTransferKind.IMPORT_LOCAL) {
                            stringResource(R.string.backup_page_importing)
                        } else {
                            stringResource(R.string.backup_page_import_desc)
                        }
                    )
                },
                leadingContent = {
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && runningKind == LocalTransferKind.IMPORT_LOCAL)
                },
            )
        }

        CardGroup(
            title = { Text(stringResource(R.string.backup_page_import_from_other_app)) },
        ) {
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
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && runningKind == LocalTransferKind.IMPORT_CHATBOX)
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
                    BackupActionIcon(HugeIcons.FileImport, loading = isRestoring && runningKind == LocalTransferKind.IMPORT_CHERRY)
                },
            )
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
