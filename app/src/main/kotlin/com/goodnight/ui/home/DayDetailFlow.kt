package com.goodnight.ui.home

import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileTotal
import com.goodnight.data.db.TaskEntity
import com.goodnight.data.mergeSessions
import com.goodnight.di.AppGraph
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * v2.1 Task 7:每日详情流(从 [HomeViewModel] 抽出,让 VM 留在 200 行内)。
 *
 * 以选中日总额为键驱动重查:dayTotals 是 Room 失效通知流,新增记录后会重发,
 * 每次变化重新查询 breakdownByDate,避免选中期间卡片停留在旧总额上。
 * v2.1:再并上任务表 —— 任务重命名后卡片里的任务名即时跟着改。
 */
internal fun dayDetailFlow(graph: AppGraph, day: LocalDate, from: String): Flow<DayDetailUi> {
    val zone = ZoneId.systemDefault()
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return graph.totalsRepo.dayTotals(from)
        .map { totals -> totals.firstOrNull { it.date == day.toString() }?.total ?: 0L }
        .distinctUntilChanged()
        .map { graph.totalsRepo.breakdownByDate(day.toString()) }
        .combine(graph.profileRepo.profiles) { rows, profiles -> rows to profiles }
        .combine(graph.taskRepo.observeAll()) { (rows, profiles), tasks ->
            buildDayDetail(graph, day, rows, profiles, tasks, start, end)
        }
}

private suspend fun buildDayDetail(
    graph: AppGraph,
    day: LocalDate,
    dailyRows: List<ProfileTotal>,
    profiles: List<ProfileEntity>,
    tasks: List<TaskEntity>,
    start: Long,
    end: Long,
): DayDetailUi {
    val known = profiles.associateBy { it.id }
    val titles = tasks.associate { it.id to it.title }
    // v1.10.8:有段落的配置——行合计 = 该行时间段之和(同一份数据);
    // 已删除配置的段落直接不参与(不再出现"已删除配置"行)。
    val grouped = graph.totalsRepo.sessionsBetween(start, end)
        .filter { it.profileId in known.keys }
        .groupBy { it.profileId }
    val fromSessions = grouped.map { (pid, ses) ->
        val spans = mergeSessions(ses.map { it.startAt to it.endAt })
        DayDetailRow(
            profileName = known.getValue(pid).name,
            millis = spans.sumOf { it.second - it.first },
            index = 0,
            sessions = spans,
            taskSpans = taskSpansOf(ses, titles),
        )
    }
    // 无段落但有历史合计的(旧版本数据/计时进行中的检查点):保留合计,无时间段
    val legacy = dailyRows
        .filter { it.profileId in known.keys && it.profileId !in grouped.keys && it.total > 0 }
        .map { r -> DayDetailRow(profileName = known.getValue(r.profileId).name, millis = r.total, index = 0) }
    val rows = (fromSessions + legacy).sortedByDescending { it.millis }
        .mapIndexed { i, r -> r.copy(index = i) }
    return DayDetailUi(date = day, totalMillis = rows.sumOf { it.millis }, rows = rows)
}
