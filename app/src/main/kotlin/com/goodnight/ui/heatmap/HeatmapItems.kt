package com.goodnight.ui.heatmap

import java.time.LocalDate

/** 网格项:Cell = 数据/NONE 格;Blank = 首记录前的占位(纯空白,保持 7 行列对齐) */
sealed interface HeatmapItem {
    data class Blank(val date: LocalDate) : HeatmapItem
    data class Cell(val cell: DayCell) : HeatmapItem
}

fun HeatmapItem.itemId(): String = when (this) {
    is HeatmapItem.Blank -> "b${date.toEpochDay()}"
    is HeatmapItem.Cell -> "c${cell.date.toEpochDay()}"
}

/**
 * 展平为 LazyHorizontalGrid 项序列:每列首格前的空缺日用 Blank 占位,
 * 否则首数据周不足 7 项时后续周会错位挤进同一网格列(网格按 7 行顺序填充)。
 */
fun gridItems(model: HeatmapModel): List<HeatmapItem> =
    model.columns.flatMap { col ->
        val prefix = col.cells.firstOrNull()?.let { it.date.dayOfWeek.value % 7 } ?: 0 // 周日=0
        List(prefix) { i -> HeatmapItem.Blank(col.weekStart.plusDays(i.toLong())) } +
            col.cells.map { HeatmapItem.Cell(it) }
    }
