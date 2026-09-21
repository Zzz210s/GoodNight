package com.goodnight.ui.heatmap

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

/**
 * 色阶分档(v1.14.0):**绝对标准 + 相对标准结合**。
 *
 * - **相对**:四个分档边界取非零日总量的 **20 / 40 / 60 / 80 分位** —— 色阶随用户自己的量级伸缩,
 *   重度用户的常态不会挤成一色,轻度用户的较好一天也能显出深浅。
 * - **绝对**:每个分位都被夹在**绝对区间**内(见 [ABSOLUTE_BOUNDS]),保证任何数据量下色阶都有
 *   实际意义:全是 5 分钟的日子不会因为"相对最高"而变成最深档,动辄 6 小时的重度用户也不会把
 *   2 小时的一天看成最浅档。无数据时用 [DEFAULT_BOUNDS]。
 */
object HeatmapLevels {
    /** 相对分位点(非零日总量) */
    private val PERCENTILES = listOf(20, 40, 60, 80)

    /** 各分位对应的绝对夹紧区间(下界~上界,ms)—— 绝对标准所在 */
    private val ABSOLUTE_BOUNDS = listOf(
        20 * 60_000L to 40 * 60_000L, // L1/L2:20 ~ 40 分钟
        40 * 60_000L to 90 * 60_000L, // L2/L3:40 分钟 ~ 1.5 小时
        60 * 60_000L to 3 * 3_600_000L, // L3/L4:1 ~ 3 小时
        90 * 60_000L to 6 * 3_600_000L, // L4/L5:1.5 ~ 6 小时
    )

    /** 无数据时的边界:30 分钟 / 1 小时 / 2 小时 / 4 小时 */
    val DEFAULT_BOUNDS: List<Long> = listOf(30 * 60_000L, 60 * 60_000L, 2 * 3_600_000L, 4 * 3_600_000L)

    /** 由数据算出四个边界:分位数各自夹在绝对区间内,并保证严格递增。有效数据少于 3 天时退回缺省绝对边界(分位无意义) */
    fun boundsMs(dayTotals: Collection<Long>): List<Long> {
        val positives = dayTotals.filter { it > 0 }.sorted()
        if (positives.size < MIN_DAYS_FOR_RELATIVE) return DEFAULT_BOUNDS
        var prev = 0L
        return PERCENTILES.mapIndexed { i, p ->
            val (lo, hi) = ABSOLUTE_BOUNDS[i]
            val value = maxOf(percentile(positives, p).coerceIn(lo, hi), prev + 1)
            prev = value
            value
        }
    }

    /** 少于这么多天有效数据时,相对分位不可靠 → 用缺省绝对边界 */
    private const val MIN_DAYS_FOR_RELATIVE = 3

    fun of(millis: Long, bounds: List<Long> = DEFAULT_BOUNDS): HeatLevel {
        if (millis <= 0) return HeatLevel.NONE
        bounds.forEachIndexed { i, b -> if (millis < b) return HeatLevel.entries[i + 1] }
        return HeatLevel.L5
    }

    /** 线性插值分位数(数据量小也稳定) */
    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.size == 1) return sorted[0]
        val idx = (p / 100.0) * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = kotlin.math.ceil(idx).toInt()
        if (lo == hi) return sorted[lo]
        val frac = idx - lo
        return (sorted[lo] * (1 - frac) + sorted[hi] * frac).toLong()
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
 * 月份标签放在"含该月 1 日"的那一列顶;首记录前/未来日不产出格子。
 * 色阶锚点(典型一天)由**全部非零日**的中位数得出,再夹在绝对窗口内(见 [HeatmapLevels])。 */
fun buildHeatmapModel(days: Map<LocalDate, Long>, today: LocalDate): HeatmapModel {
    val firstDataDate = days.keys.minOrNull() ?: today
    val bounds = HeatmapLevels.boundsMs(days.values)
    // 周日对齐:首个使用日所在周的周日
    val first = firstDataDate.minusDays((firstDataDate.dayOfWeek.value % 7).toLong())
    val weekStarts = generateSequence(first) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(today) }
        .toList()
        .ifEmpty { listOf(first) }

    fun cellOf(d: LocalDate): DayCell? = when {
        d.isAfter(today) -> null
        d.isBefore(firstDataDate) -> null
        else -> DayCell(d, days[d] ?: 0L, HeatmapLevels.of(days[d] ?: 0L, bounds))
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
