package com.goodnight.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v2.1 Task 1:任务表读写。两个列表查询(未完成/已完成)按各自口径排序。
 *
 * [clearTaskRefs] 把引用某任务的段置为未绑定 —— 删除任务时必须在同一事务里
 * 先/后调用(见 TaskRepository),SQLite 无法给既有表加外键,时间账由应用层保证保留。
 */
@Dao
interface TaskDao {
    /** 未完成任务:手工顺序升序 */
    @Query("SELECT * FROM task WHERE done = 0 ORDER BY sortOrder ASC, id ASC")
    fun observeActive(): Flow<List<TaskEntity>>

    /**
     * v2.1 Task 6:[TaskRepository.moveTo] 在事务内重读的活跃列表 —— 调用方快照可能过期
     * (拖动期间新建/取消完成),只有库内实况能保证压实后 sortOrder 不重复。
     */
    @Query("SELECT * FROM task WHERE done = 0 ORDER BY sortOrder ASC, id ASC")
    suspend fun activeNow(): List<TaskEntity>

    /** 已完成任务:最近完成在前 */
    @Query("SELECT * FROM task WHERE done = 1 ORDER BY doneAt DESC, id DESC")
    fun observeDone(): Flow<List<TaskEntity>>

    @Insert suspend fun insert(t: TaskEntity): Long

    @Query("UPDATE task SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)

    @Query("UPDATE task SET done = :done, doneAt = :doneAt WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, doneAt: Long?)

    @Query("DELETE FROM task WHERE id = :id") suspend fun deleteById(id: Long)

    /** 删除任务前把其引用置空(段本身保留,报表归「未绑定」) */
    @Query("UPDATE focus_session SET taskId = NULL WHERE taskId = :taskId")
    suspend fun clearTaskRefs(taskId: Long)

    /**
     * v2.1 Task 5:按 id 取标题(通知标题拼接用)。不过滤 done —— 段可能绑定一个后来
     * 被标记完成的任务,通知仍要显示它的名字。未知 id 返回 null。
     */
    @Query("SELECT title FROM task WHERE id = :id") suspend fun titleById(id: Long): String?

    /** v2.1 Task 5:落库前校验任务仍在 —— 运行态可能还带着已删任务的 id */
    @Query("SELECT EXISTS(SELECT 1 FROM task WHERE id = :id)") suspend fun exists(id: Long): Boolean

    /** v2.1 Task 6:当前完成标记(勾选完成/取消完成用);行不存在返回 null */
    @Query("SELECT done FROM task WHERE id = :id") suspend fun doneOf(id: Long): Boolean?

    /**
     * v2.1 Task 7:计时页 chip 按 id 观察当前绑定 —— 不过滤 done(完成不解除运行态绑定,
     * chip 仍要显示它的名字);行已被删时发 null。
     */
    @Query("SELECT * FROM task WHERE id = :id") fun observeById(id: Long): Flow<TaskEntity?>

    /** v2.1 Task 7:每日详情解析任务名的标题表(重命名后卡片即时刷新);小表,全量读 */
    @Query("SELECT * FROM task") fun observeAll(): Flow<List<TaskEntity>>

    /** 末尾排序位;空表返回 null,调用方按 0 起算 */
    @Query("SELECT MAX(sortOrder) FROM task") suspend fun maxSortOrder(): Long?

    @Query("UPDATE task SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Long)
}
