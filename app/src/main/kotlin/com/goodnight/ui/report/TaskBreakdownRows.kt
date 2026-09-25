package com.goodnight.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.goodnight.R
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon
import com.goodnight.ui.theme.BarAnim
import com.goodnight.ui.theme.rememberAnimationsEnabled

/** 测试钩子:时钟子行的语义 tag */
const val REPORT_CLOCK_ROW_TAG = "report_clock_row"

/**
 * 「按任务」主行:展开箭头(仅有明细的行有;无明细留同宽空位避免标签错位) + 标签 +
 * 比例条 + 时长/次数/占比。条形以本区最长任务为满格。
 */
@Composable
internal fun TaskBarRow(
    label: String,
    millis: Long,
    count: Int,
    percent: Int,
    max: Long,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp)) {
            if (expandable) {
                PathIcon(
                    IconPaths.CHEVRON_DOWN,
                    size = 18.dp,
                    contentDescription = stringResource(R.string.report_clock_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.weight(1.5f).height(12.dp).padding(end = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        ) {
            val target = (millis.toFloat() / max).coerceIn(0f, 1f)
            val anim = rememberAnimationsEnabled()
            val fraction by animateFloatAsState(
                targetValue = if (anim) target else target,
                animationSpec = if (anim) BarAnim else tween(0),
                label = "taskBarFraction",
            )
            Box(
                Modifier.fillMaxWidth(fraction).height(12.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
            )
        }
        Column(Modifier.weight(1.2f)) {
            Text(
                localizedDur(millis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.report_task_meta, count, percent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 时钟子行:缩进 + 只读文本(名字 — 时长 — 次数/占比);「未知时钟」= profile 行已被真删。
 * 左边距 = 18dp(主行箭头留白,见 [TaskBarRow]) + 18dp(层级步进),让子行名字明显右于任务名。
 */
@Composable
internal fun ClockBarRow(label: String, millis: Long, count: Int, percent: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 36.dp).testTag(REPORT_CLOCK_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            localizedDur(millis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.8f),
            maxLines = 1,
        )
        Text(
            stringResource(R.string.report_task_meta, count, percent),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}
