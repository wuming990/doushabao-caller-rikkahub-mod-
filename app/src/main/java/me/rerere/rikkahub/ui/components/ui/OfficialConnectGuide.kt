package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * 面向非技术用户的“连接官方 RikkaHub”引导。
 *
 * Android 的 SAF 必须由用户确认一次位置，不同厂商的系统文件界面起始位置也不一致，
 * 因此这里用纯步骤文案兜底，而不是声称完全自动。
 */
@Composable
fun OfficialConnectGuideDialog(
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.official_connect_guide_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.official_connect_guide_intro))
                Text(stringResource(R.string.official_connect_guide_step1))
                Text(stringResource(R.string.official_connect_guide_step2))
                Text(stringResource(R.string.official_connect_guide_step3))
                Text(stringResource(R.string.official_connect_guide_step4))
                Text(
                    stringResource(R.string.official_connect_guide_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(onClick = onStart) {
                Text(stringResource(R.string.official_connect_guide_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 选错位置时的兜底引导：直接给出“再选一次”的具体步骤。 */
@Composable
fun OfficialConnectFailedDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_official_connect_failed_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.workspace_official_connect_failed_desc))
                Text(stringResource(R.string.official_connect_guide_step2))
                Text(stringResource(R.string.official_connect_guide_step3))
                Text(stringResource(R.string.official_connect_guide_step4))
            }
        },
        confirmButton = {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.official_connect_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
