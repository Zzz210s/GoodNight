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

/**
 * 色阶分档(v1.14.0):**绝对标准 + 相对标准结合**。
 *
 * - **相对**:以"典型一天"([anchorMs],取非零日的中位数)为 1.0,按 0.5 / 1 / 2 / 3 倍分成五档 ——
 *   这样色阶随用户自己的量级伸缩:轻度用户 1 小时的一天也能显出深浅,重度用户 3 小时的常态不会挤成一色。
 * - **绝对**:典型一天本身被夹在 [ANCHOR_MIN_MS, ANCHOR_MAX_MS] 内(30 分钟 ~ 2 小时)——
 *   数据极少(全是 5 分钟)或极多(动辄 6 小时)时色阶不会被拉得失真;无数据时用 [DEFAULT_ANCHOR_MS]。
 *
 * 由此各档边界落在 15 分钟 ~ 6 小时之间,既有绝对意义又贴合个人量级。
 */
object HeatmapLevels {
    const val ANCHOR_MIN_MS: Long = 30 * 60_000L
    const val ANCHOR_MAX_MS: Long = 2 * 3_600_000L

    /** 无数据/无有效锚点时的典型一天:1 小时 */
    const val DEFAULT_ANCHOR_MS: Long = 3_600_000L

    /** 相对分档系数:L1 < 0.5×锚点,L2 < 1×,L3 < 2×,L4 < 3×,L5 >= 3× */
    private val FACTORS = listOf(0.5, 1.0, 2.0, 3.0)

    /** 典型一天 = 非零日总量的中位数,夹在绝对窗口内 */
    fun anchorMs(dayTotals: Collection<Long>): Long {
        val positives = dayTotals.filter { it > 0 }.sorted()
        if (positives.isEmpty()) return DEFAULT_ANCHOR_MS
        val mid = positives.size / 2
        val median = if (positives.size % 2 == 1) positives[mid]
        else (positives[mid - 1] + positives[mid]) / 2
        return median.coerceIn(ANCHOR_MIN_MS, ANCHOR_MAX_MS)
    }

    fun of(millis: Long, anchorMs: Long = DEFAULT_ANCHOR_MS): HeatLevel {
        if (millis <= 0) return HeatLevel.NONE
        val a = anchorMs.coerceIn(ANCHOR_MIN_MS, ANCHOR_MAX_MS)
        return when {
            millis < a * FACTORS[0] -> HeatLevel.L1
            millis < a * FACTORS[1] -> HeatLevel.L2
            millis < a * FACTORS[2] -> HeatLevel.L3
            millis < a * FACTORS[3] -> HeatLevel.L4
            else -> HeatLevel.L5
        }
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
    val anchor = HeatmapLevels.anchorMs(days.values)
    // 周日对齐:首个使用日所在周的周日
    val first = firstDataDate.minusDays((firstDataDate.dayOfWeek.value % 7).toLong())
    val weekStarts = generateSequence(first) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(today) }
        .toList()
        .ifEmpty { listOf(first) }

    fun cellOf(d: LocalDate): DayCell? = when {
        d.isAfter(today) -> null
        d.isBefore(firstDataDate) -> null
        else -> DayCell(d, days[d] ?: 0L, HeatmapLevels.of(days[d] ?: 0L, anchor))
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
