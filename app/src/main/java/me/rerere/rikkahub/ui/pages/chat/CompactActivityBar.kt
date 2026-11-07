package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01

/** v1023：收起的活动面板共用紧凑入口，不保留铺满宽度的底板。 */
@Composable
internal fun CompactActivityBar(
    agents: CompactActivitySummary?,
    group: CompactActivitySummary?,
    onExpandAgents: () -> Unit,
    onExpandGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (agents == null && group == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        agents?.let {
            CompactActivityChip(
                summary = it,
                onClick = onExpandAgents,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        group?.let {
            CompactActivityChip(
                summary = it,
                onClick = onExpandGroup,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun CompactActivityChip(
    summary: CompactActivitySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(
                role = Role.Button,
                onClickLabel = "展开${summary.label}",
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = summary.contentDescription
                stateDescription = "已收起"
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 28.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (summary.needsAttention) {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Text(
                text = summary.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clearAndSetSemantics {},
            )
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
