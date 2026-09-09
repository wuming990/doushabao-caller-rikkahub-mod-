package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.datastore.AGENT_FALLBACK_MODEL_LIMIT
import me.rerere.rikkahub.data.datastore.AutoCompressModelOverride
import me.rerere.rikkahub.data.datastore.MAX_ROUND_TABLE_MEMBERS
import me.rerere.rikkahub.data.datastore.RoundTableSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutoCompressModelSource
import me.rerere.rikkahub.data.model.automaticTargetModelIds
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.CompressionModelSource
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** 特殊模型触发值的快捷档位，单位 K。与助手级自定义触发值用同一套档位。 */
private val SPECIAL_MODEL_THRESHOLD_OPTIONS = listOf(32, 64, 128, 272, 372, 500, 1_000)

/** 250000 -> 250K，1000000 -> 1M。用于特殊模型那一行的小标签。 */
private fun formatTriggerTokens(tokens: Int): String {
    val valueK = (tokens + 999) / 1_000
    return if (valueK >= 1_000) "${valueK / 1_000}M" else "${valueK}K"
}

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, modelSource: CompressionModelSource, customPrompt: String) -> Job,
    // v268：经典压缩（官方旧版流程）入口
    onCompressContextClassic: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    // 圆桌模式（v208）：入口在这里，避免误触输入框旁的快捷键
    onRunRoundTable: () -> Unit = {},
    onRunRoundTableProposalsOnly: () -> Unit = {},
    onSummarizeRoundTable: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val currentChatModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = currentChatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // v255：附件限制说明（对齐主流 AI 应用；选择阶段先讲清规则）
        Text(
            text = "支持图片 / 视频 / 音频 / 文档：图片单个 ≤20MB，其它 ≤50MB，一次最多 20 个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        // v258：恢复原样 —— + 面板保留拍照/照片/视频/音频/文件和文档全部入口
        // （此前误删了这些入口，用户明确要求恢复；要改的是「文件和文档」打开后的文件浏览器显示）
        FlowRow(
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton(onClick = onPickVideo)

                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)

            onStartVoiceMode?.let { start ->
                BigIconTextButton(
                    icon = { Icon(HugeIcons.Voice, contentDescription = null) },
                    text = { Text(stringResource(R.string.chat_page_voice_title)) },
                    onClick = start,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        if (workspaces.isNotEmpty()) {
            WorkspacePickerListItem(
                assistant = assistant,
                conversation = conversation,
                workspaces = workspaces,
                onUpdateAssistant = onUpdateAssistant,
                onUpdateConversation = onUpdateConversation,
                onNavigateToDetail = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(id))
                },
                onNavigateToTerminal = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceTerminal(id))
                },
                onNavigateToManage = {
                    onDismiss()
                    navController.navigate(Screen.Workspaces)
                },
            )
        }

        if (settings.mcpServers.isNotEmpty()) {
            McpPickerListItem(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
            )
        }

        // Extensions (Quick Messages + Prompt Injections + Skills)
        val selectedModeInjectionIds = if (assistant.allowConversationPromptInjection) {
            conversation.modeInjectionIds
        } else {
            assistant.modeInjectionIds
        }
        val selectedLorebookIds = if (assistant.allowConversationPromptInjection) {
            conversation.lorebookIds
        } else {
            assistant.lorebookIds
        }
        val activeModeInjectionCount = settings.modeInjections.count { injection ->
            val automaticTargetModelIds = injection.automaticTargetModelIds()
            injection.enabled && if (automaticTargetModelIds.isNotEmpty()) {
                currentChatModel != null && currentChatModel.id in automaticTargetModelIds
            } else {
                injection.id in selectedModeInjectionIds
            }
        }
        val modeAndLorebookCount = activeModeInjectionCount + selectedLorebookIds.size
        val activeCount =
            assistant.quickMessageIds.size +
                modeAndLorebookCount +
                assistant.enabledSkills.size
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = stringResource(R.string.assistant_page_tab_extensions),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.assistant_page_tab_extensions))
            },
            trailingContent = {
                if (activeCount > 0) {
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowInjectionSheetChange(true)
                },
        )

        // Compress History Button
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = stringResource(R.string.chat_page_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_compress_context))
            },
            trailingContent = {
                if (conversation.messageNodes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowCompressDialogChange(true)
                },
        )

        // Auto Compress Switch
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = stringResource(R.string.chat_page_auto_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_auto_compress_context))
            },
            supportingContent = {
                Text(stringResource(R.string.chat_page_auto_compress_context_desc))
            },
            trailingContent = {
                Switch(
                    checked = assistant.enableAutoCompress,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(assistant.copy(enableAutoCompress = enabled))
                    },
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onUpdateAssistant(assistant.copy(enableAutoCompress = !assistant.enableAutoCompress))
                },
        )

        // Auto Compress Model
        if (assistant.enableAutoCompress) {
            val fixedCompressModelName = settings.findModelById(settings.compressModelId)?.displayName
                ?: stringResource(R.string.chat_page_compress_no_model)
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.chat_page_auto_compress_model))
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AutoCompressionModelOption(
                            selected = assistant.autoCompressModelSource == AutoCompressModelSource.CURRENT_CHAT,
                            title = stringResource(R.string.chat_page_compress_current_model),
                            modelName = currentChatModel?.displayName
                                ?: stringResource(R.string.chat_page_compress_no_model),
                            onClick = {
                                onUpdateAssistant(assistant.copy(autoCompressModelSource = AutoCompressModelSource.CURRENT_CHAT))
                            },
                        )
                        AutoCompressionModelOption(
                            selected = assistant.autoCompressModelSource == AutoCompressModelSource.FIXED,
                            title = stringResource(R.string.chat_page_compress_fixed_model),
                            modelName = fixedCompressModelName,
                            onClick = {
                                onUpdateAssistant(assistant.copy(autoCompressModelSource = AutoCompressModelSource.FIXED))
                            },
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.clip(MaterialTheme.shapes.large),
            )
        }

        // Auto Compress Threshold: directly enter the actual trigger value in K.
        if (assistant.enableAutoCompress) {
            val windowOptions = remember { listOf(32, 64, 128, 272, 372, 500, 1_000) }
            var customThresholdK by remember(assistant.id) {
                mutableStateOf(((assistant.autoCompressTriggerTokens + 999) / 1_000).toString())
            }
            val currentK = (assistant.autoCompressTriggerTokens + 999) / 1_000
            fun formatThreshold(valueK: Int): String = if (valueK >= 1_000) {
                "${valueK / 1_000}M"
            } else {
                "${valueK}K"
            }
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.chat_page_auto_compress_window))
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.chat_page_auto_compress_window_desc,
                                formatThreshold(currentK),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = customThresholdK,
                            onValueChange = { value ->
                                customThresholdK = value.filter { it.isDigit() }
                                customThresholdK.toIntOrNull()
                                    ?.takeIf { it > 0 }
                                    ?.let { thresholdK ->
                                        onUpdateAssistant(
                                            assistant.copy(
                                                autoCompressTriggerTokens = (thresholdK.toLong() * 1_000)
                                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                                    .toInt(),
                                            )
                                        )
                                    }
                            },
                            label = { Text(stringResource(R.string.chat_page_auto_compress_custom_threshold)) },
                            supportingText = {
                                Text(stringResource(R.string.chat_page_auto_compress_custom_threshold_desc))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            windowOptions.forEach { option ->
                                val selected = option == currentK
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.clickable {
                                        customThresholdK = option.toString()
                                        onUpdateAssistant(
                                            assistant.copy(autoCompressTriggerTokens = option * 1_000)
                                        )
                                    },
                                ) {
                                    Text(
                                        text = formatThreshold(option),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.clip(MaterialTheme.shapes.large),
            )
        }

        // 特殊模型：只有被选中的模型走自己的触发值，其余模型继续用上面的助手自定义触发值。
        // 存在全局设置里，所以一个模型只需要设一次，换助手也通用。
        //
        // 界面上默认收起，只显示一行摘要，避免模型多了以后把整个「+」面板占满；
        // 展开后一个模型一行，数值做成可点的小标签，点开才弹出输入框和快捷档位。
        if (assistant.enableAutoCompress) {
            val overrides = settings.autoCompressModelOverrides
            val overridePickerState = rememberModelListState(
                modelId = null,
                providers = settings.providers,
                type = ModelType.CHAT,
            )
            var overridesExpanded by remember { mutableStateOf(false) }
            var editingOverrideId by remember { mutableStateOf<Uuid?>(null) }

            fun writeOverrides(next: List<AutoCompressModelOverride>) {
                onUpdateSettings(settings.copy(autoCompressModelOverrides = next))
            }

            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.chat_page_auto_compress_special_models))
                },
                supportingContent = {
                    Text(
                        text = if (overrides.isEmpty()) {
                            stringResource(R.string.chat_page_auto_compress_special_models_empty)
                        } else {
                            stringResource(
                                R.string.chat_page_auto_compress_special_models_summary,
                                overrides.size,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = if (overridesExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = stringResource(
                            if (overridesExpanded) {
                                R.string.chat_page_auto_compress_special_models_collapse
                            } else {
                                R.string.chat_page_auto_compress_special_models_expand
                            }
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { overridesExpanded = !overridesExpanded },
            )

            if (overridesExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.chat_page_auto_compress_special_models_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    overrides.forEach { override ->
                        key(override.modelId) {
                            val modelName = settings.findModelById(override.modelId)?.displayName
                                ?: stringResource(R.string.chat_page_auto_compress_special_model_missing)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .padding(start = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = modelName,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            editingOverrideId = override.modelId
                                        },
                                    ) {
                                        Text(
                                            text = formatTriggerTokens(override.triggerTokens),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (editingOverrideId == override.modelId) {
                                                editingOverrideId = null
                                            }
                                            writeOverrides(
                                                overrides.filterNot { it.modelId == override.modelId }
                                            )
                                        }
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.chat_page_auto_compress_special_model_remove
                                            ),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { overridePickerState.open() }) {
                        Text(stringResource(R.string.chat_page_auto_compress_special_model_add))
                    }
                }
            }

            // 点数值标签后弹出的小编辑框：数字输入 + 快捷档位，改动即时保存。
            val editingOverride = overrides.firstOrNull { it.modelId == editingOverrideId }
            if (editingOverride != null) {
                val editingName = settings.findModelById(editingOverride.modelId)?.displayName
                    ?: stringResource(R.string.chat_page_auto_compress_special_model_missing)
                var editingValueK by remember(editingOverride.modelId) {
                    mutableStateOf(((editingOverride.triggerTokens + 999) / 1_000).toString())
                }
                val currentK = (editingOverride.triggerTokens + 999) / 1_000
                AlertDialog(
                    onDismissRequest = { editingOverrideId = null },
                    title = {
                        Text(
                            text = editingName,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = editingValueK,
                                onValueChange = { input ->
                                    editingValueK = input.filter { it.isDigit() }
                                    editingValueK.toIntOrNull()
                                        ?.takeIf { it > 0 }
                                        ?.let { thresholdK ->
                                            val updated = (thresholdK.toLong() * 1_000)
                                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                                .toInt()
                                            writeOverrides(
                                                overrides.map { entry ->
                                                    if (entry.modelId == editingOverride.modelId) {
                                                        entry.copy(triggerTokens = updated)
                                                    } else {
                                                        entry
                                                    }
                                                }
                                            )
                                        }
                                },
                                label = {
                                    Text(stringResource(R.string.chat_page_auto_compress_special_model_trigger))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SPECIAL_MODEL_THRESHOLD_OPTIONS.forEach { option ->
                                    val selected = option == currentK
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        modifier = Modifier.clickable {
                                            editingValueK = option.toString()
                                            writeOverrides(
                                                overrides.map { entry ->
                                                    if (entry.modelId == editingOverride.modelId) {
                                                        entry.copy(triggerTokens = option * 1_000)
                                                    } else {
                                                        entry
                                                    }
                                                }
                                            )
                                        },
                                    ) {
                                        Text(
                                            text = formatTriggerTokens(option * 1_000),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { editingOverrideId = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    },
                )
            }

            ModelListSheet(
                state = overridePickerState,
                selectedModelIds = overrides.map { it.modelId }.toSet(),
                dismissOnSelect = false,
                onSelect = { model ->
                    val existing = settings.autoCompressModelOverrides
                    onUpdateSettings(
                        settings.copy(
                            autoCompressModelOverrides = if (existing.any { it.modelId == model.id }) {
                                existing.filterNot { it.modelId == model.id }
                            } else {
                                existing + AutoCompressModelOverride(
                                    modelId = model.id,
                                    triggerTokens = assistant.autoCompressTriggerTokens.coerceAtLeast(1_000),
                                )
                            }
                        )
                    )
                },
            )
        }

        // 圆桌模式（v208 主导式）：入口从输入框快捷栏移到这里，避免误触。
        // 流程：任务合同 → 成员独立探索 + 主模型独立初案（并发）→ 针对性反驳 → 主模型拍板。
        val roundTable = settings.roundTableSetting
        val roundTableMembers = roundTable.memberModelIds
        val validMemberCount = roundTableMembers.count { settings.findModelById(it) != null }
        val canRunRoundTable = validMemberCount >= 2
        val estimatedCalls = validMemberCount +
            (if (roundTable.enableContractStage) 1 else 0) +
            (if (roundTable.enableMainDraft) 1 else 0) +
            (if (roundTable.enableRebuttalStage) 1 else 0) + 1
        var roundTableExpanded by remember { mutableStateOf(false) }
        val roundTableMemberPicker = rememberModelListState(
            modelId = null,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
        val roundTableMainPicker = rememberModelListState(
            modelId = roundTable.mainModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
        val roundTableContractPicker = rememberModelListState(
            modelId = roundTable.contractModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
        val roundTableRebuttalPicker = rememberModelListState(
            modelId = roundTable.rebuttalModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )

        // v219：子代理默认模型选择器（跟随主助手 / 指定模型）
        val agentModelPicker = rememberModelListState(
            modelId = settings.agentModelId,
            providers = settings.providers,
            type = ModelType.CHAT,
        )

        // v219：角色级模型选择器（与 AgentRole.name.lowercase() 一致）
        // v236：新增 programmer —— 用户的设计是「探测用廉价模型、编程用中档模型」，
        // 编程位必须能单独指定模型，否则这条省钱思路只落实了一半。
        val agentRolePickers = listOf(
            "default",
            "explorer",
            "reviewer",
            "programmer",
        ).associateWith { role ->
            rememberModelListState(
                modelId = settings.agentRoleModelOverrides[role],
                providers = settings.providers,
                type = ModelType.CHAT,
            )
        }

        // v237：子代理备用模型（多选，最多 3 个）+ 接力顺序预览。
        //
        // 为什么必须有这个入口：v236 的候选链在用户什么都不配时会去重塌成 1 个模型，
        // 而 GenerationAgentBackend.run 里换人的代码写在 `if (index > 0)` 里，
        // 链子只有 1 节时这个条件恒假 —— 换人代码一次都进不去，「备用模型保底」等于没做。
        // 用户实测反馈：「我根本没有选择备用模型的地方，只有一个模型你怎么切换？」
        val agentFallbackModelIds = settings.agentFallbackModelIds
        val agentFallbackPicker = rememberModelListState(
            modelId = null,
            providers = settings.providers,
            type = ModelType.CHAT,
        )
        // 顺序必须与 GenerationAgentBackend.resolveModelChain 保持一致。
        // 按「通用角色」计算：线程点名的模型是运行时才有的，界面无法预知，故不计入。
        // 过滤掉已删除的模型，因为运行时 findModelById 也会把它们丢掉 —— 预览要和真实行为一致，
        // 否则用户会以为有 3 层保底，实际只有 1 层。
        val agentChainModelIds = buildList {
            settings.agentRoleModelOverrides["default"]?.let { add(it) }
            settings.agentModelId?.let { add(it) }
            addAll(agentFallbackModelIds)
            add(settings.chatModelId)
        }.distinct().filter { settings.findModelById(it) != null }
        val agentChainHasFallback = agentChainModelIds.size >= 2

        fun updateRoundTable(update: (RoundTableSetting) -> RoundTableSetting) {
            onUpdateSettings(
                settings.copy(roundTableSetting = update(settings.roundTableSetting))
            )
        }

        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Puzzle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            headlineContent = { Text(stringResource(R.string.round_table_setting_title)) },
            supportingContent = {
                Text(
                    text = if (roundTableMembers.isEmpty()) {
                        stringResource(R.string.round_table_setting_empty)
                    } else {
                        stringResource(R.string.round_table_setting_summary, roundTableMembers.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (roundTableExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = stringResource(
                        if (roundTableExpanded) {
                            R.string.round_table_collapse
                        } else {
                            R.string.round_table_expand
                        }
                    ),
                    modifier = Modifier.size(20.dp),
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable { roundTableExpanded = !roundTableExpanded },
        )

        if (roundTableExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.round_table_setting_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 操作区：三个明确入口，点了才会花钱 ----
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (canRunRoundTable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canRunRoundTable) {
                            onDismiss()
                            onRunRoundTable()
                        },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = stringResource(R.string.round_table_action_run),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (canRunRoundTable) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                        )
                        Text(
                            text = if (canRunRoundTable) {
                                stringResource(R.string.round_table_action_run_desc, estimatedCalls)
                            } else {
                                stringResource(R.string.round_table_need_members_hint)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (canRunRoundTable) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                ListItem(
                    headlineContent = { Text(stringResource(R.string.round_table_action_proposals_only)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.round_table_action_proposals_only_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .clickable(enabled = canRunRoundTable) {
                            onDismiss()
                            onRunRoundTableProposalsOnly()
                        },
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.round_table_action_summarize)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.round_table_action_summarize_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .clickable {
                            onDismiss()
                            onSummarizeRoundTable()
                        },
                )

                HorizontalDivider(modifier = Modifier.fillMaxWidth())

                // ---- 成员模型 ----
                Text(
                    text = stringResource(R.string.round_table_members, MAX_ROUND_TABLE_MEMBERS),
                    style = MaterialTheme.typography.labelMedium,
                )
                roundTableMembers.forEach { modelId ->
                    key(modelId) {
                        val memberName = settings.findModelById(modelId)?.displayName
                            ?: stringResource(R.string.round_table_member_missing)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = memberName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        updateRoundTable { current ->
                                            current.copy(
                                                memberModelIds = current.memberModelIds
                                                    .filterNot { it == modelId }
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Delete02,
                                        contentDescription = stringResource(
                                            R.string.round_table_remove_member
                                        ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { roundTableMemberPicker.open() }) {
                    Text(stringResource(R.string.round_table_add_member))
                }

                // ---- 主模型 ----
                RoundTableModelRow(
                    title = stringResource(R.string.round_table_main_model),
                    modelName = roundTable.mainModelId
                        ?.let { settings.findModelById(it)?.displayName }
                        ?: stringResource(R.string.round_table_main_model_follow_chat),
                    showClear = roundTable.mainModelId != null,
                    onClear = { updateRoundTable { it.copy(mainModelId = null) } },
                    onClick = { roundTableMainPicker.open() },
                )

                HorizontalDivider(modifier = Modifier.fillMaxWidth())

                // ---- 阶段开关 ----
                RoundTableSwitchRow(
                    title = stringResource(R.string.round_table_contract_stage),
                    desc = stringResource(R.string.round_table_contract_stage_desc),
                    checked = roundTable.enableContractStage,
                    onCheckedChange = { checked ->
                        updateRoundTable { it.copy(enableContractStage = checked) }
                    },
                )
                if (roundTable.enableContractStage) {
                    RoundTableModelRow(
                        title = stringResource(R.string.round_table_contract_model),
                        modelName = roundTable.contractModelId
                            ?.let { settings.findModelById(it)?.displayName }
                            ?: stringResource(R.string.round_table_model_follow_main),
                        showClear = roundTable.contractModelId != null,
                        onClear = { updateRoundTable { it.copy(contractModelId = null) } },
                        onClick = { roundTableContractPicker.open() },
                    )
                }

                RoundTableSwitchRow(
                    title = stringResource(R.string.round_table_main_draft),
                    desc = stringResource(R.string.round_table_main_draft_desc),
                    checked = roundTable.enableMainDraft,
                    onCheckedChange = { checked ->
                        updateRoundTable { it.copy(enableMainDraft = checked) }
                    },
                )

                RoundTableSwitchRow(
                    title = stringResource(R.string.round_table_rebuttal_stage),
                    desc = stringResource(R.string.round_table_rebuttal_stage_desc),
                    checked = roundTable.enableRebuttalStage,
                    onCheckedChange = { checked ->
                        updateRoundTable { it.copy(enableRebuttalStage = checked) }
                    },
                )
                if (roundTable.enableRebuttalStage) {
                    RoundTableModelRow(
                        title = stringResource(R.string.round_table_rebuttal_model),
                        modelName = roundTable.rebuttalModelId
                            ?.let { settings.findModelById(it)?.displayName }
                            ?: stringResource(R.string.round_table_model_follow_main),
                        showClear = roundTable.rebuttalModelId != null,
                        onClear = { updateRoundTable { it.copy(rebuttalModelId = null) } },
                        onClick = { roundTableRebuttalPicker.open() },
                    )
                }

                RoundTableSwitchRow(
                    title = stringResource(R.string.round_table_allow_readonly_tools),
                    desc = stringResource(R.string.round_table_allow_readonly_tools_desc),
                    checked = roundTable.allowReadOnlyTools,
                    onCheckedChange = { checked ->
                        updateRoundTable { it.copy(allowReadOnlyTools = checked) }
                    },
                )

                var roundTableSummaryPromptDraft by remember {
                    mutableStateOf(roundTable.summaryPrompt)
                }
                OutlinedTextField(
                    value = roundTableSummaryPromptDraft,
                    onValueChange = { input ->
                        roundTableSummaryPromptDraft = input
                        updateRoundTable { it.copy(summaryPrompt = input) }
                    },
                    label = { Text(stringResource(R.string.round_table_summary_prompt)) },
                    supportingText = {
                        Text(stringResource(R.string.round_table_summary_prompt_hint))
                    },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // v224：子代理的全部设置收进这张折叠卡（默认收起），并排在圆桌之后。
        // 用户原话：「把子代理这些东西都放到一起，不要散落在外面影响我启用圆桌模式」。
        // 之前这些开关平铺在圆桌卡片上方，每次要用圆桌都得先划过一堆子代理选项。
        var agentExpanded by remember { mutableStateOf(false) }
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Codesandbox,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            headlineContent = { Text(stringResource(R.string.agent_tools_section_title)) },
            supportingContent = {
                Text(
                    text = if (settings.enableAgentTools) {
                        stringResource(R.string.agent_tools_section_summary_on)
                    } else {
                        stringResource(R.string.agent_tools_section_summary_off)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (agentExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = stringResource(
                        if (agentExpanded) {
                            R.string.round_table_collapse
                        } else {
                            R.string.round_table_expand
                        }
                    ),
                    modifier = Modifier.size(20.dp),
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable { agentExpanded = !agentExpanded },
        )

        if (agentExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.agent_tools_section_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // v219：子代理总开关（与圆桌完全独立；关闭后主模型拿不到控制工具）
                RoundTableSwitchRow(
                    title = stringResource(R.string.agent_tools_switch_title),
                    desc = stringResource(R.string.agent_tools_switch_desc),
                    checked = settings.enableAgentTools,
                    onCheckedChange = { checked ->
                        onUpdateSettings(settings.copy(enableAgentTools = checked))
                    },
                )

                // v284：助手级分闸。上面那个是「这台机器要不要子代理」，这个是「当前这个助手要不要」。
                //
                // 用户原话：「我需要这个助手开子代理，另外一个助手关闭都做不到」。
                // 两个都开才生效；默认开，所以升级后行为与旧版一致。
                val agentAssistant = settings.getCurrentAssistant()
                RoundTableSwitchRow(
                    title = stringResource(R.string.agent_tools_assistant_switch_title),
                    desc = stringResource(
                        R.string.agent_tools_assistant_switch_desc,
                        agentAssistant.name.trim().ifEmpty { "—" },
                    ),
                    checked = agentAssistant.enableAgentTools,
                    onCheckedChange = { checked ->
                        onUpdateSettings(
                            settings.copy(
                                assistants = settings.assistants.map { assistant ->
                                    if (assistant.id == agentAssistant.id) {
                                        assistant.copy(enableAgentTools = checked)
                                    } else {
                                        assistant
                                    }
                                },
                            )
                        )
                    },
                )

                // v280：允许子代理检索历史对话（默认关）。说明文案里必须写清内容会外发，
                // 这是隐私相关的开关，不能让用户在不知情的情况下打开。
                RoundTableSwitchRow(
                    title = stringResource(R.string.agent_tools_memory_title),
                    desc = stringResource(R.string.agent_tools_memory_desc),
                    checked = settings.allowAgentConversationSearch,
                    onCheckedChange = { checked ->
                        onUpdateSettings(settings.copy(allowAgentConversationSearch = checked))
                    },
                )

                // v219：子代理默认模型（跟随主助手 / 指定模型；主模型显式指定时仍可覆盖）
                RoundTableModelRow(
                    title = stringResource(R.string.agent_tools_model_title),
                    modelName = settings.agentModelId
                        ?.let { settings.findModelById(it)?.displayName }
                        ?: stringResource(R.string.agent_tools_model_follow_main),
                    showClear = settings.agentModelId != null,
                    onClear = { onUpdateSettings(settings.copy(agentModelId = null)) },
                    onClick = { agentModelPicker.open() },
                )

                // v219：子代理最大并发数（1~8，运行时生效）
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.agent_tools_concurrent_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.agent_tools_concurrent_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentMaxConcurrent = (settings.agentMaxConcurrent - 1).coerceAtLeast(1))
                                    )
                                },
                                enabled = settings.agentMaxConcurrent > 1,
                            ) {
                                Text("−")
                            }
                            Text(
                                text = settings.agentMaxConcurrent.toString(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentMaxConcurrent = (settings.agentMaxConcurrent + 1).coerceAtMost(8))
                                    )
                                },
                                enabled = settings.agentMaxConcurrent < 8,
                            ) {
                                Text("+")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )
                    // v238：单个子代理超时（0 = 不限时；步长 5 分钟）
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.agent_tools_timeout_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.agent_tools_timeout_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentThreadTimeoutMinutes = (settings.agentThreadTimeoutMinutes - 5).coerceAtLeast(0))
                                    )
                                },
                                enabled = settings.agentThreadTimeoutMinutes > 0,
                            ) {
                                Text("−")
                            }
                            Text(
                                text = if (settings.agentThreadTimeoutMinutes == 0) {
                                    stringResource(R.string.agent_tools_timeout_unlimited)
                                } else {
                                    stringResource(R.string.agent_tools_timeout_minutes, settings.agentThreadTimeoutMinutes)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentThreadTimeoutMinutes = (settings.agentThreadTimeoutMinutes + 5).coerceAtMost(120))
                                    )
                                },
                                enabled = settings.agentThreadTimeoutMinutes < 120,
                            ) {
                                Text("+")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )

                // v246：工具最长执行时间（5~60 分钟，步长 5）
                // 用户原话：「我现在设置五分钟，为什么超过五分钟了没有停下」——
                // 当时它正卡在一次工具调用里，走的是这条闸，而界面上一个字都没提。
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.agent_tools_tool_timeout_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.agent_tools_tool_timeout_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentToolTimeoutMinutes = (settings.agentToolTimeoutMinutes - 5).coerceAtLeast(5))
                                    )
                                },
                                enabled = settings.agentToolTimeoutMinutes > 5,
                            ) {
                                Text("−")
                            }
                            Text(
                                text = stringResource(
                                    R.string.agent_tools_tool_timeout_minutes,
                                    settings.agentToolTimeoutMinutes
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentToolTimeoutMinutes = (settings.agentToolTimeoutMinutes + 5).coerceAtMost(60))
                                    )
                                },
                                enabled = settings.agentToolTimeoutMinutes < 60,
                            ) {
                                Text("+")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )

                // v248：子代理工具返回上限（KB，步长 32，范围 32~256）
                //
                // 用户原话：「子代理用的都是廉价模型，不用太担心子代理的消耗，
                // 担心的是主模型的消耗和上文纯净度才是重点」「不要对主模型作出限制」。
                //
                // ⚠️ 但用户随后划了红线：「你子代理可以随便动，不好用大不了以后不用，
                // 但是主模型绝对不能乱动，不能做出任何限制」。
                // 所以这个设置**只作用于子代理**：主对话与圆桌那条路一个字都没改，
                // 仍走上游写死的 32KB（主对话有终端，被截断的内容能自己 grep 回来）。
                //
                // 仍然留上界的理由：正常读代码撞不到它，它兜的是终端命令那种
                // 不可预测的大输出（一次构建日志能有几十万字符），无上限时一次误操作
                // 就会把主对话上下文冲垮。
                //
                // 为什么给档位而不是无上限：一次塞太多会撑爆模型的上下文窗口，
                // 窗口不够时是**直接请求失败**，不是变慢，而失败信息往往看不懂。
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.agent_tools_read_limit_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.agent_tools_read_limit_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(toolOutputLimitKb = (settings.toolOutputLimitKb - 32).coerceAtLeast(32))
                                    )
                                },
                                enabled = settings.toolOutputLimitKb > 32,
                            ) {
                                Text("−")
                            }
                            Text(
                                text = stringResource(
                                    R.string.agent_tools_read_limit_kb,
                                    settings.toolOutputLimitKb
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(toolOutputLimitKb = (settings.toolOutputLimitKb + 32).coerceAtMost(256))
                                    )
                                },
                                enabled = settings.toolOutputLimitKb < 256,
                            ) {
                                Text("+")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )

                // v241：自动续跑次数（0 = 完全不自动续跑；步长 1）
                // 用户原话：「直接给我个选项，让我可以自主选择续跑次数。」
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.agent_tools_resume_max_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.agent_tools_resume_max_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentAutoResumeMax = (settings.agentAutoResumeMax - 1).coerceAtLeast(0))
                                    )
                                },
                                enabled = settings.agentAutoResumeMax > 0,
                            ) {
                                Text("−")
                            }
                            Text(
                                text = if (settings.agentAutoResumeMax == 0) {
                                    stringResource(R.string.agent_tools_resume_max_off)
                                } else {
                                    stringResource(R.string.agent_tools_resume_max_times, settings.agentAutoResumeMax)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentAutoResumeMax = (settings.agentAutoResumeMax + 1).coerceAtMost(10))
                                    )
                                },
                                enabled = settings.agentAutoResumeMax < 10,
                            ) {
                                Text("+")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )

                // v241：子代理单趟步数上限（16~96，步长 8）
                // 用户原话：「为什么要限制这个步数？不会导致关键时候差一点搜索完结果截断么」
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.agent_tools_max_steps_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.agent_tools_max_steps_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentMaxSteps = (settings.agentMaxSteps - 8).coerceAtLeast(16))
                                    )
                                },
                                enabled = settings.agentMaxSteps > 16,
                            ) {
                                Text("−")
                            }
                            Text(
                                text = stringResource(R.string.agent_tools_max_steps_value, settings.agentMaxSteps),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(agentMaxSteps = (settings.agentMaxSteps + 8).coerceAtMost(96))
                                    )
                                },
                                enabled = settings.agentMaxSteps < 96,
                            ) {
                                Text("+")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )

                // v219：角色级模型（默认跟随子代理模型；点开可单独指定）
                // v236：programmer 一并列出（探测用便宜的、编程用中档的，这是用户的省钱设计）
                listOf(
                    "default" to R.string.agent_tools_role_default,
                    "explorer" to R.string.agent_tools_role_explorer,
                    "reviewer" to R.string.agent_tools_role_reviewer,
                    "programmer" to R.string.agent_tools_role_programmer,
                ).forEach { (role, titleRes) ->
                    RoundTableModelRow(
                        title = stringResource(titleRes),
                        modelName = settings.agentRoleModelOverrides[role]
                            ?.let { settings.findModelById(it)?.displayName }
                            ?: stringResource(R.string.agent_tools_role_follow_default),
                        showClear = settings.agentRoleModelOverrides[role] != null,
                        onClear = {
                            onUpdateSettings(
                                settings.copy(agentRoleModelOverrides = settings.agentRoleModelOverrides - role)
                            )
                        },
                        onClick = { agentRolePickers[role]?.open() },
                    )
                }

                // v237：备用模型（最多 3 个，按顺序兜底）
                Text(
                    text = stringResource(
                        R.string.agent_tools_fallback_title,
                        AGENT_FALLBACK_MODEL_LIMIT,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(R.string.agent_tools_fallback_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (agentFallbackModelIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_tools_fallback_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                agentFallbackModelIds.forEach { fallbackId ->
                    key(fallbackId) {
                        val fallbackName = settings.findModelById(fallbackId)?.displayName
                            ?: stringResource(R.string.agent_tools_fallback_missing)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = fallbackName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        onUpdateSettings(
                                            settings.copy(
                                                agentFallbackModelIds = settings
                                                    .agentFallbackModelIds
                                                    .filterNot { it == fallbackId }
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Delete02,
                                        contentDescription = stringResource(
                                            R.string.agent_tools_fallback_remove
                                        ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { agentFallbackPicker.open() },
                    enabled = agentFallbackModelIds.size < AGENT_FALLBACK_MODEL_LIMIT,
                ) {
                    Text(stringResource(R.string.agent_tools_fallback_add))
                }

                // v237：接力顺序预览。
                // 只有 1 节时整块变成 errorContainer 配色 + 明确警告 ——
                // 用户此前正是因为界面什么都不说，才以为「备用模型保底」已经生效。
                Text(
                    text = stringResource(R.string.agent_tools_chain_title),
                    style = MaterialTheme.typography.labelMedium,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (agentChainHasFallback) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val chainTextColor = if (agentChainHasFallback) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        agentChainModelIds.forEachIndexed { index, chainId ->
                            Text(
                                text = "${index + 1}. " +
                                    (settings.findModelById(chainId)?.displayName ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                                color = chainTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = if (agentChainHasFallback) {
                                stringResource(
                                    R.string.agent_tools_chain_ok,
                                    agentChainModelIds.size,
                                )
                            } else {
                                stringResource(R.string.agent_tools_chain_warn)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = chainTextColor,
                        )
                        Text(
                            text = stringResource(R.string.agent_tools_chain_role_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = chainTextColor,
                        )
                    }
                }
            }
        }

        // v237：备用模型多选面板。再点一次已选中的模型就是取消选择；
        // 满 3 个之后继续点新模型不生效（上限由 AGENT_FALLBACK_MODEL_LIMIT 统一把关，
        // encode 落盘时也会 take(3)，两侧都不会越界）。
        ModelListSheet(
            state = agentFallbackPicker,
            selectedModelIds = agentFallbackModelIds.toSet(),
            dismissOnSelect = false,
            onSelect = { model ->
                val current = settings.agentFallbackModelIds
                val next = when {
                    current.contains(model.id) -> current.filterNot { it == model.id }
                    current.size < AGENT_FALLBACK_MODEL_LIMIT -> current + model.id
                    else -> current
                }
                onUpdateSettings(settings.copy(agentFallbackModelIds = next))
            },
        )

        ModelListSheet(
            state = roundTableMemberPicker,
            selectedModelIds = roundTableMembers.toSet(),
            dismissOnSelect = false,
            onSelect = { model ->
                updateRoundTable { current ->
                    val next = when {
                        current.memberModelIds.contains(model.id) ->
                            current.memberModelIds.filterNot { it == model.id }

                        current.memberModelIds.size < MAX_ROUND_TABLE_MEMBERS ->
                            current.memberModelIds + model.id

                        else -> current.memberModelIds
                    }
                    current.copy(memberModelIds = next)
                }
            },
        )

        ModelListSheet(
            state = roundTableMainPicker,
            onSelect = { model -> updateRoundTable { it.copy(mainModelId = model.id) } },
        )

        ModelListSheet(
            state = roundTableContractPicker,
            onSelect = { model -> updateRoundTable { it.copy(contractModelId = model.id) } },
        )

        ModelListSheet(
            state = roundTableRebuttalPicker,
            onSelect = { model -> updateRoundTable { it.copy(rebuttalModelId = model.id) } },
        )

        ModelListSheet(
            state = agentModelPicker,
            onSelect = { model -> onUpdateSettings(settings.copy(agentModelId = model.id)) },
        )

        agentRolePickers.forEach { (role, picker) ->
            ModelListSheet(
                state = picker,
                onSelect = { model ->
                    onUpdateSettings(
                        settings.copy(agentRoleModelOverrides = settings.agentRoleModelOverrides + (role to model.id))
                    )
                },
            )
        }


        // Workspace CWD
        val boundWorkspace = remember(workspaces, assistant.workspaceId) {
            workspaces.find { it.id == assistant.workspaceId?.toString() }
        }
        if (boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
            var showCwdSheet by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showCwdSheet = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Folder01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = conversation.workspaceCwd ?: "/workspace",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showCwdSheet) {
                WorkspaceCwdPickerSheet(
                    workspaceId = boundWorkspace.id,
                    currentCwd = conversation.workspaceCwd,
                    onSelectCwd = { newCwd ->
                        onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                    },
                    onDismiss = { showCwdSheet = false },
                )
            }
        }
    }

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            currentModelId = currentChatModel?.id,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { onShowInjectionSheetChange(false) },
            onDismissAll = onDismiss,
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(
            currentModelName = settings.findModelById(
                assistant.chatModelId ?: settings.chatModelId
            )?.displayName ?: stringResource(R.string.chat_page_compress_no_model),
            fixedModelName = settings.findModelById(settings.compressModelId)
                ?.displayName ?: stringResource(R.string.chat_page_compress_no_model),
            initialCustomPrompt = if (settings.compressPrompt == DEFAULT_COMPRESS_PROMPT) {
                ""
            } else {
                settings.compressPrompt
            },
            onDismiss = {
                onShowCompressDialogChange(false)
                onDismiss()
            },
            onConfirm = { additionalPrompt, modelSource, customPrompt ->
                onCompressContext(additionalPrompt, modelSource, customPrompt)
            },
            // v268：双模式——初始模式记住上次选择；经典模式走官方旧版流程
            initialClassicMode = settings.compressUseClassicMode,
            onConfirmClassic = { additionalPrompt, targetTokens, keepRecentMessages ->
                onCompressContextClassic(additionalPrompt, targetTokens, keepRecentMessages)
            },
        )
    }
}

@Composable
private fun RoundTableSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.clip(MaterialTheme.shapes.large),
    )
}

@Composable
private fun RoundTableModelRow(
    title: String,
    modelName: String,
    showClear: Boolean,
    onClear: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = modelName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (showClear) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = HugeIcons.Delete02,
                        contentDescription = stringResource(R.string.round_table_remove_member),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { onClick() },
    )
}

@Composable
private fun AutoCompressionModelOption(
    selected: Boolean,
    title: String,
    modelName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    conversation: Conversation,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = stringResource(R.string.assistant_page_workspace),
            )
        },
        headlineContent = {
            Text(stringResource(R.string.assistant_page_workspace))
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { showSheet = true },
    )

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                    if (conversation.workspaceCwd != null) {
                        onUpdateConversation(conversation.copy(workspaceCwd = null))
                    }
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    currentModelId: Uuid?,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
                currentModelId = currentModelId,
                modifier = Modifier.weight(1f),
                onNavigateToQuickMessages = {
                    onDismissAll()
                    navController.navigate(Screen.QuickMessages)
                },
                onNavigateToPrompts = {
                    onDismissAll()
                    navController.navigate(Screen.Prompts)
                },
                onNavigateToSkills = {
                    onDismissAll()
                    navController.navigate(Screen.Skills)
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Camera01, null)
    }, text = {
        Text(stringResource(R.string.take_picture))
    }) {
        onLaunchCamera()
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
