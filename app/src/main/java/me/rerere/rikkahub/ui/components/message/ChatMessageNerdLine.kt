package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.displayedGenerationSteps
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.text.NumberFormat
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量），点击查看详情
 *
 * 多轮工具调用时 usage 是各步累计值，行内只显示最后一次请求的上下文占用，累计值放在详情里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    val settings = LocalSettings.current
    val usage = message.usage
    if (!settings.displaySetting.showTokenUsage || usage == null) return

    val stats = remember(message) { message.nerdStats(usage) }
    // 消息里的模型快照不带上下文窗口，只能从设置里按 ID 找；模型被删了就不显示窗口大小
    val contextLength = remember(settings.providers, message.modelId) {
        settings.findModelById(message.modelId)?.contextLength?.takeIf { it > 0 }
    }
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = { Text(stringResource(R.string.chat_message_usage_title)) },
            ) {
                UsageDetails(stats, contextLength, message)
            }
        },
        state = tooltipState,
        enableUserInput = false,
        modifier = modifier,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
            CompositionLocalProvider(LocalContentColor provides color) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { tooltipState.show() } }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (stats.steps > 1) {
                        StatsItem(
                            icon = { StatsIcon(HugeIcons.Repeat, stringResource(R.string.chat_message_usage_step_count)) },
                            content = { Text(text = stringResource(R.string.chat_message_usage_steps, stats.steps)) }
                        )
                    }
                    if (stats.tps != null) {
                        StatsItem(
                            icon = { StatsIcon(HugeIcons.Zap, stringResource(R.string.chat_message_usage_speed)) },
                            content = { Text(text = "${stats.tps.toFixed(1)} tok/s") }
                        )
                    }
                    StatsItem(
                        icon = { StatsIcon(HugeIcons.Layers01, stringResource(R.string.chat_message_usage_context)) },
                        content = {
                            Text(text = stringResource(R.string.chat_message_usage_context_short, stats.contextTokens.formatNumber()))
                            val cached = stats.lastUsage.cachedTokens
                            if (cached > 0) {
                                Text(text = stringResource(R.string.chat_message_usage_cached_short, cached.formatNumber()))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageDetails(stats: NerdStats, contextLength: Int?, message: UIMessage) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        val total = stats.total
        ContextUsageBar(
            lastUsage = stats.lastUsage,
            contextLength = contextLength,
            message = message,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // v1014：按用户口径对齐官方用量面板。这几行以前被 `steps > 1` 整块挡住 ——
        // 单步对话（没调工具、没续跑）时面板里一行明细都没有，这就是两张截图的差别。
        // 现在无条件显示；只有「缓存总量」保留 >0 的判断（不支持缓存的供应商那行永远是 0，
        // 是噪音不是信息）。速度与时长按官方那样做成「行」，**数值仍用我们自己的算法**。
        UsageDetailRow(stringResource(R.string.chat_message_usage_step_count), stats.steps.toString())
        UsageDetailRow(stringResource(R.string.chat_message_usage_total_input), total.promptTokens.exact())
        if (total.cachedTokens > 0) UsageDetailRow(stringResource(R.string.chat_message_usage_total_cached), total.cachedTokens.exact())
        UsageDetailRow(stringResource(R.string.chat_message_usage_total_output), total.completionTokens.exact())
        val readout = SpeedReadout.of(message, total)
        // 三审 P2：「速度」这一行只放纯吐字口径。上一版这里挂的是 headlineTps，
        // 而它会兜到响应速度 —— 于是行标签写着「速度」、印出来的却是含首字等待的数，
        // 标签与数值不符。算不出纯吐字就干脆不显示这一行，不拿别的数顶。
        readout?.generationTps?.let {
            UsageDetailRow(stringResource(R.string.chat_message_usage_speed), "${it.toFixed(1)} tok/s")
        }
        // 官方只有一行速度；我们多出来的这行按自己的标签叫「响应速度」（含首字等待）。
        // 与上一行数值相同时不重复占位。
        readout?.responseTps?.takeIf { it != readout.generationTps }?.let {
            UsageDetailRow(stringResource(R.string.chat_message_usage_response_speed), "${it.toFixed(1)} tok/s")
        }
        readout?.seconds?.let {
            UsageDetailRow(stringResource(R.string.chat_message_usage_duration), "${it.toFixed(1)}s")
        }
    }
}

private data class UsageSegment(val label: String, val tokens: Int, val color: Color)

/**
 * 一条回答的速度与时长读数。**用量面板的行与底部小字共用这一份** ——
 * 以前两处各算一套（底部拿「输出÷墙钟」、面板拿「累计输出÷纯吐字」），
 * 真机上就出现过底部 8.9 tok/s 与面板 136.7 tok/s 并排的矛盾读数。
 *
 * v303 的口径原样保留：分子统一用这条回答的**真实累计输出**
 * （`cumulativeUsage`，工具轮/续跑轮全部相加，不再只取最后一轮）；
 * 分母不含工具执行时间、不含用户审批等待。外面工具分两派，两行各对应一派：
 * 「速度」只算模型真正在吐字的时间（LM Studio / Cherry Studio 口径，不含首字等待）；
 * 「响应速度」从发出请求算到本轮收完（Claude Code 插件 / ccstatusline 口径，含首字等待）。
 * 取不到新账本时（旧聊天记录、非流式那一趟）退回官方原算法，旧消息不掉数。
 * v1007 的教训也保留：这些读数原来写在 `segments.forEach` 里面，缓存/输入/输出有几段
 * 就重复几遍（3 段 = 3 份「生成 / 响应 / 秒数」），最后一份被挤成竖排 —— 用户报「统计很杂乱」。
 * 所以**一条消息只能出现一次**，且面板与底部必须同源。
 */
internal data class SpeedReadout(
    /**
     * 「速度」行与底部小字：按精度依次兜底 —— 累计纯吐字 → 本条消息的生成耗时 → 墙钟。
     *
     * 三审 P2：上一版只算墙钟，把旧消息本来就存着的 generationTimeMs 丢了，
     * 那会让旧消息的速度数凭空变慢（口径倒退）。
     */
    val generationTps: Float?,
    /** 「响应速度」行：含首字等待，只有新账本才算得出来；算不出就不显示这一行 */
    val responseTps: Float?,
    val seconds: Float?,
) {
    /** 底部小字只有一个位置：优先纯吐字口径 */
    val headlineTps: Float? get() = generationTps ?: responseTps

    companion object {
        /** 没有 finishedAt（还在跑）时返回 null，界面就不显示速度，不猜。 */
        fun of(message: UIMessage, total: TokenUsage): SpeedReadout? {
            val finishedAt = message.finishedAt ?: return null
            val wallClockMs = Duration.between(
                message.createdAt.toJavaLocalDateTime(),
                finishedAt.toJavaLocalDateTime(),
            ).toMillis()
            val cumulative = message.cumulativeUsage
            val outputTokens = cumulative?.completionTokens ?: total.completionTokens
            // 一律走 computeTokenSpeed：它对「耗时 ≤ 0 / 输出 ≤ 0」返回 null，不会拿 0 或
            // 近似 0 的分母冒充出一个速度。三审 P2：极快消息墙钟可能为 0，那种情况该是
            // 这一格没数，而不是整块读数消失（上一版直接 return null 就是这个毛病）。
            val generation = cumulative?.let {
                computeTokenSpeed(outputTokens, message.cumulativeGenerationMs)
            } ?: computeTokenSpeed(total.completionTokens, message.generationTimeMs)
              ?: computeTokenSpeed(total.completionTokens, wallClockMs.takeIf { it > 0L })
            val response = cumulative?.let {
                computeTokenSpeed(outputTokens, message.cumulativeResponseMs)
            }
            val seconds = wallClockMs.takeIf { it > 0L }?.let { it / 1000f }
            if (generation == null && response == null && seconds == null) return null
            return SpeedReadout(
                generationTps = generation,
                responseTps = response,
                seconds = seconds,
            )
        }
    }
}

/**
 * 最后一次请求的上下文占用：缓存命中、未命中的输入、输出依次排开，剩下的是窗口里的空余
 */
@Composable
private fun ContextUsageBar(
    lastUsage: TokenUsage,
    contextLength: Int?,
    message: UIMessage,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val settings = LocalSettings.current
    val color = colorScheme.secondary.copy(alpha = 0.5f)
    val segments = listOf(
        UsageSegment(stringResource(R.string.chat_message_usage_cached), lastUsage.cachedTokens, colorScheme.primary),
        UsageSegment(
            stringResource(R.string.chat_message_usage_input),
            (lastUsage.promptTokens - lastUsage.cachedTokens).coerceAtLeast(0),
            colorScheme.primary.copy(alpha = 0.45f),
        ),
        UsageSegment(stringResource(R.string.chat_message_usage_output), lastUsage.completionTokens, colorScheme.tertiary),
    ).filter { it.tokens > 0 }
    val contextTokens = lastUsage.promptTokens + lastUsage.completionTokens
    // 窗口未知或已经超出时，占用本身铺满整条
    val capacity = maxOf(contextLength ?: 0, contextTokens, 1)
    val hasFreeSpace = capacity > contextTokens
    val trackColor = colorScheme.surfaceContainerHighest

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chat_message_usage_context),
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            if (contextLength != null) {
                val fraction = contextTokens.toFloat() / contextLength
                Text(
                    text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()} · ${(fraction * 100).toFixed(0)}%",
                    color = if (fraction >= 0.9f) colorScheme.error else Color.Unspecified,
                )
            } else {
                Text(text = contextTokens.exact())
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val gap = 2.dp.toPx()
            val radius = CornerRadius(size.height / 2)
            // 每段之间留出间隔；还有空余时最后一段和空余之间也要留
            val gaps = if (hasFreeSpace) segments.size else segments.size - 1
            val available = size.width - gap * gaps
            var x = 0f
            segments.forEach { segment ->
                if (x >= size.width) return@forEach
                // 太小的段至少画成一个圆点，不然看不出来
                val width = (segment.tokens.toFloat() / capacity * available).coerceAtLeast(size.height)
                drawRoundRect(
                    color = segment.color,
                    topLeft = Offset(x, 0f),
                    size = Size(width.coerceAtMost(size.width - x), size.height),
                    cornerRadius = radius,
                )
                x += width + gap
            }
            if (hasFreeSpace && x < size.width) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(size.width - x, size.height),
                    cornerRadius = radius,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            segments.forEach { segment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(segment.color, CircleShape)
                    )
                    Text(
                        text = "${segment.label} ${segment.tokens.exact()}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            // v1014：速度与时长按用户口径改成用量面板里的「行」（对齐官方布局），不再挤在
            // 进度条下面当格子；算法与 v303 / v1007 的口径完全没变，全部搬进 [SpeedReadout]，
            // 由面板行与底部小字共用同一份数字（以前两处各算一套，真机出现过
            // 底部 8.9 tok/s、面板 136.7 tok/s 并排显示的矛盾）。
            // v269：续跑次数 —— 只在消息真的续跑过（次数 > 0）且设置开启时显示，
            // 正常回复不占任何位置；开关在 设置→显示→「显示续跑次数」。
            // v1007：同样从 segments.forEach 挪出来，否则有几段就重复几遍。
            if (settings.displaySetting.showResumeCount && message.resumeCount > 0) {
                StatsItem(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = "Resume count",
                            tint = color,
                            modifier = Modifier.size(12.dp)
                        )
                    },
                    content = {
                        Text(text = stringResource(R.string.chat_message_resume_count, message.resumeCount))
                    }
                )
            }
        }
    }
}

@Composable
private fun UsageDetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(text = value)
    }
}

@Composable
private fun StatsIcon(imageVector: ImageVector, contentDescription: String) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(12.dp)
    )
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}

private data class NerdStats(
    val total: TokenUsage,
    val lastUsage: TokenUsage,
    val steps: Int,
    val contextTokens: Int,
    // v1014：这里原来有 durationMillis —— 它只给底部那个速度数当分母；
    // 现在底部与面板同源走 [SpeedReadout]，留着会让下一个人以为时长还从这儿出。
    val tps: Float?,
)

private fun UIMessage.nerdStats(usage: TokenUsage): NerdStats {
    val last = lastRequestUsage() ?: usage
    return NerdStats(
        total = usage,
        lastUsage = last,
        steps = displayedGenerationSteps(stepCount, cumulativeRequestCount),
        contextTokens = last.promptTokens + last.completionTokens,
        // v1014：与面板「速度」行同源（同一份 SpeedReadout），不再各算一套
        tps = SpeedReadout.of(this, usage)?.headlineTps,
    )
}

private fun Int.exact(): String = NumberFormat.getIntegerInstance().format(this)
