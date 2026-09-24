package com.goodnight.data

import androidx.room.withTransaction
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.TaskDao
import com.goodnight.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * v2.1 任务仓库:任务读写 + 删除事务。
 *
 * 不加外键(SQLite 无法用 ALTER 给既有表加外键),「删任务不删时间账」由
 * [deleteTask] 在同一事务内先置空引用再删行保证:事务中途失败不会留下悬挂的 taskId。
 */
class TaskRepository(private val db: GoodNightDatabase) {
    private val dao: TaskDao = db.taskDao()
    private val sessionDao = db.focusSessionDao()
    private val profileDao = db.profileDao()

    fun observeActive(): Flow<List<TaskEntity>> = dao.observeActive()
    fun observeDone(): Flow<List<TaskEntity>> = dao.observeDone()

    /** v2.1 Task 7:计时页 chip 的当前绑定(含已完成任务;id 已删/未知时发 null) */
    fun observeById(id: Long): Flow<TaskEntity?> = dao.observeById(id)

    /** v2.1 Task 7:每日详情把段的 taskId 解析成任务名(重命名后卡片即时刷新) */
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    /** 标题去首尾空白后入库;空/纯空白/超 [MAX_TITLE] 字拒绝(返回 null),调用方据此提示 */
    suspend fun create(title: String, now: Long): Long? {
        val t = title.trim()
        if (t.isEmpty() || t.length > MAX_TITLE) return null
        return dao.insert(TaskEntity(title = t, createdAt = now, sortOrder = (dao.maxSortOrder() ?: 0L) + 1))
    }

    /** v2.1 Task 5:按 id 取标题(通知标题拼接用);未知 id 返回 null,已完成任务照常返回 */
    suspend fun titleById(id: Long): String? = dao.titleById(id)

    /**
     * v2.1 Task 5:任务是否存在(落库前校验)。运行态绑定不因删除而清(见 Task 6),
     * 落库前必须用它丢弃已删任务的 id,否则会写出指向已删任务的孤儿引用。
     */
    suspend fun exists(id: Long): Boolean = dao.exists(id)

    /** v2.1 Task 6:当前完成标记(勾选用);任务不存在返回 null */
    suspend fun doneOf(id: Long): Boolean? = dao.doneOf(id)

    /** 校验口径同 [create];不合法时静默不改(行不存在也静默) */
    suspend fun rename(id: Long, title: String) {
        val t = title.trim()
        if (t.isEmpty() || t.length > MAX_TITLE) return
        dao.rename(id, t)
    }

    /** 完成时盖 [now];取消完成必须清 doneAt(已完成列表按 doneAt 倒序,旧值会错位) */
    suspend fun setDone(id: Long, done: Boolean, now: Long) =
        dao.setDone(id, done, if (done) now else null)

    /**
     * 删除任务与解绑同事务:段保留(时间账保留),只把 taskId 置空;
     * v2.2 起该任务的**专属时钟转为通用**(`profile.taskId = NULL`,行与计时设置都保留),
     * 否则任务行消失后时钟会变成指向已删任务的悬挂引用。其它任务引用不受影响。
     */
    suspend fun deleteTask(id: Long) = db.withTransaction {
        dao.clearTaskRefs(id)
        profileDao.clearTaskRefs(id)
        dao.deleteById(id)
    }

    /**
     * 拖动排序:[list] 为拖动前的未完成列表(升序快照),[targetIndex] 为落点下标。
     * 重排后把 sortOrder 压实为 1..n —— 相对顺序即真相,不留空洞导致下次新建跳号。
     *
     * v2.1 Task 6:[list] 允许**过期**(拖动期间新建了任务/某行被勾选完成):事务内重读
     * 活跃行,以 [list] 顺序为准、库内列表外的活跃行按原顺序追加到末尾,再整体压实。
     * 若只信 [list],列表外行会保留旧 sortOrder 而与压实结果重复。
     * [targetIndex] 越界夹到首/尾;[id] 不在 [list] 或已非活跃时不动。
     */
    suspend fun moveTo(id: Long, targetIndex: Int, list: List<TaskEntity>) {
        if (list.none { it.id == id }) return
        db.withTransaction {
            val actual = dao.activeNow()
            val present = actual.map { it.id }.toSet()
            if (id !in present) return@withTransaction
            val ids = (list.map { it.id }.filter { it in present } + actual.map { it.id }).distinct()
            val from = ids.indexOf(id)
            val to = targetIndex.coerceIn(0, ids.lastIndex)
            val reordered = ids.toMutableList().apply { add(to, removeAt(from)) }
            reordered.forEachIndexed { index, taskId -> dao.updateSortOrder(taskId, index + 1L) }
        }
    }

    /** v2.1 Task 6:某任务已记录的合计毫秒(删除确认文案「已记录的 N 分钟」);无段/未知任务为 0 */
    suspend fun recordedMillis(id: Long): Long = sessionDao.totalMillisForTask(id)

    companion object { const val MAX_TITLE = 100 }
}
