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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.CompressionModelSource
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

/**
 * 手动压缩对话框（v268 起双模式）。
 *
 * 智能压缩（默认）：照抄 Codex 的 compaction —— 摘要长度由模型自行决定，
 * 保留的历史由 Codex 的 20k token 用户消息预算决定。
 *
 * 经典压缩（v268 历史描述）：自选目标 token 数（500/1000/2000/4000）
 * 与保留最近消息条数；超长对话分块并发压缩，摘要作为 user 消息保存。
 * v1019：经典模式已对齐官方 3.1.1；以上经典流程/token 档位仅为历史说明，现保留最近消息并插入可恢复检查点摘要。
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
    onConfirmClassic: (additionalPrompt: String, keepRecentMessages: Int) -> Job,
    runningJob: Job? = null,
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var customPrompt by remember { mutableStateOf(initialCustomPrompt) }
    var modelSource by remember { mutableStateOf(CompressionModelSource.FIXED) }
    // v268：双模式状态 + 经典模式的两个参数
    var classicMode by remember { mutableStateOf(initialClassicMode) }
    var keepRecentMessages by remember { mutableIntStateOf(0) }
    var currentJob by remember { mutableStateOf<Job?>(runningJob) }
    // v1024：重新打开时接回原对话正在压缩的任务，不重复发请求。
    LaunchedEffect(runningJob) {
        if (runningJob != null) currentJob = runningJob
    }
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
        // v1024：返回/点外部只隐藏，不取消应用级压缩。
        onDismissRequest = onDismiss,
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
                        ContainedLoadingIndicator(
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
                        // v1019：经典压缩按官方当前方式保留最近消息
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_page_compress_keep_recent),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                FilledTonalIconButton(
                                    onClick = { keepRecentMessages-- },
                                    enabled = keepRecentMessages > 0,
                                ) {
                                    Icon(HugeIcons.Remove01, contentDescription = "Decrease")
                                }
                                Text(
                                    text = keepRecentMessages.toString(),
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(min = 40.dp),
                                )
                                FilledTonalIconButton(
                                    onClick = { keepRecentMessages++ },
                                    enabled = keepRecentMessages < Int.MAX_VALUE,
                                ) {
                                    Icon(HugeIcons.Add01, contentDescription = "Increase")
                                }
                            }
                            Text(
                                text = stringResource(R.string.chat_page_compress_keep_recent_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        text = stringResource(
                            if (classicMode) R.string.chat_page_compress_checkpoint_hint
                            else R.string.chat_page_compress_warning
                        ),
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
                        onConfirmClassic(additionalPrompt, keepRecentMessages)
                    } else {
                        onConfirm(additionalPrompt, modelSource, customPrompt)
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (isLoading) R.string.chat_page_compress_background else R.string.cancel))
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
