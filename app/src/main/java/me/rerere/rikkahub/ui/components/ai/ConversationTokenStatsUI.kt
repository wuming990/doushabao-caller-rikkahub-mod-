package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.dao.ConversationTokenStats

/**
 * 本对话 token 累计的显示件（v297）。
 *
 * 取数口径见 [ConversationTokenStats]：每条消息优先用 cumulativeUsage（本次生成所有请求
 * 相加的真实消耗），老消息没这个字段才退回 usage。界面必须如实标注这个数**不含**
 * 起标题 / 生成建议 / 压缩历史 / 翻译等后台调用，也**不含**子代理的消耗。
 *
 * 不做费用折算（用户口径）：中转站价格只有用户自己知道，内置价目表算出来是错的，
 * 宁可只显示数量。
 */

/**
 * 数字缩写：1234 → 1.2K，1500000 → 1.5M；不足 1K 原样显示（不写成 0.0K 骗人）。
 *
 * 名字必须与同包 ContextUsageBar.kt 里那个私有的 formatTokenCount 区分开 ——
 * Kotlin 里同一个包的两个顶层私有函数同名会直接编译失败（Conflicting overloads），
 * 两者的取整规则也不同（那边固定一位小数，这边整数不带小数点）。
 */
fun formatTokenTotal(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> trimTrailingZero(value / 1_000.0) + "K"
    value < 1_000_000_000 -> trimTrailingZero(value / 1_000_000.0) + "M"
    else -> trimTrailingZero(value / 1_000_000_000.0) + "B"
}

/** 保留一位小数，整数位不带小数点（12.0 → "12"，12.5 → "12.5"） */
private fun trimTrailingZero(value: Double): String {
    val rounded = kotlin.math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * 缓存命中率（v299）：缓存命中的输入 token 占输入总量的百分比。
 *
 * 输入为 0 时返回 "—" —— 不能除零，也不能编一个 0% 出来（那会让人以为缓存完全没生效）。
 * 保留一位小数，整数不带小数点（42.0 → "42%"、42.3 → "42.3%"）。
 * 名字必须与同包其他顶层函数区分开（同包顶层同名会直接编译失败，见 v298 的 formatTokenTotal）。
 */
internal fun formatCacheHitRate(cachedTokens: Long, promptTokens: Long): String {
    if (promptTokens <= 0L) return "—"
    val pct = kotlin.math.round(cachedTokens * 1000.0 / promptTokens) / 10.0
    return if (pct % 1.0 == 0.0) "${pct.toInt()}%" else "$pct%"
}

/** 顶栏那行小字：本对话 ↑输入 ↓输出 Σ合计。没有任何用量数据时整行不渲染。 */
@Composable
fun ConversationTokenStatsLine(
    stats: ConversationTokenStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stats.usageMessages <= 0) return
    val summary = stringResource(
        R.string.chat_conversation_token_summary,
        formatTokenTotal(stats.promptTokens),
        formatTokenTotal(stats.completionTokens),
        formatTokenTotal(stats.promptTokens + stats.completionTokens),
    )
    Text(
        text = summary,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
    )
}

/** 点开后的小窗：分项 + 思考 + 缓存 + 合计 + 口径说明。 */
@Composable
fun ConversationTokenStatsDialog(
    stats: ConversationTokenStats,
    onDismiss: () -> Unit,
    // v301：花费估算（调用方没传 / 没填过价时为 null）
    cost: ConversationCost? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_conversation_token_detail_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatsRow(
                    label = stringResource(R.string.stats_page_input_tokens),
                    value = formatTokenTotal(stats.promptTokens),
                )
                StatsRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenTotal(stats.completionTokens),
                )
                // 服务商没报思考量时整行不出现 —— 显示成 0 会让人以为「思考没花钱」
                if (stats.reasoningTokens > 0) {
                    StatsRow(
                        label = stringResource(R.string.chat_conversation_token_reasoning),
                        value = formatTokenTotal(stats.reasoningTokens),
                    )
                }
                if (stats.cachedTokens > 0) {
                    StatsRow(
                        label = stringResource(R.string.stats_page_cached_tokens),
                        value = formatTokenTotal(stats.cachedTokens),
                    )
                    // v299：命中率 = 缓存输入 / 总输入。输入为 0 时不显示（不编 0% 骗人）
                    if (stats.promptTokens > 0) {
                        StatsRow(
                            label = stringResource(R.string.chat_conversation_token_cache_hit_rate),
                            value = formatCacheHitRate(stats.cachedTokens, stats.promptTokens),
                        )
                    }
                }
                StatsRow(
                    label = stringResource(R.string.chat_conversation_token_total),
                    value = formatTokenTotal(stats.promptTokens + stats.completionTokens),
                )
                // v301：按用户自填单价估算的花费。
                // 一条消息都没定上价时**不显示这一行** —— 显示「≈ 0」会被当成「免费」。
                if (cost != null && cost.hasAnyPrice) {
                    StatsRow(
                        label = stringResource(R.string.chat_conversation_token_cost),
                        value = "≈ ${formatCost(cost.cost)}",
                    )
                    Text(
                        text = stringResource(R.string.chat_conversation_token_cost_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                // 有消息因为「模型没填价 / 模型已删」没被算进去时，必须如实说出来
                if (cost != null && cost.unpricedMessages > 0) {
                    Text(
                        text = stringResource(
                            R.string.chat_conversation_token_cost_unpriced,
                            cost.unpricedMessages,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.chat_conversation_token_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_conversation_token_close))
            }
        },
    )
}

@Composable
private fun StatsRow(
    label: String,
    value: String,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = labelStyle)
    }
}
