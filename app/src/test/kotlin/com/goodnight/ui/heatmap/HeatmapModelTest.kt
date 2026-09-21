package com.goodnight.ui.heatmap

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-09-07 是周一;2026-09-01 是周二;2026-09-09 是周三;2026-08-28 是周五 */
class HeatmapModelTest {
    private val today = LocalDate.of(2026, 9, 9)

    @Test fun emptyDataYieldsCurrentWeekUpToToday() {
        val m = buildHeatmapModel(emptyMap(), today)
        assertEquals(1, m.columns.size)
        // 新语义:无数据时 firstDataDate = today,首记录前空白 -> 仅 today 1 格
        assertEquals(listOf(today), m.columns[0].cells.map { it.date })
    }

    @Test fun noDataTodayOnly() {
        val m = buildHeatmapModel(emptyMap(), today) // today 2026-09-09 周三
        val cells = m.columns.flatMap { it.cells }
        assertEquals(listOf(today), cells.map { it.date }) // 仅 today 一个 cell(today 前/后无)
    }

    @Test fun unusedDaysAreCellsWithNoneNotMissing() {
        // 数据在周一(首记录),其后至 today 的未使用日仍渲染 NONE 格
        val monday = LocalDate.of(2026, 9, 7)
        val m = buildHeatmapModel(mapOf(monday to 3_600_000L), today)
        val cells = m.columns[0].cells
        assertEquals(3, cells.size)
        assertEquals(monday, cells[0].date)
        assertEquals(HeatLevel.L3, cells[0].level)
        assertEquals(HeatLevel.NONE, cells[1].level)
        assertEquals(0L, cells[1].millis)
        assertEquals(HeatLevel.NONE, cells[2].level)
    }

    @Test fun daysBeforeFirstRecordAreBlank() {
        // 首数据 2026-08-28(周五);窗口首列 = 8/24 周
        val m = buildHeatmapModel(mapOf(LocalDate.of(2026, 8, 28) to 60_000L), today)
        val cells = m.columns.flatMap { it.cells }
        assertTrue(cells.none { it.date.isBefore(LocalDate.of(2026, 8, 28)) }) // 首数据前无 cell
        assertTrue(cells.any { it.date == LocalDate.of(2026, 8, 28) })
    }

    @Test fun boundsAreClampedAndMonotonic() {
        // 无数据/全零/有效数据不足 3 天 → 缺省绝对边界
        assertEquals(HeatmapLevels.DEFAULT_BOUNDS, HeatmapLevels.boundsMs(emptyList()))
        assertEquals(HeatmapLevels.DEFAULT_BOUNDS, HeatmapLevels.boundsMs(listOf(0L, 0L)))
        assertEquals(HeatmapLevels.DEFAULT_BOUNDS, HeatmapLevels.boundsMs(listOf(3_600_000L, 0L)))
        // 数据极小:分位被绝对下界兜住(20/40/60/90 分钟)
        assertEquals(
            listOf(20 * 60_000L, 40 * 60_000L, 60 * 60_000L, 90 * 60_000L),
            HeatmapLevels.boundsMs(listOf(5 * 60_000L, 6 * 60_000L, 7 * 60_000L)),
        )
        // 数据极大:分位被绝对上界兜住(40 分钟 / 1.5 / 3 / 6 小时)
        assertEquals(
            listOf(40 * 60_000L, 90 * 60_000L, 3 * 3_600_000L, 6 * 3_600_000L),
            HeatmapLevels.boundsMs(listOf(5 * 3_600_000L, 6 * 3_600_000L, 7 * 3_600_000L)),
        )
        // 任意输入都单调递增(不出现交叉)
        val b = HeatmapLevels.boundsMs(listOf(1_000L, 20 * 60_000L, 10 * 3_600_000L))
        assertTrue(b.zipWithNext().all { (x, y) -> y > x })
    }

    @Test fun levelsFollowBounds() {
        val bounds = listOf(30 * 60_000L, 60 * 60_000L, 2 * 3_600_000L, 3 * 3_600_000L)
        assertEquals(HeatLevel.NONE, HeatmapLevels.of(0, bounds))
        assertEquals(HeatLevel.L1, HeatmapLevels.of(29 * 60_000L, bounds))
        assertEquals(HeatLevel.L2, HeatmapLevels.of(30 * 60_000L, bounds))
        assertEquals(HeatLevel.L3, HeatmapLevels.of(3_600_000L, bounds))
        assertEquals(HeatLevel.L4, HeatmapLevels.of(2 * 3_600_000L, bounds))
        assertEquals(HeatLevel.L5, HeatmapLevels.of(3 * 3_600_000L, bounds))
        // 缺省边界(无数据)= 30 分钟 / 1 小时 / 2 小时 / 4 小时
        assertEquals(HeatLevel.L3, HeatmapLevels.of(3_600_000L))
        assertEquals(HeatLevel.L5, HeatmapLevels.of(4 * 3_600_000L))
    }

    @Test fun relativeScaleAmplifiesLightUserGradient() {
        // 轻量用户的三天(20/28/40 分钟):纯绝对标准会把前两天都归最浅档,
        // 绝对+相对结合后本人最好的一天应高出至少一档
        val days = mapOf(
            LocalDate.of(2026, 9, 7) to 20 * 60_000L,
            LocalDate.of(2026, 9, 8) to 28 * 60_000L,
            LocalDate.of(2026, 9, 9) to 40 * 60_000L,
        )
        val byDate = buildHeatmapModel(days, today).columns.flatMap { it.cells }.associateBy { it.date }
        val hybridTop = byDate.getValue(today).level
        val absoluteTop = HeatmapLevels.of(40 * 60_000L) // 纯绝对(默认锤点 1 小时)
        assertTrue(
            "相对标准应比纯绝对标准更能体现本人较好的一天:$hybridTop vs $absoluteTop",
            hybridTop.ordinal > absoluteTop.ordinal,
        )
    }

    @Test fun dayCellHasNoJoinFlags() {
        val d = LocalDate.of(2026, 9, 1)
        val m = buildHeatmapModel(mapOf(d to 3_600_000L), today)
        val c = m.columns.flatMap { it.cells }.first { it.date == d }
        assertEquals(HeatLevel.L3, c.level)
        // join 字段已随直角渲染移除(编译期保证:DayCell 仅 date/millis/level)
        assertEquals(d, c.date)
        assertEquals(3_600_000L, c.millis)
    }

    @Test fun monthLabelsOnlyOnMonthChange() {
        // 两列:8/31 周(Aug 起始? 8/31 是周一)与 9/7 周;首列不标
        val d1 = LocalDate.of(2026, 9, 1) // 8/31 周
        val d2 = LocalDate.of(2026, 9, 8) // 9/7 周
        val m = buildHeatmapModel(mapOf(d1 to 60_000L, d2 to 60_000L), today)
        // GitHub:月份标签放在"含该月 1 日"的那一列 —— 9/1 落在首列(8/30 周),故标 col0
        assertEquals(mapOf(0 to "Sep"), m.monthLabels)
    }

    @Test fun monthLabelCrossesYearBoundaryOnMonthChange() {
        val m = buildHeatmapModel(
            mapOf(LocalDate.of(2026, 12, 20) to 60_000L),
            LocalDate.of(2027, 1, 6),
        )
        // GitHub:月份标签按"含 1 日"列 —— 12/1 在首列(12/20 周起点前,标 col0),1/1 在第二列(col1)
        assertEquals(mapOf(0 to "Dec", 1 to "Jan"), m.monthLabels)
    }
}
