package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.CompressionModelSource
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

/**
 * 手动压缩对话框（v268 起双模式）。
 *
 * 智能压缩（默认）：照抄 Codex 的 compaction —— 摘要长度由模型自行决定，
 * 保留的历史由 Codex 的 20k token 用户消息预算决定。
 *
 * 经典压缩（官方旧版，用户要求加回）：自选目标 token 数（500/1000/2000/4000）
 * 与保留最近消息条数；超长对话分块并发压缩，摘要作为 user 消息保存。
 * 上次选择的模式会被记住。
 */
@Composable
fun CompressContextDialog(
    currentModelName: String,
    fixedModelName: String,
    initialCustomPrompt: String,
    initialClassicMode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, modelSource: CompressionModelSource, customPrompt: String) -> Job,
    onConfirmClassic: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var customPrompt by remember { mutableStateOf(initialCustomPrompt) }
    var modelSource by remember { mutableStateOf(CompressionModelSource.FIXED) }
    // v268：双模式状态 + 经典模式的两个参数
    var classicMode by remember { mutableStateOf(initialClassicMode) }
    var selectedTokens by remember { mutableIntStateOf(2000) }
    var keepRecentMessages by remember { mutableIntStateOf(32) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // v268：模式切换（智能 / 经典）
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = !classicMode,
                            onClick = { classicMode = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text(stringResource(R.string.chat_page_compress_mode_smart))
                        }
                        SegmentedButton(
                            selected = classicMode,
                            onClick = { classicMode = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text(stringResource(R.string.chat_page_compress_mode_classic))
                        }
                    }

                    if (classicMode) {
                        // 经典压缩（官方旧版）：目标 token 数 + 保留最近消息条数
                        Text(
                            text = stringResource(R.string.chat_page_compress_target_tokens),
                            style = MaterialTheme.typography.labelMedium
                        )
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tokenOptions = listOf(500, 1000, 2000, 4000)
                            tokenOptions.forEachIndexed { index, tokens ->
                                SegmentedButton(
                                    selected = selectedTokens == tokens,
                                    onClick = { selectedTokens = tokens },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = tokenOptions.size
                                    )
                                ) {
                                    Text("$tokens")
                                }
                            }
                        }
                        OutlinedNumberInput(
                            value = keepRecentMessages,
                            onValueChange = { keepRecentMessages = it },
                            label = stringResource(R.string.chat_page_compress_keep_recent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                    Text(
                        text = stringResource(R.string.chat_page_compress_codex_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = stringResource(R.string.chat_page_compress_model_source),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    CompressionModelOption(
                        selected = modelSource == CompressionModelSource.CURRENT_CHAT,
                        title = stringResource(R.string.chat_page_compress_current_model),
                        modelName = currentModelName,
                        onClick = { modelSource = CompressionModelSource.CURRENT_CHAT },
                    )
                    CompressionModelOption(
                        selected = modelSource == CompressionModelSource.FIXED,
                        title = stringResource(R.string.chat_page_compress_fixed_model),
                        modelName = fixedModelName,
                        onClick = { modelSource = CompressionModelSource.FIXED },
                    )
                    } // 结束智能模式分支

                    // Additional context input（两种模式共用）
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // Persistent custom compression prompt: when filled, it fully replaces the
                    // built-in default prompt; leave empty to keep the built-in default.
                    // （智能压缩专属选项；经典压缩使用内置官方旧版提示词）
                    if (!classicMode) {
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { customPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_custom_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_custom_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                    }

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    currentJob = if (classicMode) {
                        onConfirmClassic(additionalPrompt, selectedTokens, keepRecentMessages)
                    } else {
                        onConfirm(additionalPrompt, modelSource, customPrompt)
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun CompressionModelOption(
    selected: Boolean,
    title: String,
    modelName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                modelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
