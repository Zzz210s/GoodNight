package com.goodnight.data

import androidx.room.withTransaction
import com.goodnight.data.db.DailyTotalDao
import com.goodnight.data.db.FocusSessionDao
import com.goodnight.data.db.DailyTotalEntity
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.DayProfileTotal
import com.goodnight.data.db.DayTotal
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileTotal
import com.goodnight.timer.TimeProvider
import kotlinx.coroutines.flow.Flow

class DailyTotalRepository(
    private val db: GoodNightDatabase,
    private val dao: DailyTotalDao,
    private val sessionDao: FocusSessionDao,
    private val time: TimeProvider,
) {
    /** API 26-28 的 SQLite 不支持 UPSERT 语法,用事务读-改-写兼容 */
    suspend fun addWork(date: String, profileId: Long, deltaMillis: Long) {
        if (deltaMillis <= 0) return
        db.withTransaction {
            val cur = dao.getWorkMillis(date, profileId) ?: 0L
            dao.upsert(DailyTotalEntity(date, profileId, cur + deltaMillis, time.now()))
        }
    }

    fun dayTotals(from: String): Flow<List<DayTotal>> = dao.observeDayTotals(from)

    /** v1.9.13 #43:最早有数据日期(报表往期回顾起点) */
    suspend fun earliestDate(): String? = dao.earliestDate()
    fun profileTotals(): Flow<List<ProfileTotal>> = dao.observeProfileTotals()

    suspend fun breakdownByDate(date: String): List<ProfileTotal> = dao.breakdownByDate(date)

    /** 区间内每日每配置明细(from/to 闭区间);供报表按日/按配置在内存聚合 */
    suspend fun rangeBreakdown(from: String, to: String): List<DayProfileTotal> =
        dao.rangeBreakdown(from, to)

    /**
     * 落一段专注(墙钟窗口),按本地午夜 + [extraCuts] 切分后逐段入库;zone 注入便于测试。
     * [taskId] 写在每一段上(v2.1);默认参数下与旧行为一致。
     */
    suspend fun recordWorkSession(
        profileId: Long,
        startAt: Long,
        endAt: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        taskId: Long? = null,
        extraCuts: List<Long> = emptyList(),
    ) {
        val rows = buildSessionRows(profileId, startAt, endAt, zone, taskId, extraCuts)
        if (rows.isNotEmpty()) db.withTransaction { sessionDao.insertAll(rows) }
    }

    /** 某本地日 [dayStartMs, dayEndMs) 内的全部段(升序);供每日详情小行 */
    suspend fun sessionsBetween(dayStartMs: Long, dayEndMs: Long): List<FocusSessionEntity> =
        sessionDao.between(dayStartMs, dayEndMs)

    /** 墙钟窗口内全部段(起点升序);供报表时段分布 */
    suspend fun sessionsBetweenMs(startMs: Long, endMs: Long): List<FocusSessionEntity> =
        sessionDao.betweenMs(startMs, endMs)

    /**
     * v2.1 Task 8:窗口内按任务分解(段起点归属,与 [sessionsBetweenMs] 同口径)。
     * 标题在仓库层用 task 表补齐;窗口内无段 → 空列表(界面不渲染该区块),
     * 有段时「未绑定」恒在末位(即使 0)。排序/占比口径见 [taskSlices]/[taskPercents]。
     *
     * v2.2 Task 6:每个任务行再附上它的时钟明细([TaskSlice.clocks])。名称用 **profile 全表**
     * (`profileDao().getAll()`,含归档)解析 —— 归档时钟的名字在报表里仍要显示。
     */
    suspend fun taskBreakdown(fromMs: Long, toMs: Long): List<TaskSlice> {
        val rows = sessionDao.taskTotalsBetween(fromMs, toMs)
        if (rows.isEmpty()) return emptyList()
        val titles = db.taskDao().allNow().associate { it.id to it.title }
        val names = db.profileDao().getAll().associate { it.id to it.name }
        val clocks = clockSlicesByTask(sessionDao.taskProfileTotalsBetween(fromMs, toMs), titles, names)
        return taskSlices(rows, titles).map { it.copy(clocks = clocks[it.taskId].orEmpty()) }
    }

    /**
     * v1.8.3:按暂停窗口分段落库——>=[minMs] 的暂停把整段切分为多段(每段不含长暂停间隙)。
     * 入库粒度只是"保留时间空档";**可见的分合由展示层决定**(v1.10:间隔 <= 3 分钟合并,
     * 见 [mergeSessions]),因此 [minMs] 只需 <= 展示阈值即可覆盖所有需要留痕的暂停。
     *
     * v2.1 Task 3:[taskId]/[extraCuts] 只在 [mergeSessions] **之后**施加 —— 任务切点是相邻段,
     * 若先切再合并会被合并规则(gap=0 <= 3 分钟)吞掉。
     * [taskCuts] 每项 = (切点墙钟 ms, 从该切点起那一段的 taskId),让**一次调用内各段分属不同任务**
     * (Task 5 的 09:00-09:30 任务 A / 09:30-10:00 任务 B 场景);段归属规则见 [buildSessionRows]。
     * 不要用"分两次调用各带一个 taskId"代替它 —— 单段不足 3 分钟会被最小跨度规则整段丢弃。
     * 三参数均有默认值,既有调用方零改动。
     */
    suspend fun recordWorkSessionSplit(
        profileId: Long,
        startAt: Long,
        endAt: Long,
        pauses: List<LongArray>,
        minMs: Long = MERGE_GAP_MS,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        taskId: Long? = null,
        extraCuts: List<Long> = emptyList(),
        taskCuts: List<Pair<Long, Long?>> = emptyList(),
    ) {
        val cur = startAt
        val segs = mutableListOf<Pair<Long, Long>>()
        var from = startAt
        pauses.filter { (it[1] - it[0]) >= minMs }.sortedBy { it[0] }.forEach { p ->
            if (p[0] > from) segs += (from to p[0])
            from = p[1]
        }
        if (endAt > from) segs += (from to endAt)
        // v1.11.1:数据层规则 —— 间隔 <=3 分钟合并、合并后 <3 分钟的段落不落库(与展示同源)
        val rows = ArrayList<com.goodnight.data.db.FocusSessionEntity>()
        mergeSessions(segs).forEach { (st, en) ->
            val r = buildSessionRows(profileId, st, en, zone, taskId, extraCuts, taskCuts)
            if (r.isNotEmpty()) { sessionDao.insertAll(r); rows += r }
        }
        // v1.10.8:当日合计改为"由段落派生"—— 与每日详情显示的时间段完全一致(而不是另算一份)
        rows.map { segmentLocalDate(it.startAt, zone).toString() }.distinct()
            .forEach { recomputeDay(it, zone) }
    }

    /**
     * v1.10.8:重算某日合计 = Σ(该日各配置段落经展示规则合并后的时长)。
     * 这样"总累计"与"每日详情各时间段之和"必然相等(单一数据源,不再单独计算)。
     */
    suspend fun recomputeDay(date: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val start = java.time.LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = java.time.LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = sessionDao.between(start, end)
        db.withTransaction {
            dao.deleteByDate(date)
            rows.groupBy { it.profileId }.forEach { (pid, list) ->
                val total = mergeSessions(list.map { it.startAt to it.endAt }).sumOf { it.second - it.first }
                if (total > 0) dao.upsert(DailyTotalEntity(date, pid, total, time.now()))
            }
        }
    }

    /** v1.10.8:删除配置时级联清掉它的段落与每日合计(否则"已删除配置"仍会出现在每日详情里) */
    suspend fun deleteProfileData(profileId: Long) {
        db.withTransaction {
            sessionDao.deleteByProfile(profileId)
            dao.deleteByProfile(profileId)
        }
    }

    /** v1.10.8:全量重算(升级用):把所有已有日期的合计按新规则重算 */
    suspend fun recomputeAllDays(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        dao.getAll().map { it.date }.distinct().forEach { recomputeDay(it, zone) }
    }

    /** v1.10.8:数据变动心跳(自动备份监听用) */
    fun dataTick(): Flow<Long> = dao.observeDataTick()

    /** v1.6 误触清理:删除短于 [minMs] 的段并把其时长从当日合计扣回(一次全量) */
    suspend fun pruneMisTouchSessions(minMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val short = sessionDao.shorterThan(minMs)
        if (short.isEmpty()) return
        db.withTransaction {
            short.forEach { r ->
                sessionDao.deleteById(r.id)
                val date = java.time.Instant.ofEpochMilli(r.startAt).atZone(zone).toLocalDate().toString()
                val cur = dao.getWorkMillis(date, r.profileId) ?: 0L
                val remain = (cur - (r.endAt - r.startAt)).coerceAtLeast(0L)
                dao.upsert(DailyTotalEntity(date, r.profileId, remain, time.now()))
            }
        }
    }
}

/**
 * 入库分段阈值 = 展示合并阈值([MERGE_GAP_MS],3 分钟):暂停只要不超过 3 分钟就视为"连续专注",
 * 入库不切分、合计照算、展示也不断开 —— 三处同源,保证"总累计 == 每日详情时间段之和"。
 */
const val PAUSE_SPLIT_MIN_MS: Long = MERGE_GAP_MS
