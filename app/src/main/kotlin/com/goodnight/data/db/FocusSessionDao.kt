package com.goodnight.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FocusSessionDao {
    @Insert
    suspend fun insertAll(rows: List<FocusSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAllIgnore(rows: List<FocusSessionEntity>)

    @Query("SELECT * FROM focus_session ORDER BY startAt") suspend fun getAll(): List<FocusSessionEntity>

    /** 某日(本地时区 [dayStartMs, dayEndMs))内全部段,按开始时间升序 */
    @Query(
        "SELECT * FROM focus_session WHERE startAt >= :dayStartMs AND startAt < :dayEndMs " +
            "ORDER BY startAt"
    )
    suspend fun between(dayStartMs: Long, dayEndMs: Long): List<FocusSessionEntity>

    /** 某任务最近的工作段(起点倒序取 [limit] 条);疲劳提醒据此算"连续工作"累计 */
    @Query("SELECT * FROM focus_session WHERE profileId = :profileId ORDER BY startAt DESC LIMIT :limit")
    suspend fun recentForProfile(profileId: Long, limit: Int): List<FocusSessionEntity>

    @Query("SELECT COUNT(*) FROM focus_session")
    suspend fun count(): Int

    /** v2.1 Task 6:某任务已记录的段落合计毫秒(删除确认文案「已记录的 N 分钟」);无段为 0 */
    @Query("SELECT COALESCE(SUM(endAt - startAt), 0) FROM focus_session WHERE taskId = :taskId")
    suspend fun totalMillisForTask(taskId: Long): Long

    /** 短于阈值(误触)的段:endAt - startAt < :minMs */
    @Query("SELECT * FROM focus_session WHERE endAt - startAt < :minMs")
    suspend fun shorterThan(minMs: Long): List<FocusSessionEntity>

    @Query("DELETE FROM focus_session WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)

    @Query("DELETE FROM focus_session WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 墙钟窗口内全部段(起点升序);供报表时段分布 */
    @Query("SELECT * FROM focus_session WHERE startAt >= :startMs AND startAt < :endMs ORDER BY startAt")
    suspend fun betweenMs(startMs: Long, endMs: Long): List<FocusSessionEntity>

    /**
     * v2.1 Task 8:窗口内按任务分组取时长与段数。归属口径与 [betweenMs] 完全一致
     * (段起点落在窗口内),因此跨午夜段按起点归日、次数 = 切段后的行数。
     * 未绑定段的 taskId 为 NULL,单列一组;排序交给仓库层(未绑定必须固定末位)。
     */
    @Query(
        "SELECT taskId, SUM(endAt - startAt) AS millis, COUNT(*) AS count FROM focus_session " +
            "WHERE startAt >= :startMs AND startAt < :endMs GROUP BY taskId"
    )
    suspend fun taskTotalsBetween(startMs: Long, endMs: Long): List<TaskTotalRow>

    /**
     * v2.2 Task 6:窗口内按「任务 × 时钟」分组取时长与段数(过滤口径与 [taskTotalsBetween] 逐字一致),
     * 供报表「按任务」区块展开出各时钟明细。taskId 为 NULL = 未绑定任务。
     */
    @Query(
        "SELECT taskId, profileId, SUM(endAt - startAt) AS millis, COUNT(*) AS count FROM focus_session " +
            "WHERE startAt >= :startMs AND startAt < :endMs GROUP BY taskId, profileId"
    )
    suspend fun taskProfileTotalsBetween(startMs: Long, endMs: Long): List<TaskProfileTotalRow>
}

/** v2.1 Task 8:按 taskId 分组的聚合行(`taskId` 为 null = 未绑定) */
data class TaskTotalRow(val taskId: Long?, val millis: Long, val count: Int)

/** v2.2 Task 6:按「taskId × profileId」分组的细行(`taskId` 为 null = 未绑定任务) */
data class TaskProfileTotalRow(val taskId: Long?, val profileId: Long, val millis: Long, val count: Int)
