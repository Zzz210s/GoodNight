package com.goodnight.ui.report

import com.goodnight.data.db.DayProfileTotal
import com.goodnight.data.db.ProfileEntity
import java.time.LocalDate

/**
 * 报表纯聚合(无 Android 依赖,见 ReportAggregateTest)。
 * v2.1 Task 8:从 ReportViewModel.kt 拆出 —— 该文件要接入「按任务」区块,受 200 行上限约束。
 */

data class ReportRow(
    val label: String,
    val millis: Long,
    /** v1.4 本地化辅助:月报桶序号与起止(MM-dd);0 = 非桶行 */
    val weekIndex: Int = 0,
    val rangeFrom: String = "",
    val rangeTo: String = "",
)

data class ProfileTotalUi(val profileName: String, val millis: Long)

/** 报表窗口(闭区间 ISO 日期):周 = 本周一(ISO 周一为一周首日)至 today;月 = 本月 1 日至 today。 */
fun reportWindow(range: ReportRange, today: LocalDate): Pair<String, String> = when (range) {
    ReportRange.WEEK -> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        monday.toString() to today.toString()
    }
    ReportRange.MONTH -> today.withDayOfMonth(1).toString() to today.toString()
    ReportRange.LIFETIME -> LocalDate.ofEpochDay(0).toString() to today.toString()
}

/**
 * 明细行。周报:逐日行(label "MM-dd");月报:按周分桶(label "第 N 周(MM-dd~MM-dd)",
 * 桶 = 当月按日切 7 天段,跨月末尾桶止于 today)。raw 只含窗口内有数据的单元,按日期升序。
 */
fun reportRows(range: ReportRange, today: LocalDate, raw: List<DayProfileTotal>): List<ReportRow> {
    val byDate = raw.groupBy { it.date }.mapValues { (_, rs) -> rs.sumOf { it.total } }
    val dates = byDate.toSortedMap()
    return when (range) {
        ReportRange.WEEK -> dates.map { (date, m) -> ReportRow(date.substring(5), m) }
        ReportRange.LIFETIME -> emptyList() // 长期累计走 profileTotals,明细为空
        ReportRange.MONTH -> dates.entries
            .groupBy { (date, _) -> (LocalDate.parse(date).dayOfMonth - 1) / 7 + 1 }
            .map { (bucket, dayEntries) ->
                val start = today.withDayOfMonth((bucket - 1) * 7 + 1)
                val end = start.plusDays(6).let { if (it.isAfter(today)) today else it }
                val from = start.toString().substring(5)
                val to = end.toString().substring(5)
                ReportRow("第 $bucket 周($from~$to)", dayEntries.sumOf { it.value }, bucket, from, to)
            }
    }
}

fun reportProfileTotals(
    profiles: List<ProfileEntity>,
    raw: List<DayProfileTotal>,
): List<ProfileTotalUi> = raw
    // v1.10.8:已删除配置不参与统计(其段落/合计在删除时已级联清理,这里再兜一层)
    .filter { r -> profiles.any { it.id == r.profileId } }
    .groupBy { it.profileId }
    .map { (id, rs) ->
        ProfileTotalUi(
            profileName = profiles.first { it.id == id }.name,
            millis = rs.sumOf { it.total },
        )
    }
    .sortedByDescending { it.millis }
