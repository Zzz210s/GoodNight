package com.goodnight.ui.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon
import com.goodnight.ui.theme.MotionTokens
import com.goodnight.ui.theme.rememberAnimationsEnabled

/**
 * 报表期次选择器(v1.10.12:与首页下拉菜单统一格式)。
 *
 * 顶行为「上一期 | 期次标签(可点) | 下一期」;点击标签在同一 Column 内展开**全宽面板**
 * (布局流内占位 -> 高度动画把报表内容整体顺沿下移,收起回弹),面板体与首页 PanelBody 同款:
 * surface 背景 + 分隔线 + 全宽行(20dp/14dp)+ 当前期次右侧对号。
 * 候选从"首次打开日"起按周/月分段到今日,可滚动且限高 320dp。
 *
 * 历史:v1.9.11 的 ExposedDropdownMenuBox(SubcomposeLayout 与 LazyColumn intrinsic 冲突会崩)、
 * v1.9.x 的 Popup 浮层 —— 现统一为首页同款布局流面板。
 */
@Composable
internal fun PeriodPicker(
    range: ReportRange,
    anchor: java.time.LocalDate,
    canGoNext: Boolean,
    /** v1.9.13 #43:往期回顾下限(首次打开日);候选不早于此 */
    minDate: java.time.LocalDate?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: (java.time.LocalDate) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = periodLabel(range, anchor)

    // 候选:从首次打开日起按周/月分段到今日;无下限时回退最近 9 期。remember 缓存避免每帧重算。
    val candidates = remember(range, anchor, minDate) {
        val today = java.time.LocalDate.now()
        if (minDate != null) {
            buildList {
                var d = when (range) {
                    ReportRange.WEEK -> minDate.minusDays((minDate.dayOfWeek.value - 1).toLong())
                    ReportRange.MONTH -> minDate.withDayOfMonth(1)
                    ReportRange.LIFETIME -> today
                }
                while (!d.isAfter(today)) {
                    add(d)
                    d = when (range) {
                        ReportRange.WEEK -> d.plusWeeks(1)
                        ReportRange.MONTH -> d.plusMonths(1)
                        ReportRange.LIFETIME -> today
                    }
                }
            }
        } else {
            buildList {
                for (i in -6..2) {
                    val d = if (range == ReportRange.WEEK) anchor.plusWeeks(i.toLong()) else anchor.plusMonths(i.toLong())
                    if (!d.isAfter(today)) add(d)
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrev) { Text(stringResource(R.string.report_prev)) }
            Row(
                Modifier.clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PathIcon(
                    d = IconPaths.CHEVRON_DOWN,
                    size = 14.dp,
                    contentDescription = stringResource(R.string.report_pick_period),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            TextButton(onClick = onNext, enabled = canGoNext) { Text(stringResource(R.string.report_next)) }
        }
        val animationsOn = rememberAnimationsEnabled()
        if (animationsOn) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(MotionTokens.TextSwapEnter.durationMillis)) +
                    fadeIn(tween(MotionTokens.TextSwapEnter.durationMillis)),
                exit = shrinkVertically(tween(MotionTokens.TextSwapExit.durationMillis)) +
                    fadeOut(tween(MotionTokens.TextSwapExit.durationMillis)),
            ) {
                PeriodPanel(range, anchor, candidates) { d -> onJump(d); expanded = false }
            }
        } else if (expanded) {
            PeriodPanel(range, anchor, candidates) { d -> onJump(d); expanded = false }
        }
    }
}

/** 面板体:与首页 PanelBody 同款(surface + 分隔线 + 全宽行 + 选中对号) */
@Composable
private fun PeriodPanel(
    range: ReportRange,
    anchor: java.time.LocalDate,
    candidates: List<java.time.LocalDate>,
    onPick: (java.time.LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            candidates.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(d) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(periodLabel(range, d), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (d == anchor) {
                        PathIcon(
                            IconPaths.CHECK, size = 20.dp, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** 报表窗口标签:周显示 MM-dd ~ MM-dd;月显示 yyyy-MM */
internal fun periodLabel(range: ReportRange, anchor: java.time.LocalDate): String {
    val (from, to) = reportWindow(range, anchor)
    return when (range) {
        ReportRange.WEEK -> "${from.substring(5)} ~ ${to.substring(5)}"
        ReportRange.MONTH -> from.substring(0, 7)
        ReportRange.LIFETIME -> ""
    }
}
