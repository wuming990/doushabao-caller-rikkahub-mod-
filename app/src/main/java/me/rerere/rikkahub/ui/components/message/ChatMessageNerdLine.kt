package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.promptTokens.formatNumber()} tokens")
                            // Cached tokens
                            if (usage.cachedTokens > 0) {
                                Text(
                                    text = "(${message.usage?.cachedTokens?.formatNumber() ?: "0"} cached)"
                                )
                            }
                        }
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.completionTokens.formatNumber()} tokens")
                        }
                    )
                    // v303：两个速度都显示。分子统一用这条回答的**真实累计输出**
                    // （cumulativeUsage，工具轮/续跑轮全部相加，不再只取最后一轮）；
                    // 分母不含工具执行时间、不含用户审批等待。外面工具分两派，两格各对应一派：
                    // 「生成」只算模型真正在吐字的时间（LM Studio / Cherry Studio 口径，不含首字等待）；
                    // 「响应」从发出请求算到本轮收完（Claude Code 插件 / ccstatusline 口径，含首字等待）。
                    // 取不到新账本时（旧聊天记录、非流式那一趟）退回官方原算法，旧消息不掉数。
                    if (message.finishedAt != null) {
                        val cumulative = message.cumulativeUsage
                        val outputTokens = cumulative?.completionTokens ?: usage.completionTokens
                        val generationSpeed = if (cumulative != null) {
                            computeTokenSpeed(outputTokens, message.cumulativeGenerationMs)
                        } else {
                            null
                        }
                        val responseSpeed = if (cumulative != null) {
                            computeTokenSpeed(outputTokens, message.cumulativeResponseMs)
                        } else {
                            null
                        }
                        val duration = Duration.between(
                            message.createdAt.toJavaLocalDateTime(),
                            message.finishedAt!!.toJavaLocalDateTime()
                        )
                        val seconds = (duration.toMillis() / 1000f).toFixed(1)
                        if (generationSpeed != null || responseSpeed != null) {
                            generationSpeed?.let { speed ->
                                StatsItem(
                                    icon = {
                                        Icon(
                                            imageVector = HugeIcons.Zap,
                                            contentDescription = "Generation speed",
                                            tint = color,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    },
                                    content = {
                                        Text(text = "生成 ${speed.toFixed(1)} tok/s")
                                    }
                                )
                            }
                            responseSpeed?.let { speed ->
                                StatsItem(
                                    icon = {
                                        Icon(
                                            imageVector = HugeIcons.Zap,
                                            contentDescription = "Response speed",
                                            tint = color,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    },
                                    content = {
                                        Text(text = "响应 ${speed.toFixed(1)} tok/s")
                                    }
                                )
                            }
                        } else {
                            // 官方原算法兜底（旧消息）：整段墙钟 + 水位口径输出
                            val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
                            StatsItem(
                                icon = {
                                    Icon(
                                        imageVector = HugeIcons.Zap,
                                        contentDescription = "Speed",
                                        modifier = Modifier.size(12.dp)
                                    )
                                },
                                content = {
                                    Text(text = "${tps.toFixed(1)} tok/s")
                                }
                            )
                        }

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            }
                        )
                    }
                }
                // v269：续跑次数 —— 只在消息真的续跑过（次数 > 0）且设置开启时显示，
                // 正常回复不占任何位置；开关在 设置→显示→「显示续跑次数」。
                if (settings.showResumeCount && message.resumeCount > 0) {
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
