package me.rerere.rikkahub.ui.pages.chat

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import me.rerere.rikkahub.service.RoundTableContentVerdict
import me.rerere.rikkahub.service.RoundTableGapDecision
import me.rerere.rikkahub.service.RoundTableGapStage
import me.rerere.rikkahub.service.RoundTableRunState
import me.rerere.rikkahub.service.RoundTableSeat
import me.rerere.rikkahub.service.RoundTableSeatCommand
import me.rerere.rikkahub.service.RoundTableSeatStatus
import me.rerere.rikkahub.service.RoundTableStallPolicy
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState

/**
 * v213 圆桌控制面板。
 *
 * 每个"座位"一行：显示这个位置现在由谁负责、跑到哪一步了，
 * 并且**从一开始就可以**停止它、换一个模型接手、重试或跳过——不需要等系统判超时。
 *
 * 出方案阶段结束后如果有位置缺席，这里会出现检查点：补齐 / 用现有方案继续 / 结束本轮。
 */
@Composable
internal fun RoundTableSeatPanel(
    state: RoundTableRunState?,
    providers: List<ProviderSetting>,
    onCommand: (String, RoundTableSeatCommand) -> Unit,
    onToggleInclude: (String, Boolean) -> Unit,
    onGapDecision: (RoundTableGapDecision) -> Unit,
    // v224：点「查看过程」直接进这个位置自己的对话（参数是那条对话的 ID）
    onOpenSeatChat: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // v223：圆桌结束后，如果仍有截断/停止/失败/疑似无效的座位，面板继续保留，
    // 让用户可以查看过程或点「继续输出」；没有可处理座位时才整体隐藏。
    if (
        state == null ||
        state.seats.isEmpty() ||
        (state.isFinished && state.seats.none { it.canContinue })
    ) return

    var expanded by remember { mutableStateOf(true) }
    var replacingSeatId by remember { mutableStateOf<String?>(null) }
    val picker = rememberModelListState(
        modelId = null,
        providers = providers,
        type = ModelType.CHAT,
    )
    val gap = state.gapPrompt
    val doneCount = state.seats.count { it.status.isTerminal }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (gap != null) {
                        stringResource(
                            R.string.round_table_panel_title_gap,
                            gap.usableCount,
                            state.seats.size,
                        )
                    } else {
                        stringResource(
                            R.string.round_table_panel_title,
                            doneCount,
                            state.seats.size,
                        )
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = stringResource(
                            if (expanded) {
                                R.string.round_table_panel_collapse
                            } else {
                                R.string.round_table_panel_expand
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (gap != null) {
                HorizontalDivider()
                Text(
                    text = stringResource(
                        // v229：按停下来的原因显示不同标题（缺席 / 需要过目 / 最后一步没成功）
                        when (gap.stage) {
                            RoundTableGapStage.REVIEW -> R.string.round_table_gap_title_review
                            RoundTableGapStage.SUMMARY -> R.string.round_table_gap_title_summary
                            RoundTableGapStage.PROPOSALS -> R.string.round_table_gap_title
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = when (gap.stage) {
                        RoundTableGapStage.REVIEW ->
                            stringResource(R.string.round_table_gap_desc_review)

                        RoundTableGapStage.SUMMARY ->
                            stringResource(R.string.round_table_gap_desc_summary)

                        RoundTableGapStage.PROPOSALS -> stringResource(
                            R.string.round_table_gap_desc,
                            gap.usableCount,
                            gap.missingCount,
                            gap.remainingCalls,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onGapDecision(RoundTableGapDecision.FILL_SEATS) }) {
                        Text(
                            text = stringResource(R.string.round_table_gap_action_fill),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(
                        onClick = { onGapDecision(RoundTableGapDecision.CONTINUE_WITH_CURRENT) },
                        enabled = gap.usableCount > 0,
                    ) {
                        Text(
                            text = stringResource(R.string.round_table_gap_action_continue),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { onGapDecision(RoundTableGapDecision.END_RUN) }) {
                        Text(
                            text = stringResource(R.string.round_table_gap_action_end),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.round_table_gap_action_fill_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.seats.forEach { seat ->
                        RoundTableSeatRow(
                            seat = seat,
                            onCommand = onCommand,
                            onToggleInclude = onToggleInclude,
                            onReplace = {
                                replacingSeatId = seat.seatId
                                picker.open()
                            },
                            onOpenSeat = {
                                seat.chatConversationId
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(onOpenSeatChat)
                            },
                        )
                    }
                }
            }
        }
    }

    ModelListSheet(
        state = picker,
        onSelect = { model ->
            replacingSeatId?.let { seatId ->
                onCommand(
                    seatId,
                    RoundTableSeatCommand.Replace(
                        modelId = model.id,
                        modelName = model.displayName,
                    ),
                )
            }
            replacingSeatId = null
        },
    )
}

@Composable
private fun RoundTableSeatRow(
    seat: RoundTableSeat,
    onCommand: (String, RoundTableSeatCommand) -> Unit,
    onToggleInclude: (String, Boolean) -> Unit,
    onReplace: () -> Unit,
    onOpenSeat: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${roundTableSeatTitle(seat)} · ${seat.modelName}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = roundTableSeatStatusText(seat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (seat.reruns > 0) {
                Text(
                    text = stringResource(R.string.round_table_panel_attempt, seat.reruns),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v229：自动续跑单独显示，跟「重跑」区分开——续跑不丢已产出内容
            if (seat.autoContinuations > 0) {
                Text(
                    text = stringResource(
                        R.string.round_table_panel_auto_continue,
                        seat.autoContinuations,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // 还在跑：可以只停这一个位置
            if (!seat.status.isTerminal) {
                // v239：停止按钮绝不禁用。
                // 旧行为 enabled = !seat.pendingCommand 造成死锁：上游卡住时第一次点停止
                // 会把 pendingCommand 置为 true，按钮随即变灰，用户再也点不了第二次，
                // 而那条指令又因为执行协程被卡死的连接拖住而永远没人消费。
                TextButton(
                    onClick = { onCommand(seat.seatId, RoundTableSeatCommand.Stop) },
                ) {
                    Text(
                        text = stringResource(
                            if (seat.pendingCommand) {
                                R.string.round_table_panel_action_force_stop
                            } else {
                                R.string.round_table_panel_action_stop
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // v224：只有真的建好了过程对话才给入口，避免点了没反应
            if (seat.hasSeatChat) {
                TextButton(onClick = onOpenSeat) {
                    Text(
                        text = stringResource(R.string.round_table_panel_action_view),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // 截断、停止、失败、意外中断和疑似无效都可以接着写
            // v229：卡在「没人负责」状态时也要给入口，否则那个位置只能整轮作废
            if (seat.canContinue || seat.stalledUnscheduled) {
                TextButton(
                    onClick = { onCommand(seat.seatId, RoundTableSeatCommand.Continue) },
                    enabled = !seat.pendingCommand,
                ) {
                    Text(
                        text = stringResource(R.string.round_table_panel_action_continue),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // 换人：任何时候都能换，换完只重跑这一个位置
            TextButton(onClick = onReplace) {
                Text(
                    text = stringResource(R.string.round_table_panel_action_replace),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // 已经有结论但没拿到可用结果：可以用同一个模型再试一次
            // v229：卡在「没人负责」状态时也必须给重试入口 ——
            // 那个死锁的现场就是「排队中」，旧条件不含它，于是一个救援按钮都不显示。
            if (seat.status.isMissingResult || seat.stalledUnscheduled) {
                TextButton(onClick = { onCommand(seat.seatId, RoundTableSeatCommand.Retry) }) {
                    Text(
                        text = stringResource(R.string.round_table_panel_action_retry),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (seat.status != RoundTableSeatStatus.SKIPPED) {
                TextButton(onClick = { onCommand(seat.seatId, RoundTableSeatCommand.Skip) }) {
                    Text(
                        // v239：已经下过指令还点跳过 = 强制跳过，走不等执行协程的通道
                        text = stringResource(
                            if (seat.pendingCommand) {
                                R.string.round_table_panel_action_force_skip
                            } else {
                                R.string.round_table_panel_action_skip
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // v215：疑似无效、已停止（保留了半截内容）都由用户自己决定要不要算进最终结论
            if (seat.status == RoundTableSeatStatus.SUSPECT ||
                seat.status == RoundTableSeatStatus.STOPPED
            ) {
                TextButton(
                    onClick = { onToggleInclude(seat.seatId, !seat.includeInSummary) }
                ) {
                    Text(
                        text = stringResource(
                            if (seat.includeInSummary) {
                                R.string.round_table_panel_action_exclude
                            } else {
                                R.string.round_table_panel_action_include
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun roundTableSeatTitle(seat: RoundTableSeat): String = when (seat.role) {
    RoundTableRole.EXPLORATION ->
        stringResource(R.string.round_table_seat_exploration, seat.ordinal)

    RoundTableRole.CONTRACT -> stringResource(R.string.round_table_role_contract)
    RoundTableRole.MAIN_DRAFT -> stringResource(R.string.round_table_role_main_draft)
    RoundTableRole.REBUTTAL -> stringResource(R.string.round_table_role_rebuttal)
    RoundTableRole.FINAL -> stringResource(R.string.round_table_role_final)
}

@Composable
private fun roundTableSeatStatusText(seat: RoundTableSeat): String {
    // 面板会随座位状态刷新（看门狗每 15 秒也会推一次），因此这里直接取当前时间即可
    val now = SystemClock.elapsedRealtime()
    // v229：指令已下达但还没被处理时，任何状态都要给出反馈。
    // 旧版只在「正在生成」时显示「正在停止…」，于是座位卡在「排队中」时按了停止毫无反应。
    if (seat.pendingCommand) {
        return stringResource(R.string.round_table_panel_state_command_pending)
    }
    // v229：没人负责却又没跑完 —— 这正是「换模型后永久停在排队中」那个死锁的样子，
    // 必须明确告诉用户怎么救，而不是让他对着「排队中」干等。
    if (seat.stalledUnscheduled) {
        return stringResource(R.string.round_table_panel_state_stalled)
    }
    return when (seat.status) {
        RoundTableSeatStatus.PENDING ->
            stringResource(R.string.round_table_panel_state_pending)

        RoundTableSeatStatus.RUNNING ->
            if (seat.pendingCommand) {
                // v215：指令已下达但连接还没断开，明确告诉用户"在停了"，不要让人以为没反应
                stringResource(R.string.round_table_panel_state_stopping)
            } else if (RoundTableStallPolicy.isSoftStalled(seat, now)) {
                stringResource(
                    R.string.round_table_panel_state_running_stalled,
                    (seat.idleMillis(now) / 60_000L).coerceAtLeast(1L).toInt(),
                )
            } else {
                stringResource(R.string.round_table_panel_state_running, seat.chars)
            }

        RoundTableSeatStatus.SUCCEEDED ->
            if (seat.truncated) {
                stringResource(R.string.round_table_panel_state_succeeded_truncated, seat.chars)
            } else {
                stringResource(R.string.round_table_panel_state_succeeded, seat.chars)
            }

        RoundTableSeatStatus.SUSPECT ->
            stringResource(
                R.string.round_table_panel_state_suspect,
                roundTableSuspectReasonText(seat.suspectReason),
            )

        RoundTableSeatStatus.FAILED -> stringResource(R.string.round_table_panel_state_failed)
        RoundTableSeatStatus.STOPPED -> stringResource(R.string.round_table_panel_state_stopped)
        RoundTableSeatStatus.SKIPPED -> stringResource(R.string.round_table_panel_state_skipped)
        RoundTableSeatStatus.INTERRUPTED ->
            stringResource(R.string.round_table_panel_state_interrupted)
    }
}

@Composable
private fun roundTableSuspectReasonText(verdict: RoundTableContentVerdict?): String = when (verdict) {
    RoundTableContentVerdict.ROLE_ECHO ->
        stringResource(R.string.round_table_panel_suspect_role_echo)

    RoundTableContentVerdict.INTENT_ONLY ->
        stringResource(R.string.round_table_panel_suspect_intent_only)

    else -> stringResource(R.string.round_table_panel_suspect_too_short)
}
