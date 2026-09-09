package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.gemini.GeminiAccount
import me.rerere.rikkahub.data.gemini.GeminiAccountRepository
import me.rerere.rikkahub.data.gemini.GeminiOAuthManager
import me.rerere.rikkahub.data.gemini.GeminiOAuthStatus
import me.rerere.rikkahub.data.gemini.GeminiTokenStatus
import me.rerere.rikkahub.data.gemini.GeminiQuotaBucket
import me.rerere.rikkahub.data.gemini.GeminiQuotaGroup
import me.rerere.rikkahub.data.gemini.GeminiUsageWindow
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun GeminiProviderConfigure(
    provider: ProviderSetting.GeminiOAuth,
    onEdit: (ProviderSetting.GeminiOAuth) -> Unit,
) {
    val repository = koinInject<GeminiAccountRepository>()
    val oauthManager = koinInject<GeminiOAuthManager>()
    val providerManager = koinInject<ProviderManager>()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val oauthStatus by oauthManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val canEnable = accounts.any { it.enabled && it.tokenStatus != GeminiTokenStatus.INVALID }

    LaunchedEffect(oauthStatus) {
        when (val status = oauthStatus) {
            is GeminiOAuthStatus.Success -> {
                runCatching {
                    providerManager.getProviderByType(provider).listModels(provider)
                }.onSuccess { models ->
                    onEdit(provider.copy(models = mergeCodexModels(provider.models, models)))
                }
                toaster.show(
                    context.getString(R.string.gemini_oauth_success),
                    type = ToastType.Success,
                )
                oauthManager.consumeResult()
            }

            is GeminiOAuthStatus.Error -> {
                toaster.show(status.message, type = ToastType.Error)
                oauthManager.consumeResult()
            }

            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = provider.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.gemini_provider_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The ban risk is the single most important thing on this page, so it sits above the
        // sign-in button rather than below the account list where it could be scrolled past.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.gemini_ban_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.gemini_ban_warning_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Button(
            onClick = oauthManager::startLogin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.gemini_sign_in))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.gemini_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (canEnable) {
                        stringResource(R.string.gemini_round_robin_description)
                    } else {
                        stringResource(R.string.gemini_sign_in_required)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                enabled = provider.enabled || canEnable,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gemini_accounts_count, accounts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = { scope.launch { repository.refreshAll() } },
                enabled = accounts.isNotEmpty(),
            ) {
                Text(stringResource(R.string.gemini_check_status))
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.gemini_no_accounts),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            accounts.forEach { account ->
                GeminiAccountCard(
                    account = account,
                    onEnabledChange = { enabled ->
                        scope.launch { repository.setEnabled(account.id, enabled) }
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching { repository.refreshAccount(account.id) }
                                .onFailure {
                                    toaster.show(
                                        it.message ?: context.getString(
                                            R.string.gemini_refresh_failed
                                        ),
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    },
                    onReauthenticate = oauthManager::startLogin,
                    onDelete = {
                        scope.launch {
                            repository.delete(account.id)
                            if (repository.accounts.value.isEmpty() && provider.enabled) {
                                onEdit(provider.copy(enabled = false))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GeminiAccountCard(
    account: GeminiAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauthenticate: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.gemini_remove_account_title)) },
            text = { Text(stringResource(R.string.gemini_remove_account_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text(
                        stringResource(R.string.gemini_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    if (account.email.isNotBlank()) {
                        Text(
                            account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = account.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Text(
                text = stringResource(R.string.gemini_project_id, account.projectId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = when (account.tokenStatus) {
                    GeminiTokenStatus.AVAILABLE -> stringResource(R.string.gemini_token_available)
                    GeminiTokenStatus.EXPIRED -> stringResource(R.string.gemini_token_expired)
                    GeminiTokenStatus.INVALID -> stringResource(R.string.gemini_token_unavailable)
                    GeminiTokenStatus.UNKNOWN -> stringResource(R.string.gemini_token_not_checked)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (account.tokenStatus) {
                    GeminiTokenStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                    GeminiTokenStatus.INVALID, GeminiTokenStatus.EXPIRED ->
                        MaterialTheme.colorScheme.error

                    GeminiTokenStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // v280：优先按官方配额摘要的分组显示 —— Gemini 一组、Claude/GPT 一组，每组各有
            // 5 小时桶与每周桶。这两组是**互相独立的额度**，所以 Gemini 用完时另一组通常还能用，
            // 分开显示才能让用户看出这件事。拿不到分组时才退回旧的两行估算读数（见
            // GeminiUsageSnapshot 的文档：那条路的数字会偏乐观）。
            val usage = account.usage
            if (usage != null && usage.groups.isNotEmpty()) {
                usage.groups.forEach { group ->
                    Text(
                        text = geminiQuotaGroupLabel(group),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    group.buckets.forEach { bucket ->
                        GeminiUsageRow(
                            label = geminiQuotaBucketLabel(bucket),
                            window = GeminiUsageWindow(bucket.remainingFraction, bucket.resetsAt),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.gemini_quota_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                usage?.daily?.let {
                    GeminiUsageRow(label = stringResource(R.string.gemini_daily_limit), window = it)
                }
                usage?.weekly?.let {
                    GeminiUsageRow(label = stringResource(R.string.gemini_weekly_limit), window = it)
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.gemini_refresh))
                }
                TextButton(onClick = onReauthenticate) {
                    Text(stringResource(R.string.gemini_reauthenticate))
                }
                TextButton(onClick = { showDeleteConfirmation = true }) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * v280：组名本地化。
 *
 * 优先按 bucketId 前缀判断（`gemini-*` / `3p-*`），这是官方稳定的标识；判断不出来才退回服务端
 * 给的英文 displayName，都没有就显示「其他模型」—— 谷歌加了新的模型分组时不至于显示成空行。
 */
@Composable
private fun geminiQuotaGroupLabel(group: GeminiQuotaGroup): String = when {
    group.buckets.any { it.bucketId.startsWith("gemini") } ->
        stringResource(R.string.gemini_quota_group_gemini)

    group.buckets.any { it.bucketId.startsWith("3p") } ->
        stringResource(R.string.gemini_quota_group_third_party)

    else -> group.name.ifBlank { stringResource(R.string.gemini_quota_group_other) }
}

/** v280：窗口名本地化。归一化不出来的窗口原样显示服务端给的标题，不猜。 */
@Composable
private fun geminiQuotaBucketLabel(bucket: GeminiQuotaBucket): String = when (bucket.window) {
    "5h" -> stringResource(R.string.gemini_quota_window_5h)
    "weekly" -> stringResource(R.string.gemini_quota_window_weekly)
    else -> bucket.label.ifBlank { bucket.window }
}

@Composable
private fun GeminiUsageRow(label: String, window: GeminiUsageWindow) {
    val remainingPercent = (window.remainingFraction * 100).coerceIn(0.0, 100.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.gemini_percent_remaining, remainingPercent.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { window.remainingFraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        window.resetsAt?.let { epochSeconds ->
            Text(
                text = stringResource(
                    R.string.gemini_resets_at,
                    GEMINI_RESET_FORMAT.format(Instant.ofEpochSecond(epochSeconds)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val GEMINI_RESET_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
