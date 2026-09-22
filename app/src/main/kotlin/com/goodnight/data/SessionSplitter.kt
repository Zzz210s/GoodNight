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

/** 切分结果按段起点(本地日)落表;每段带同一 [taskId](null = 未绑定任务) */
fun buildSessionRows(
    profileId: Long,
    startAt: Long,
    endAt: Long,
    zone: ZoneId,
    taskId: Long? = null,
    extraCuts: List<Long> = emptyList(),
): List<FocusSessionEntity> =
    splitAtMidnights(startAt, endAt, zone, extraCuts).map { (s, e) ->
        FocusSessionEntity(profileId = profileId, startAt = s, endAt = e, taskId = taskId)
    }

/** 该段起始日(切分后每段同日期,用段起点判断归属日) */
fun segmentLocalDate(segmentStartMs: Long, zone: ZoneId): LocalDate =
    java.time.Instant.ofEpochMilli(segmentStartMs).atZone(zone).toLocalDate()
