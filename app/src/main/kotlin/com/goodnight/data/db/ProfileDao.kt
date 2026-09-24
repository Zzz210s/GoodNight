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
    @Query("SELECT mode FROM profile WHERE id = :id") suspend fun modeById(id: Long): Int?
    @Query("SELECT COUNT(*) FROM profile") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<ProfileEntity>)
    @Query("SELECT * FROM profile ORDER BY createdAt, id") suspend fun getAll(): List<ProfileEntity>

    /** 库内最大时钟 id(空库为 null);导入前记下它即可分辨本批新插入的行,见 [ProfileScopeDedupe] */
    @Query("SELECT MAX(id) FROM profile") suspend fun maxId(): Long?

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

    /** 删除任务时把专属时钟转通用(行保留:时间账与时钟设置都不丢);名字由调用方去重后传入 */
    @Query("SELECT * FROM profile WHERE taskId = :taskId ORDER BY createdAt, id")
    suspend fun getByTask(taskId: Long): List<ProfileEntity>

    /** 一条语句完成「解绑 + 改名」,避免中间态出现两个同名通用时钟 */
    @Query("UPDATE profile SET taskId = NULL, name = :name WHERE id = :id")
    suspend fun freeToGeneric(id: Long, name: String)

    /**
     * 归档(行保留、列表隐藏);判定见 [deleteIfUnreferenced]。
     * @return rowsAffected:0 = 行不存在(调用方不能对空行谎称「已归档、历史保留」)
     */
    @Query("UPDATE profile SET archived = 1 WHERE id = :id")
    suspend fun archiveById(id: Long): Int

    /**
     * v2.2 Task 5(复审修复 W2):「改归属 + 改名」一条带条件的语句 —— 目标作用域已有同名
     * **活跃**时钟时一行不动(返回 0)。NOT EXISTS 里的 `p2.id != :id` 排除本行自身:同作用域
     * 改名时自己总会命中查询,不排除就会恒返回 0。
     * @return 1 = 已写入(值可能与原来相同);0 = 被重名挡住,或行不存在
     */
    @Query(
        "UPDATE profile SET taskId = :taskId, name = :name WHERE id = :id AND NOT EXISTS (" +
            "SELECT 1 FROM profile p2 WHERE p2.name = :name AND p2.taskId IS :taskId " +
            "AND p2.archived = 0 AND p2.id != :id)"
    )
    suspend fun moveAndRenameIfFree(id: Long, taskId: Long?, name: String): Int

    /**
     * v2.2 Task 5:活跃(未归档)时钟数 —— 「至少保留 1 个」只算界面上看得见的行。
     * 归档行仍在库里(历史要解析它的名字),但不能替活跃时钟挡删除,否则会出现
     * 「列表只剩一个时钟却删不掉」的怪现象。
     */
    @Query("SELECT COUNT(*) FROM profile WHERE archived = 0") suspend fun countActive(): Int

    /** v2.2 Task 5:该时钟是否有历史引用(会话段或每日合计)—— 口径与 [deleteIfUnreferenced] 一致 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM focus_session WHERE profileId = :id) " +
            "OR EXISTS(SELECT 1 FROM daily_total WHERE profileId = :id)"
    )
    suspend fun hasHistory(id: Long): Boolean

    /** v2.2 Task 5:每个时钟已记录的**会话**毫秒(删除预告文案用);无段的时钟不出现 */
    @Query("SELECT profileId, SUM(endAt - startAt) AS total FROM focus_session GROUP BY profileId")
    suspend fun sessionMinutesByProfile(): List<ProfileTotal>

    /**
     * v2.2 Task 2:导入事务末尾归一化 —— 时钟归属的任务不在库中时归为通用。
     * 与 [TaskDao.clearDanglingTaskRefs] 同口径:同 id 在另一台设备可能是另一个任务,
     * 悬挂引用会在之后导入那台设备的备份时被静默重绑。
     */
    @Query(
        "UPDATE profile SET taskId = NULL WHERE taskId IS NOT NULL " +
            "AND taskId NOT IN (SELECT id FROM task)"
    )
    suspend fun clearDanglingTaskRefs()

    /**
     * 原子的「无引用才删」:同一条语句里检查会话段与每日合计,取 rowsAffected(>0 才是真删)。
     * 拆成「先计数再删」会在两步之间漏进新段落,留下悬空 profileId。
     */
    @Query(
        "DELETE FROM profile WHERE id = :id " +
            "AND NOT EXISTS (SELECT 1 FROM focus_session WHERE profileId = :id) " +
            "AND NOT EXISTS (SELECT 1 FROM daily_total WHERE profileId = :id)"
    )
    suspend fun deleteIfUnreferenced(id: Long): Int
}
