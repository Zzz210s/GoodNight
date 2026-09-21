package com.goodnight.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class DayTotal(val date: String, val total: Long)
data class ProfileTotal(val profileId: Long, val total: Long)
/** 报表区间明细:一行 = 某日某配置的累计专注时长(date 升序,profileId 升序) */
data class DayProfileTotal(val date: String, val profileId: Long, val total: Long)

@Dao
interface DailyTotalDao {
    @Query("SELECT workMillis FROM daily_total WHERE date = :date AND profileId = :profileId")
    suspend fun getWorkMillis(date: String, profileId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: DailyTotalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DailyTotalEntity>)

    @Query("SELECT date, SUM(workMillis) AS total FROM daily_total WHERE date >= :from GROUP BY date ORDER BY date")
    fun observeDayTotals(from: String): Flow<List<DayTotal>>

    @Query("SELECT profileId, SUM(workMillis) AS total FROM daily_total GROUP BY profileId")
    fun observeProfileTotals(): Flow<List<ProfileTotal>>

    @Query("SELECT profileId, SUM(workMillis) AS total FROM daily_total WHERE date = :date GROUP BY profileId")
    suspend fun breakdownByDate(date: String): List<ProfileTotal>

    @Query(
        "SELECT date, profileId, SUM(workMillis) AS total FROM daily_total " +
            "WHERE date >= :from AND date <= :to GROUP BY date, profileId ORDER BY date, profileId"
    )
    suspend fun rangeBreakdown(from: String, to: String): List<DayProfileTotal>

    @Query("SELECT * FROM daily_total ORDER BY date, profileId") suspend fun getAll(): List<DailyTotalEntity>

    @Query("DELETE FROM daily_total WHERE date = :date") suspend fun deleteByDate(date: String)

    @Query("DELETE FROM daily_total WHERE profileId = :profileId") suspend fun deleteByProfile(profileId: Long)

    /**
     * v1.10.8:数据版本"心跳"——任何表的插入/更新/删除都会改变该标量,
     * 供自动备份监听"App 数据变动"(尤其是计时累计变动)。
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM daily_total) * 1000003 " +
            "+ (SELECT COUNT(*) FROM focus_session) * 1009 " +
            "+ (SELECT COALESCE(SUM(workMillis), 0) FROM daily_total) " +
            "+ (SELECT COUNT(*) FROM profile) * 7"
    )
    fun observeDataTick(): Flow<Long>

    /** v1.9.13 #43:报表往期回顾起点 —— 最早有数据的日期 */
    @Query("SELECT MIN(date) FROM daily_total") suspend fun earliestDate(): String?
}
