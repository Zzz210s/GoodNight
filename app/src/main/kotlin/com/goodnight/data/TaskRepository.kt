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

    fun observeActive(): Flow<List<TaskEntity>> = dao.observeActive()
    fun observeDone(): Flow<List<TaskEntity>> = dao.observeDone()

    /** 标题去首尾空白后入库;空/纯空白/超 [MAX_TITLE] 字拒绝(返回 null),调用方据此提示 */
    suspend fun create(title: String, now: Long): Long? {
        val t = title.trim()
        if (t.isEmpty() || t.length > MAX_TITLE) return null
        return dao.insert(TaskEntity(title = t, createdAt = now, sortOrder = (dao.maxSortOrder() ?: 0L) + 1))
    }

    /** 校验口径同 [create];不合法时静默不改(行不存在也静默) */
    suspend fun rename(id: Long, title: String) {
        val t = title.trim()
        if (t.isEmpty() || t.length > MAX_TITLE) return
        dao.rename(id, t)
    }

    /** 完成时盖 [now];取消完成必须清 doneAt(已完成列表按 doneAt 倒序,旧值会错位) */
    suspend fun setDone(id: Long, done: Boolean, now: Long) =
        dao.setDone(id, done, if (done) now else null)

    /** 删除任务与解绑同事务:段保留(时间账保留),只把 taskId 置空;其它任务引用不受影响 */
    suspend fun deleteTask(id: Long) = db.withTransaction {
        dao.clearTaskRefs(id)
        dao.deleteById(id)
    }

    /**
     * 拖动排序:[list] 为拖动前的未完成列表(升序快照),[targetIndex] 为落点下标。
     * 重排后把 sortOrder 压实为 1..n —— 相对顺序即真相,不留空洞导致下次新建跳号。
     * [targetIndex] 越界夹到首/尾;[id] 不在 [list] 内时不动。
     */
    suspend fun moveTo(id: Long, targetIndex: Int, list: List<TaskEntity>) {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = targetIndex.coerceIn(0, list.lastIndex)
        if (from == to) return
        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        db.withTransaction {
            reordered.forEachIndexed { index, task -> dao.updateSortOrder(task.id, index + 1L) }
        }
    }

    companion object { const val MAX_TITLE = 100 }
}
