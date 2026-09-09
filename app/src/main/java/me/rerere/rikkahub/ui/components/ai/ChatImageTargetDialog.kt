package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

/** 聊天页图片上传目标：只给 AI 看，或者同时保存到某个工作区。 */
sealed interface ChatImageTarget {
    data object AiOnly : ChatImageTarget

    data class Workspace(val workspaceId: String, val workspaceName: String) : ChatImageTarget
}

/**
 * 傻瓜式二选一：默认行为（只给 AI 看）与“保存到工作区并给 AI 看”。
 *
 * 只有一个工作区时直接给出该工作区；多个工作区时才要求用户选择具体工作区。
 */
@Composable
fun ChatImageTargetDialog(
    workspaces: List<WorkspaceEntity>,
    onSelect: (ChatImageTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_image_target_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.chat_image_target_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TargetCard(
                    title = stringResource(R.string.chat_image_target_ai_only),
                    subtitle = stringResource(R.string.chat_image_target_ai_only_desc),
                    onClick = { onSelect(ChatImageTarget.AiOnly) },
                )

                when {
                    workspaces.isEmpty() -> Text(
                        stringResource(R.string.chat_image_target_workspace_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    workspaces.size == 1 -> {
                        val workspace = workspaces.first()
                        TargetCard(
                            title = stringResource(R.string.chat_image_target_workspace),
                            subtitle = workspace.name,
                            onClick = {
                                onSelect(ChatImageTarget.Workspace(workspace.id, workspace.name))
                            },
                        )
                    }

                    else -> {
                        Text(
                            stringResource(R.string.chat_image_target_workspace_pick),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        workspaces.forEach { workspace ->
                            TargetCard(
                                title = workspace.name,
                                subtitle = stringResource(R.string.chat_image_target_workspace_desc),
                                onClick = {
                                    onSelect(ChatImageTarget.Workspace(workspace.id, workspace.name))
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun TargetCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
