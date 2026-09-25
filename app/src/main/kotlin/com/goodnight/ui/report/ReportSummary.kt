package com.goodnight.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodnight.ui.theme.BarAnim
import com.goodnight.ui.theme.MotionTokens
import com.goodnight.ui.theme.rememberAnimationsEnabled
import com.goodnight.R

/** 健康风报表可视化(指标 2×2 + 时段分布条 + 通用条形行),字段完整不裁切。 */
@Composable
fun ReportSummary(
    metrics: ReportMetrics,
    slots: List<SlotMinutes>,
    totalMillis: Long,
    isMonth: Boolean,
    showAvg: Boolean = true,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    localizedDur(totalMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                metrics.prevDeltaPercent?.let { d ->
                    val color = when {
                        d > 0 -> MaterialTheme.colorScheme.primary
                        d < 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val sign = when { d > 0 -> "+"; d < 0 -> "-"; else -> "" }
                    Text(
                        stringResource(if (isMonth) R.string.vs_last_month else R.string.vs_last_week) +
                            " $sign${kotlin.math.abs(d)}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
            }
            // 指标 2×2
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(stringResource(R.string.m_focus_days), "${metrics.focusDays}", Modifier.weight(1f))
                MetricTile(stringResource(R.string.m_streak), "${metrics.streakDays}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showAvg) {
                    MetricTile(stringResource(R.string.m_avg), localizedDur(metrics.avgMinutesPerDay * 60_000), Modifier.weight(1f))
                    MetricTile(stringResource(R.string.m_best), metrics.bestDay?.let { localizedDur(metrics.bestMinutes * 60_000) } ?: "—", Modifier.weight(1f), sub = metrics.bestDay)
                } else {
                    MetricTile(stringResource(R.string.m_best), metrics.bestDay?.let { localizedDur(metrics.bestMinutes * 60_000) } ?: "—", Modifier.weight(1f), sub = metrics.bestDay)
                    Spacer(Modifier.weight(1f))
                }
            }
            if (slots.isNotEmpty()) {
                Text(
                    stringResource(R.string.ts_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                slots.take(3).forEach { sm ->
                    FocusBarRow(bucketLabel(sm.bucket), sm.minutes, slots.first().minutes.coerceAtLeast(1))
                }
            }
        }
    }
}

/** 通用条形行:标签 — 比例条 — 数值(v1.8.2 加宽数值列,杜绝裁剪) */
@Composable
fun FocusBarRow(
    label: String,
    value: Long,
    max: Long,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
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
            val target = (if (max > 0) value.toFloat() / max else 0f).coerceIn(0f, 1f)
            val anim = rememberAnimationsEnabled()
            // 条形从 0 -> 目标弹性生长(系统关闭动画时直切);重放时随数据/目标变化自然重绘
            val fraction by animateFloatAsState(
                targetValue = target,
                animationSpec = if (anim) BarAnim else tween(0),
                label = "barFraction",
            )
            Box(
                Modifier.fillMaxWidth(fraction).height(12.dp)
                    .background(color, RoundedCornerShape(6.dp)),
            )
        }
        Text(
            localizedDur(value * 60_000),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.1f),
            maxLines = 1,
        )
    }
}

@Composable
fun BarsList(title: String, rows: List<Pair<String, Long>>) {
    val max = (rows.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        rows.forEach { (label, minutes) -> FocusBarRow(label = label, value = minutes, max = max) }
    }
}

@Composable
private fun MetricTile(caption: String, value: String, mod: Modifier = Modifier, sub: String? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = mod) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(caption, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun bucketLabel(bucket: TimeBucket): String = stringResource(
    when (bucket) {
        TimeBucket.MORNING -> R.string.bucket_morning
        TimeBucket.AFTERNOON -> R.string.bucket_afternoon
        TimeBucket.EVENING -> R.string.bucket_evening
        TimeBucket.NIGHT -> R.string.bucket_night
    },
)

@Composable
internal fun localizedDur(millis: Long): String {
    val totalMinutes = (millis + 59_999) / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h == 0L) stringResource(R.string.duration_m, m)
    else stringResource(R.string.duration_hm, h, m)
}
