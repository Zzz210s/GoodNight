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
}
