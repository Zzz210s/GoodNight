package com.goodnight.data

import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.FocusSessionDao
import com.goodnight.data.db.FocusSessionEntity
import java.time.Instant
import java.time.ZoneId

/**
 * v1.11.1:把"时段规则"落到**数据层**(不再只是展示层过滤)。
 *
 * 规则(与 [mergeSessions] 同源):相邻段间隔 <=3 分钟合并为一段;合并后仍不足 3 分钟的段落
 * 直接**从数据库删除**(不存储、不显示、不计入合计)。
 *
 * 用途:①升级时一次性清洗历史行(normalizeAllSessionsOnce);②新写入走 recordWorkSessionSplit
 * (写库前已合并/丢弃,故此处对正常数据是幂等的)。
 */
internal object SessionNormalizer {

    /** 遍历全部段落,按(归属日, 配置)重算应存的行;有差异则替换,并重算该日合计。返回被改写的日期数。 */
    suspend fun normalizeAllSessionsOnce(
        db: GoodNightDatabase,
        sessionDao: FocusSessionDao,
        totals: DailyTotalRepository,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val all = sessionDao.getAll()
        if (all.isEmpty()) return 0
        val byDay = all.groupBy { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate().toString() }
        var changedDays = 0
        byDay.forEach { (date, rows) ->
            if (normalizeDay(db, sessionDao, date, zone)) changedDays++
            totals.recomputeDay(date, zone)
        }
        return changedDays
    }

    /**
     * 清洗某日:按配置合并段落,删除过短/可合并的旧行,插入合并后的行。返回是否发生改写。
     *
     * 警告(v2.1):本函数的重建路径([buildSessionRows] 调用)**不携带 `taskId` 维度** —— 重插的行
     * 任务绑定为 null。当前调用链仅升级时一次性清洗旧数据(那时还没有任务绑定),所以不可达;
     * 但 2.1 之后若要再启用清洗,必须先把每段自己的 `taskId` 按段保留(按 taskId 分组合并或逐段搬运),
     * 否则会静默清空用户的任务绑定。不要顺手改成传单个 taskId —— 那是错语义。
     */
    private suspend fun normalizeDay(
        db: GoodNightDatabase,
        sessionDao: FocusSessionDao,
        date: String,
        zone: ZoneId,
    ): Boolean {
        val start = java.time.LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = java.time.LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = sessionDao.between(start, end)
        var changed = false
        rows.groupBy { it.profileId }.forEach { (profileId, list) ->
            val merged = mergeSessions(list.map { it.startAt to it.endAt })
            val stored = list.sortedBy { it.startAt }.map { it.startAt to it.endAt }
            if (merged != stored) {
                changed = true
                list.forEach { sessionDao.deleteById(it.id) }
                val fresh = merged.flatMap { (s, e) -> buildSessionRows(profileId, s, e, zone) }
                if (fresh.isNotEmpty()) sessionDao.insertAll(fresh.map { it.copy(id = 0) })
            }
        }
        return changed
    }
}

/** 便于测试/调用:构造清洗后的行(不落库) */
internal fun mergedRowsFor(rows: List<FocusSessionEntity>, zone: ZoneId): List<Pair<Long, Long>> =
    mergeSessions(rows.map { it.startAt to it.endAt })
