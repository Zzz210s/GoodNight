package com.goodnight.ui.report

import com.goodnight.data.db.DayProfileTotal
import java.time.LocalDate

/**
 * 健康风周/月报指标(v1.5,参考健康/屏幕时间报表范式):
 * 窗口内专注天数、日均、最佳单日、连续专注天数、与上一等长窗口对比。纯函数可测。
 */
data class ReportMetrics(
    val focusDays: Int,
    val avgMinutesPerDay: Long,
    val bestDay: String?,      // MM-dd
    val bestMinutes: Long,
    val streakDays: Int,
    val prevDeltaPercent: Int?, // 相对上一窗口(负=下降);null=无对比(上窗口 0)
)

/** 上一等长窗口 [windowFrom..windowTo] 向前平移同长度 */
fun prevWindowOf(windowFrom: String, windowTo: String): Pair<String, String> {
    val from = LocalDate.parse(windowFrom)
    val to = LocalDate.parse(windowTo)
    val len = from.until(to).days.toLong() + 1
    val pEnd = from.minusDays(1)
    val pStart = pEnd.minusDays(len - 1)
    return pStart.toString() to pEnd.toString()
}

fun prevDeltaPercent(currentMillis: Long, prevMillis: Long?): Int? {
    if (prevMillis == null || prevMillis == 0L) return null
    return (((currentMillis - prevMillis) * 100) / prevMillis).toInt()
}

/** 连续专注天数:以窗口内最末有数据的日向窗口首方向数连续 */
fun focusStreak(days: Map<LocalDate, Long>): Int {
    val anchor = days.keys.maxOrNull() ?: return 0
    var streak = 0
    var d = anchor
    while (days[d] != null && days[d]!! > 0) {
        streak++
        d = d.minusDays(1)
    }
    return streak
}

/** 从原始行推窗口跨度天数(首尾含);空数据回 1 */
fun windowDaySpan(raw: List<DayProfileTotal>): Int {
    val dates = raw.map { LocalDate.parse(it.date) }
    if (dates.isEmpty()) return 1
    return dates.max().toEpochDay().toInt() - dates.min().toEpochDay().toInt() + 1
}

fun computeMetrics(raw: List<DayProfileTotal>): ReportMetrics {
    val days = raw.groupBy { LocalDate.parse(it.date) }
        .mapValues { (_, rs) -> rs.sumOf { it.total } }
    val best = days.maxByOrNull { it.value }
    val focusDays = days.count { it.value > 0 }
    val totalMin = raw.sumOf { it.total } / 60_000
    return ReportMetrics(
        focusDays = focusDays,
        avgMinutesPerDay = totalMin / windowDaySpan(raw),
        bestDay = best?.key?.toString()?.substring(5),
        bestMinutes = (best?.value ?: 0L) / 60_000,
        streakDays = focusStreak(days),
        prevDeltaPercent = null,
    )
}

/** 时段桶:上午 6-12 / 下午 12-18 / 晚上 18-23 / 深夜 23-6(参考健康App 时段分布) */
enum class TimeBucket { MORNING, AFTERNOON, EVENING, NIGHT }

fun bucketOf(hour: Int): TimeBucket = when {
    hour in 6..11 -> TimeBucket.MORNING
    hour in 12..17 -> TimeBucket.AFTERNOON
    hour in 18..22 -> TimeBucket.EVENING
    else -> TimeBucket.NIGHT
}

/** 时段聚合结果:桶 + 专注分钟 */
data class SlotMinutes(val bucket: TimeBucket, val minutes: Long)

/** 汇总:窗口指标(含对比)+ 时段分布。sessions 需已按窗口过滤(sessionDao.betweenMs)。 */
fun summarizeWindow(
    from: String,
    to: String,
    raw: List<DayProfileTotal>,
    sessions: List<com.goodnight.data.db.FocusSessionEntity>,
    prevTotalMillis: Long?,
    zone: java.time.ZoneId,
): Pair<ReportMetrics, List<SlotMinutes>> {
    val metrics = computeMetrics(raw)
    val current = raw.sumOf { it.total }
    val withDelta = metrics.copy(prevDeltaPercent = prevDeltaPercent(current, prevTotalMillis))
    val buckets = LongArray(4)
    for (s in sessions) {
        val hour = java.time.Instant.ofEpochMilli(s.startAt).atZone(zone).hour
        buckets[bucketOf(hour).ordinal] += (s.endAt - s.startAt)
    }
    val slots = TimeBucket.entries.mapIndexedNotNull { i, b ->
        if (buckets[i] > 0) SlotMinutes(b, buckets[i] / 60_000) else null
    }.sortedByDescending { it.minutes }
    return withDelta to slots
}
