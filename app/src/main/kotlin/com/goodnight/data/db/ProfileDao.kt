package com.goodnight.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
    @Query("SELECT * FROM profile ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM profile WHERE id = :id") suspend fun byId(id: Long): ProfileEntity?
    @Query("SELECT * FROM profile WHERE name = :name LIMIT 1") suspend fun byName(name: String): ProfileEntity?
    @Query("SELECT mode FROM profile WHERE id = :id") suspend fun modeById(id: Long): Int?
    @Query("SELECT COUNT(*) FROM profile") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<ProfileEntity>)
    @Query("SELECT * FROM profile ORDER BY createdAt, id") suspend fun getAll(): List<ProfileEntity>

    // ---- v2.2 Task 1:时钟归属任务 ----

    /**
     * 作用域内同名判定:`taskId IS :taskId` 同时管「同任务」(IS 能比对 NULL,`=` 不行)。
     * 已归档行不占名 —— 它们从列表隐藏,占着名字会让用户看到「名已存在」却找不到那个时钟。
     */
    @Query("SELECT * FROM profile WHERE name = :name AND taskId IS :taskId AND archived = 0 LIMIT 1")
    suspend fun byNameInScope(name: String, taskId: Long?): ProfileEntity?

    /** 可用时钟[taskId]:该任务专属在前、全部通用在后,各自 createdAt/id 升序(排除归档) */
    @Query(
        "SELECT * FROM profile WHERE archived = 0 AND (taskId = :taskId OR taskId IS NULL) " +
            "ORDER BY CASE WHEN taskId IS NULL THEN 1 ELSE 0 END, createdAt, id"
    )
    fun observeAvailableFor(taskId: Long?): Flow<List<ProfileEntity>>

    /** 管理页:通用段在前(taskId NULL),专属段按 taskId/createdAt/id 分组(排除归档) */
    @Query(
        "SELECT * FROM profile WHERE archived = 0 " +
            "ORDER BY CASE WHEN taskId IS NULL THEN 0 ELSE 1 END, taskId, createdAt, id"
    )
    fun observeAllActive(): Flow<List<ProfileEntity>>

    /** 改归属:只改 taskId,不碰任何历史 */
    @Query("UPDATE profile SET taskId = :taskId WHERE id = :id")
    suspend fun moveTo(id: Long, taskId: Long?)

    /** 删除任务时把专属时钟转为通用(行保留:时间账与时钟设置都不丢) */
    @Query("UPDATE profile SET taskId = NULL WHERE taskId = :taskId")
    suspend fun clearTaskRefs(taskId: Long)

    /** 归档(行保留、列表隐藏);返回值不取,调用方先用 [referenceCount] 判定 */
    @Query("UPDATE profile SET archived = 1 WHERE id = :id")
    suspend fun archiveById(id: Long)

    @Query("DELETE FROM profile WHERE id = :id") suspend fun deleteById(id: Long)

    /** 引用计数:引用该时钟的会话段 + 每日合计(任一 > 0 则只能归档、不能真删) */
    @Query(
        "SELECT (SELECT COUNT(*) FROM focus_session WHERE profileId = :id) + " +
            "(SELECT COUNT(*) FROM daily_total WHERE profileId = :id)"
    )
    suspend fun referenceCount(id: Long): Int
}
