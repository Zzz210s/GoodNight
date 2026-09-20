package com.embertimer.ui.heatmap

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

data class DayCell(
    val date: LocalDate,
    val millis: Long,
    val level: HeatLevel,
)

data class WeekColumn(val weekStart: LocalDate, val cells: List<DayCell>)

data class HeatmapModel(
    val columns: List<WeekColumn>,
    val monthLabels: Map<Int, String>,
    val weekLabels: List<String> = listOf("", "1", "", "3", "", "5", ""), // 周标为数字:行=周日→周六,标 周一=1/周三=3/周五=5
)

enum class HeatLevel { NONE, L1, L2, L3, L4, L5 }

object HeatmapLevels {
    /**
     * v1.14.0:四色改**五色渐进** —— 在 30 分钟与 2 小时之间插入 1 小时档,
     * 让最常见的 40 分钟~2 小时区间分得更细(实测数据多落在此区间)。
     * 分档:<30 分钟 / <1 小时 / <2 小时 / <4 小时 / >=4 小时。
     */
    fun of(millis: Long): HeatLevel = when {
        millis <= 0 -> HeatLevel.NONE
        millis < 30 * 60_000L -> HeatLevel.L1
        millis < 3_600_000L -> HeatLevel.L2
        millis < 2 * 3_600_000L -> HeatLevel.L3
        millis < 4 * 3_600_000L -> HeatLevel.L4
        else -> HeatLevel.L5
    }
}

/** D4:月份标签固定英文缩写,与系统语言无关 */
private val MONTH_ABBREVIATIONS = mapOf(
    Month.JANUARY to "Jan", Month.FEBRUARY to "Feb", Month.MARCH to "Mar",
    Month.APRIL to "Apr", Month.MAY to "May", Month.JUNE to "Jun",
    Month.JULY to "Jul", Month.AUGUST to "Aug", Month.SEPTEMBER to "Sep",
    Month.OCTOBER to "Oct", Month.NOVEMBER to "Nov", Month.DECEMBER to "Dec",
)

/** 全历史构建(GitHub 布局):行 = 周日→周六(周日顶行);列 = 周;星期标签 Mon/Wed/Fri;
 * 月份标签放在"含该月 1 日"的那一列顶;首记录前/未来日不产出格子 */
fun buildHeatmapModel(days: Map<LocalDate, Long>, today: LocalDate): HeatmapModel {
    val firstDataDate = days.keys.minOrNull() ?: today
    // 周日对齐:首个使用日所在周的周日
    val first = firstDataDate.minusDays((firstDataDate.dayOfWeek.value % 7).toLong())
    val weekStarts = generateSequence(first) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(today) }
        .toList()
        .ifEmpty { listOf(first) }

    fun cellOf(d: LocalDate): DayCell? = when {
        d.isAfter(today) -> null
        d.isBefore(firstDataDate) -> null
        else -> DayCell(d, days[d] ?: 0L, HeatmapLevels.of(days[d] ?: 0L))
    }

    val columns = weekStarts.map { ws ->
        WeekColumn(ws, (0..6).mapNotNull { dowIdx -> cellOf(ws.plusDays(dowIdx.toLong())) })
    }

    // GitHub 月份标签:每个月的 1 日落到哪一列,就在该列顶标注该月缩写(1 日居中的列也标注)
    val monthLabels = LinkedHashMap<Int, String>()
    var cursor = firstDataDate.withDayOfMonth(1)
    while (!cursor.isAfter(today)) {
        val colIdx = (java.time.temporal.ChronoUnit.DAYS.between(first, cursor) / 7L)
            .toInt().coerceAtLeast(0)
        if (colIdx < weekStarts.size) monthLabels[colIdx] = MONTH_ABBREVIATIONS.getValue(cursor.month)
        cursor = cursor.plusMonths(1)
    }
    return HeatmapModel(columns, monthLabels)
}
