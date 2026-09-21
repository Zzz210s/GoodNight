package com.goodnight.data

import java.time.Instant
import java.time.ZoneId

/**
 * v1.10:每日详情「时段明细」的展示规则(取代 v1.8.3 的"暂停超过阈值即分段"规则)。
 *
 * 入库粒度不变(仍按暂停窗分段,保留长暂停造成的时间空档),但**可见的分合由本文件决定**:
 * 前一段结束 -> 后一段开始的间隔不超过 [MERGE_GAP_MS] 即合并为一条;超过则保留为两条。
 * 因此旧规则(阈值决定一切)被废除——现在只有"间隔 > 3 分钟"才会显示成两段。
 */
const val MERGE_GAP_MS: Long = 3 * 60_000

/** 合并后的段落若短于该阈值则视为无意义,整段丢弃(不显示、也不计入合计) */
const val MIN_SPAN_MS: Long = 3 * 60_000

/**
 * 合并相邻段并按阈值丢弃过短段落 —— 每日详情展示与当日合计**共用**这一函数,
 * 因此"总累计"必然等于界面上可见时间段之和(单一数据源)。
 *
 * 规则:①空段(终点 <= 起点)剔除;②间隔(后段起点 - 前段终点) <= [maxGapMs] 时并入前段;
 * ③合并后时长 < [minSpanMs] 的段落整段丢弃(3 分钟以下视为无意义)。
 */
fun mergeSessions(
    sessions: List<Pair<Long, Long>>,
    maxGapMs: Long = MERGE_GAP_MS,
    minSpanMs: Long = MIN_SPAN_MS,
): List<Pair<Long, Long>> {
    val sorted = sessions.filter { it.second > it.first }.sortedBy { it.first }
    if (sorted.isEmpty()) return emptyList()
    val out = ArrayList<Pair<Long, Long>>(sorted.size)
    var curStart = sorted[0].first
    var curEnd = sorted[0].second
    for (i in 1 until sorted.size) {
        val (s, e) = sorted[i]
        if (s - curEnd <= maxGapMs) {
            if (e > curEnd) curEnd = e
        } else {
            out += curStart to curEnd
            curStart = s
            curEnd = e
        }
    }
    out += curStart to curEnd
    return out.filter { it.second - it.first >= minSpanMs }
}

/** 一天中的大时段;展示文案由 UI 层映射到字符串资源(中英双语) */
enum class DayPeriod { DAWN, EARLY_MORNING, MORNING, AFTERNOON, EVENING }

/** 以段起点所在小时判定大时段:凌晨 0-5 / 早上 6-8 / 上午 9-11 / 下午 12-17 / 晚上 18-23 */
fun dayPeriodOf(startWallMs: Long, zone: ZoneId = ZoneId.systemDefault()): DayPeriod =
    when (Instant.ofEpochMilli(startWallMs).atZone(zone).hour) {
        in 0..5 -> DayPeriod.DAWN
        in 6..8 -> DayPeriod.EARLY_MORNING
        in 9..11 -> DayPeriod.MORNING
        in 12..17 -> DayPeriod.AFTERNOON
        else -> DayPeriod.EVENING
    }

/**
 * 按时段分组(展示层):输入段按起点升序,同一大时段的连续段合为一组。
 * 用于"左侧单列时段标识 + 右侧双列时间段"的排版。
 */
fun groupByPeriod(
    sessions: List<Pair<Long, Long>>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<DayPeriod, List<Pair<Long, Long>>>> {
    val groups = ArrayList<Pair<DayPeriod, MutableList<Pair<Long, Long>>>>()
    for (s in sessions) {
        val p = dayPeriodOf(s.first, zone)
        val last = groups.lastOrNull()
        if (last != null && last.first == p) last.second.add(s) else groups.add(p to mutableListOf(s))
    }
    return groups.map { (p, list) -> p to list.toList() }
}
