package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ContextUsageInfo
import java.util.Locale

/**
 * 输入框上方常显的「当前上下文占用」。
 *
 * 显示口径与自动压缩的触发判定完全一致（见 ChatService 的 buildContextUsageInfo），
 * 所以这里的数字走到分母时，就是真的会触发自动压缩。
 *
 * - 有真实用量时直接显示服务商返回的数字；只能本地估算时前面标「约」。
 * - 自动压缩关闭时没有参照上限，只显示已用量，不显示进度条和百分比，
 *   避免让用户以为到了某个值会自动发生什么。
 */
@Composable
fun ContextUsageBar(
    info: ContextUsageInfo,
    modifier: Modifier = Modifier,
) {
    val approx = info.isEstimated && info.usedTokens > 0L
    val usedText = formatTokenCount(info.usedTokens)

    val label = if (info.hasLimit) {
        stringResource(
            if (approx) R.string.chat_context_usage_with_limit_approx
            else R.string.chat_context_usage_with_limit,
            usedText,
            formatTokenCount(info.limitTokens.toLong()),
        )
    } else {
        stringResource(
            if (approx) R.string.chat_context_usage_no_limit_approx
            else R.string.chat_context_usage_no_limit,
            usedText,
        )
    }

    val accentColor = when {
        info.overLimit -> MaterialTheme.colorScheme.error
        info.nearLimit -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val textColor = if (info.overLimit || info.nearLimit) {
        accentColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (info.hasLimit) {
            val animatedFraction by animateFloatAsState(
                targetValue = info.fraction,
                label = "contextUsageFraction",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clearAndSetSemantics { },
            ) {
                if (animatedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(accentColor),
                    )
                }
            }
            Text(
                text = stringResource(R.string.chat_context_usage_percent, info.percent),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

/** 12345 -> 12.3K，1234567 -> 1.23M。用 Locale.US 保证小数点写法稳定。 */
private fun formatTokenCount(tokens: Long): String = when {
    tokens <= 0L -> "0"
    tokens < 1_000L -> tokens.toString()
    tokens < 1_000_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
}
