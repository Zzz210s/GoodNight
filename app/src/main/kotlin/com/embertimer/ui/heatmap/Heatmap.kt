package com.embertimer.ui.heatmap

import com.embertimer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.embertimer.timer.DurationFormat
import java.time.LocalDate

private val CELL = 20.dp
private val GAP = 2.dp
/** v1.1 #2:统一 2dp 圆角,background 与 border 同 shape */
private val CELL_RADIUS = RoundedCornerShape(2.dp)
private val LEVEL_ALPHA = listOf(0.28f, 0.46f, 0.64f, 0.82f, 1f)

@Composable
fun levelColor(level: HeatLevel): Color {
    val primary = MaterialTheme.colorScheme.primary
    return when (level) {
        HeatLevel.NONE -> MaterialTheme.colorScheme.surfaceVariant
        HeatLevel.L1 -> primary.copy(alpha = LEVEL_ALPHA[0])
        HeatLevel.L2 -> primary.copy(alpha = LEVEL_ALPHA[1])
        HeatLevel.L3 -> primary.copy(alpha = LEVEL_ALPHA[2])
        HeatLevel.L4 -> primary.copy(alpha = LEVEL_ALPHA[3])
        HeatLevel.L5 -> primary.copy(alpha = LEVEL_ALPHA[4])
    }
}

@Composable
fun Heatmap(model: HeatmapModel, selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    // 默认落在最后一列(最新一周);数据有限,不用无限索引技巧
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = ((model.columns.size - 1) * 7).coerceAtLeast(0),
    )
    Column {
        MonthLabels(model, state)
        Row {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                model.weekLabels.forEach { label ->
                    Box(Modifier.size(CELL), contentAlignment = Alignment.Center) {
                        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            LazyHorizontalGrid(
                state = state,
                rows = GridCells.Fixed(7),
                modifier = Modifier.height(CELL * 7 + GAP * 6),
                horizontalArrangement = Arrangement.spacedBy(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                items(gridItems(model), key = { it.itemId() }) { item ->
                    when (item) {
                        is HeatmapItem.Blank -> Box(Modifier.size(CELL)) // 首记录前:纯空白占位,无背景/语义
                        is HeatmapItem.Cell -> HeatmapCell(
                            cell = item.cell,
                            isSelected = item.cell.date == selected,
                            onClick = { onSelect(if (item.cell.date == selected) null else item.cell.date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(cell: DayCell, isSelected: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(levelColor(cell.level), label = "cellColor")
    // D2(v1.1):2dp 圆角;常态保留极浅描边(onSurface 12%);选中 = 2dp primary 圆角边框
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val dur = if (cell.millis > 0) localDur(cell.millis)
    else stringResource(R.string.no_records_short)
    val desc = cell.date.toString() + ", " + dur
    Box(
        Modifier
            .size(CELL)
            .background(color, CELL_RADIUS)
            .border(if (isSelected) 2.dp else 0.5.dp, borderColor, CELL_RADIUS)
            .clickable(onClick = onClick, onClickLabel = desc)
            .semantics {
                contentDescription = desc
                this.selected = isSelected
            }
            .testTag("heatmap_cell_${cell.date.toEpochDay()}"),
    )
}

/** 月份标签行:读取网格 layoutInfo,把可见列首 item 的 x 偏移换算为标签位置(D4 英文缩写) */
@Composable
private fun MonthLabels(model: HeatmapModel, state: LazyGridState) {
    val density = LocalDensity.current
    // derivedStateOf 包裹:滚动帧内只在本叠加层重算,不引发整棵热力图重组
    val visibleItems by remember(state, model.monthLabels) {
        derivedStateOf { state.layoutInfo.visibleItemsInfo }
    }
    // v1.9.8:修复月份标签(如 Sep 的 p 降部)被下方方框覆盖 —— 行高 16->20dp 完整容纳文本行高,
    // 并 zIndex 抬高到其余行之上(布局顺序在前的叠加层默认画在下面,重叠时会被后画的行盖住)
    Box(Modifier.fillMaxWidth().height(20.dp).zIndex(2f)) {
        // 首帧可见列表为空则本帧不画,布局完成后的下一帧自动补上
        visibleItems.forEach { item ->
            if (item.index % 7 == 0) {
                val col = item.index / 7
                model.monthLabels[col]?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        // item.offset.x 相对网格视口;网格位于周标签列(宽 CELL)右侧,而本叠加层从父级左缘起算,需补 CELL;
                        // 列首滚出视口左侧时标签钉在周标签列右缘,不压星期列
                        modifier = Modifier.offset(
                            x = (CELL + with(density) { item.offset.x.toDp() }).coerceAtLeast(CELL),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun localDur(millis: Long): String {
    val totalMinutes = (millis + 59_999) / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h == 0L) stringResource(R.string.duration_m, m)
    else stringResource(R.string.duration_hm, h, m)
}
