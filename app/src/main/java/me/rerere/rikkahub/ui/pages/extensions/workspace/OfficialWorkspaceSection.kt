package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.OfficialTransferInfo
import me.rerere.rikkahub.data.files.OfficialWorkspaceMigrationPhase
import me.rerere.rikkahub.data.files.OfficialWorkspaceMigrationProgress
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * v1015：这个卡片原来长在 WorkspacePage.kt 里。合并官方 3.1.0 时那个文件被官方版本逐字节整覆盖
 * （922 行 → 250 行），整套「复制官方 RikkaHub 工作区」界面就此静默消失：数据层、页面控制层、
 * 52 条文案全在，就是没有任何代码引用。现在把卡片单独成文，页面只留接线，将来再被覆盖时差异更明显。
 * 内容与 v322 快照逐字一致，仅 private → internal（同包跨文件调用）。
 */
@Composable
internal fun OfficialRikkaHubWorkspaceCard(
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
