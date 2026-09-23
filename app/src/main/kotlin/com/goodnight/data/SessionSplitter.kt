package com.goodnight.data

import com.goodnight.data.db.FocusSessionEntity

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v1.3 #6:把一段专注 [startAt..endAt](墙钟 ms)按本地午夜切分为若干同日期段。
 * v2.1 Task 3:再加 [extraCuts] 切点(任务切换时刻)—— 只保留落在 (startAt, endAt)
 * **开区间**内的值(等于端点的不切,否则产出零长段),去重排序后与午夜边界合并成
 * 同一条边界列表,逐段产出。
 * 纯函数,ZoneId 注入可测。endAt <= startAt 时返回空;跨 N 个午夜产出 N+1 段。
 */
fun splitAtMidnights(
    startAt: Long,
    endAt: Long,
    zone: ZoneId,
    extraCuts: List<Long> = emptyList(),
): List<Pair<Long, Long>> {
    if (endAt <= startAt) return emptyList()
    val bounds = sortedSetOf(startAt, endAt)
    extraCuts.forEach { if (it > startAt && it < endAt) bounds.add(it) }
    var day = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(startAt), zone).toLocalDate()
    while (true) {
        day = day.plusDays(1)
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
        if (midnight >= endAt) break
        bounds.add(midnight)
    }
    return bounds.toList().zipWithNext { s, e -> s to e }
}

/**
 * 切分结果按段起点(本地日)落表;每段带自己的任务绑定。
 *
 * [taskCuts] 每项 = (切点墙钟 ms, 从该切点起那一段的 taskId),用于一次调用内各段分属不同任务。
 * 其切点与 [extraCuts]、午夜边界合并成**同一条边界列表**(仍只取 (startAt, endAt) 开区间内的值),
 * 然后逐段归属:段起点之前或等于起点的**最后一个** taskCuts 项给出该段 id;一项都不在其前时
 * 回退到参数 [taskId]。同一时刻多项时取列表靠后的那项。显式传 null 表示"从此段起未绑定",
 * **不会**回退到 [taskId]。两参数均有默认值,既有调用方零改动。
 */
fun buildSessionRows(
    profileId: Long,
    startAt: Long,
    endAt: Long,
    zone: ZoneId,
    taskId: Long? = null,
    extraCuts: List<Long> = emptyList(),
    taskCuts: List<Pair<Long, Long?>> = emptyList(),
): List<FocusSessionEntity> {
    val cuts = extraCuts + taskCuts.map { it.first }
    val ordered = taskCuts.sortedBy { it.first }
    return splitAtMidnights(startAt, endAt, zone, cuts).map { (s, e) ->
        val hit = ordered.lastOrNull { it.first <= s }
        FocusSessionEntity(profileId = profileId, startAt = s, endAt = e, taskId = if (hit == null) taskId else hit.second)
    }
}

/** 该段起始日(切分后每段同日期,用段起点判断归属日) */
fun segmentLocalDate(segmentStartMs: Long, zone: ZoneId): LocalDate =
    java.time.Instant.ofEpochMilli(segmentStartMs).atZone(zone).toLocalDate()
