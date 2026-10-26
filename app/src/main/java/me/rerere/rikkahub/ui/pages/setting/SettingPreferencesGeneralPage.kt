package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.ResumeStrategy
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var ttsPlaybackSpeed by remember(settings.defaultTTSPlaybackSpeed) {
        mutableFloatStateOf(settings.defaultTTSPlaybackSpeed)
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { it.copy(displaySetting = setting) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup {
                    switchItem(
                        checked = createNewConversationOnStart,
                        onCheckedChange = { createNewConversationOnStart = it },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                    )
                    switchItem(
                        checked = displaySetting.sendOnEnter,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(sendOnEnter = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.showMessageJumper,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showMessageJumper = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                    )
                    if (displaySetting.showMessageJumper) {
                        switchItem(
                            checked = displaySetting.messageJumperOnLeft,
                            onCheckedChange = { updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                        )
                    }
                    switchItem(
                        checked = displaySetting.enableAutoScroll,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableAutoScroll = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.enableBlurEffect,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableBlurEffect = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_background_effect_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_title)) },
                    )
                    if (displaySetting.enableBlurEffect) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_background_effect_type)) },
                        ) {
                            ConnectedChoiceButtons(
                                options = BackgroundEffectType.entries,
                                selected = displaySetting.backgroundEffectType,
                                onSelect = { updateDisplaySetting(displaySetting.copy(backgroundEffectType = it)) },
                            ) { type, selected ->
                                ConnectedChoiceLabel(
                                    text = when (type) {
                                        BackgroundEffectType.BLUR -> stringResource(R.string.setting_display_page_background_effect_blur)
                                        BackgroundEffectType.GLASS -> stringResource(R.string.setting_display_page_background_effect_glass)
                                    },
                                    selected = selected,
                                )
                            }
                        }
                    }
                    switchItem(
                        checked = displaySetting.enableMessageGenerationHapticEffect,
                        onCheckedChange = {
                            updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                        },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.skipCropImage,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(skipCropImage = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                    )
                    switchItem(
                        checked = displaySetting.pasteLongTextAsFile,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                            trailingContent = { CardGroupValue("${displaySetting.pasteLongTextThreshold}") },
                        ) {
                            Slider(
                                value = displaySetting.pasteLongTextThreshold.toFloat(),
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                },
                                valueRange = 100f..10000f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.enableVolumeKeyScroll,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                            trailingContent = { CardGroupValue("${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%") },
                        ) {
                            Slider(
                                value = displaySetting.volumeKeyScrollRatio,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                },
                                valueRange = 0.25f..1.0f,
                                steps = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed)) },
                        supportingContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed_description)) },
                        trailingContent = { CardGroupValue("x${"%.1f".format(ttsPlaybackSpeed)}") },
                    ) {
                        Slider(
                            value = ttsPlaybackSpeed,
                            onValueChange = {
                                ttsPlaybackSpeed = (it * 10).roundToInt() / 10f
                            },
                            onValueChangeFinished = {
                                vm.updateSettings {
                                    it.copy(defaultTTSPlaybackSpeed = ttsPlaybackSpeed)
                                }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    switchItem(
                        checked = displaySetting.ttsOnlyReadQuoted,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                    )
                    switchItem(
                        checked = displaySetting.ttsOnlyReadOutsideBrackets,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
                    )
                    switchItem(
                        checked = displaySetting.autoPlayTTSAfterGeneration,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                    )
                    switchItem(
                        checked = displaySetting.voiceModeListenWhileReplying,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(voiceModeListenWhileReplying = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_voice_listen_while_replying_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_voice_listen_while_replying_title)) },
                    )
                }
            }

            // 二改：工作区命令时长上限（0 = 不限时，可自主调整）。3.1 重做设置页时丢的入口。
            item {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_workspace_command_timeout_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_workspace_command_timeout_desc)) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Slider(
                                value = settings.workspaceCommandTimeoutSeconds.toFloat(),
                                onValueChange = { value ->
                                    vm.updateSettings {
                                        it.copy(workspaceCommandTimeoutSeconds = snapTimeoutSeconds(value))
                                    }
                                },
                                valueRange = 0f..7200f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (settings.workspaceCommandTimeoutSeconds <= 0) {
                                    stringResource(R.string.setting_workspace_command_timeout_unlimited)
                                } else {
                                    formatTimeoutSeconds(settings.workspaceCommandTimeoutSeconds)
                                }
                            )
                        }
                    }
                }
            }

            // v263/v323：回复被截断时自动续写（0 = 关闭）。合并 3.1.0 时官方重做了设置页，
            // 这一段被整覆盖掉了，但数据层 truncationAutoResumeMax 一直还在 —— 必须补回 UI，
            // 否则用户找不到续跑次数（守卫测试 TruncationAutoResumeGuardTest 也钉着这个入口）。
            item {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_truncation_auto_resume_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_truncation_auto_resume_desc)) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Slider(
                                value = settings.truncationAutoResumeMax.toFloat(),
                                onValueChange = { value ->
                                    vm.updateSettings {
                                        it.copy(truncationAutoResumeMax = value.toInt().coerceIn(0, 99))
                                    }
                                },
                                valueRange = 0f..99f,
                                steps = 98,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (settings.truncationAutoResumeMax <= 0) {
                                    stringResource(R.string.setting_truncation_auto_resume_off)
                                } else {
                                    settings.truncationAutoResumeMax.toString()
                                }
                            )
                        }
                    }
                }
            }

            // v288/v323：续跑方式（发送「继续」 / 重新生成最后一次输出）。
            // 同样是官方 3.1 重做设置页时被整覆盖掉的入口。
            item {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_resume_strategy_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_resume_strategy_desc)) },
                    ) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ResumeStrategy.entries.forEachIndexed { index, strategy ->
                                SegmentedButton(
                                    selected = settings.resumeStrategy == strategy,
                                    onClick = {
                                        vm.updateSettings { it.copy(resumeStrategy = strategy) }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ResumeStrategy.entries.size,
                                    ),
                                ) {
                                    Text(
                                        text = when (strategy) {
                                            ResumeStrategy.CONTINUE ->
                                                stringResource(R.string.setting_resume_strategy_continue)

                                            ResumeStrategy.REGENERATE ->
                                                stringResource(R.string.setting_resume_strategy_regenerate)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // v268/v323：对话级模型记忆（每个对话记住自己最后用的模型，切回自动恢复）。
            // 3.1 设置页重做时丢的，数据层字段仍在。
            item {
                CardGroup {
                    switchItem(
                        checked = settings.rememberModelPerConversation,
                        onCheckedChange = { checked ->
                            vm.updateSettings { it.copy(rememberModelPerConversation = checked) }
                        },
                        supportingContent = { Text(stringResource(R.string.setting_remember_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_remember_model_title)) },
                    )
                }
            }
            // 二改：是否使用 App 图标样式的加载指示器（官方 3.1 重做设置页时丢的入口）
            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.useAppIconStyleLoadingIndicator,
                        onCheckedChange = {
                            updateDisplaySetting(
                                displaySetting.copy(useAppIconStyleLoadingIndicator = it)
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title))
                        },
                    )
                }
            }
        }
    }
}

/** 二改：工作区超时滑块只落在这些档位上，避免滑出一个没法读的秒数。 */
private fun snapTimeoutSeconds(value: Float): Int {
    val options = intArrayOf(0, 30, 60, 300, 600, 1800, 3600, 7200)
    return options.minByOrNull { kotlin.math.abs(it - value) } ?: 0
}

private fun formatTimeoutSeconds(seconds: Int): String = when {
    seconds >= 3600 -> "${seconds / 3600}h"
    seconds >= 60 -> "${seconds / 60}m"
    else -> "${seconds}s"
}
