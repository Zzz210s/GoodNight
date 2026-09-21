package com.goodnight.data

import com.goodnight.data.db.FocusSessionEntity

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v1.3 #6:把一段专注 [startAt..endAt](墙钟 ms)按本地午夜切分为若干同日期段。
 * 纯函数,ZoneId 注入可测。endAt <= startAt 时返回空;跨 N 个午夜产出 N+1 段。
 */
fun splitAtMidnights(startAt: Long, endAt: Long, zone: ZoneId): List<Pair<Long, Long>> {
    if (endAt <= startAt) return emptyList()
    val segs = ArrayList<Pair<Long, Long>>()
    var cur = startAt
    while (true) {
        val nextMidnight = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(cur), zone)
            .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (endAt <= nextMidnight) { segs.add(cur to endAt); break }
        segs.add(cur to nextMidnight)
        cur = nextMidnight
    }
    return segs
}

/** 切分结果按段起点(本地日)落表;返回插入行数(0 = 无有效段) */
fun buildSessionRows(profileId: Long, startAt: Long, endAt: Long, zone: ZoneId): List<FocusSessionEntity> =
    splitAtMidnights(startAt, endAt, zone).map { (s, e) -> FocusSessionEntity(profileId = profileId, startAt = s, endAt = e) }

/** 该段起始日(切分后每段同日期,用段起点判断归属日) */
fun segmentLocalDate(segmentStartMs: Long, zone: ZoneId): LocalDate =
    java.time.Instant.ofEpochMilli(segmentStartMs).atZone(zone).toLocalDate()
