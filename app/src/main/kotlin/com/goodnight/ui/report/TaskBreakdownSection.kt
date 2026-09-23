package com.goodnight.ui.report

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.data.TaskSlice
import com.goodnight.data.taskPercents
import com.goodnight.ui.theme.BarAnim
import com.goodnight.ui.theme.rememberAnimationsEnabled

/** v2.1 Task 8:报表「按任务」区块的一行([title] 为 null = 未绑定) */
data class TaskSliceUi(
    val taskId: Long?,
    val title: String?,
    val millis: Long,
    val count: Int,
    val percent: Int,
)

/** 纯映射:补上整数占比(口径见 [taskPercents],各行合计恰为 100) */
fun taskBreakdownUi(slices: List<TaskSlice>): List<TaskSliceUi> {
    val percents = taskPercents(slices)
    return slices.mapIndexed { i, s ->
        TaskSliceUi(s.taskId, s.title, s.millis, s.count, percents[i])
    }
}

/**
 * 「按任务」区块:视觉沿用既有报表条形行(标签 — 比例条 — 时长/次数/占比),
 * 条形以本区最长任务为满格。空列表不渲染(无段时界面无此块)。
 */
@Composable
fun TaskBreakdownSection(slices: List<TaskSliceUi>) {
    if (slices.isEmpty()) return
    val max = slices.maxOf { it.millis }.coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.report_by_task), style = MaterialTheme.typography.titleSmall)
        slices.forEach { s ->
            TaskBarRow(
                label = s.title ?: stringResource(R.string.task_unbound),
                millis = s.millis,
                count = s.count,
                percent = s.percent,
                max = max,
            )
        }
    }
}

@Composable
private fun TaskBarRow(label: String, millis: Long, count: Int, percent: Int, max: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
