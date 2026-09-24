package com.goodnight.data

import com.goodnight.data.db.ProfileDao
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.timer.TimeProvider
import kotlinx.coroutines.flow.Flow

/** v2.2 Task 1:删时钟的两种结局 —— 无任何历史 → 真删;有历史(会话段或每日合计)→ 归档 */
enum class ProfileRemoval { Deleted, Archived }

class ProfileRepository(
    private val dao: ProfileDao,
    private val time: TimeProvider,
) {
    /**
     * 全量流(**含归档**):历史解析要用它 —— 归档时钟的段落仍要显示原名字。
     * 列表展示请用 [observeAllActive] 或 [availableFor]。
     */
    val profiles: Flow<List<ProfileEntity>> = dao.observeAll()

    /** 某任务的可用时钟 = 该任务专属 + 全部通用(排除归档);[taskId] 为 null 时只有通用 */
    fun availableFor(taskId: Long?): Flow<List<ProfileEntity>> = dao.observeAvailableFor(taskId)

    /** 管理页列表:通用段 + 任务专属段(排除归档) */
    fun observeAllActive(): Flow<List<ProfileEntity>> = dao.observeAllActive()

    /**
     * v2.2:新建时钟;**作用域内重名返回 null**(旧版返回 -1,调用方一律忽略返回值)。
     * 作用域 = 同一个 [taskId],null 表示「通用时钟」这一层;mode 缺省保持既有语义。
     */
    suspend fun create(
        name: String,
        workMinutes: Int,
        restMinutes: Int,
        mode: Int = ProfileMode.COUNTDOWN,
        taskId: Long? = null,
    ): Long? {
        if (dao.byNameInScope(name, taskId) != null) return null
        return dao.insert(
            ProfileEntity(
                name = name, workMinutes = workMinutes, restMinutes = restMinutes,
                createdAt = time.now(), mode = mode, taskId = taskId,
            )
        )
    }

    /**
     * v2.2:改归属,不碰任何历史记录。
     * 目标作用域已有同名**活跃**时钟时保持原归属(静默不改):作用域内唯一是仓库层不变量,
     * 否则这一操作能造出「同一任务下两个同名时钟」。调用方(管理页)需用 [availableFor] 自查后提示。
     */
    suspend fun moveTo(id: Long, taskId: Long?) {
        val row = dao.byId(id) ?: return
        if (row.taskId == taskId) return
        if (dao.byNameInScope(row.name, taskId) != null) return
        dao.moveTo(id, taskId)
    }

    /** v2.2:改名。作用域内唯一校验;重名(或行不存在)返回 false 且不改任何东西 */
    suspend fun rename(id: Long, name: String): Boolean {
        val row = dao.byId(id) ?: return false
        if (row.name == name) return true
        if (dao.byNameInScope(name, row.taskId) != null) return false
        dao.update(row.copy(name = name))
        return true
    }

    /**
     * v2.2:删时钟。有会话段或每日合计引用 → 归档(行保留,历史仍可解析其名);
     * 无任何引用 → 真删。判定与落库分两步,但 SQL 只读一次引用计数,
     * 且归档是「保守」方向(最坏情况多留一行归档,不会留下悬空 profileId)。
     */
    suspend fun removeOrArchive(id: Long): ProfileRemoval {
        if (dao.referenceCount(id) == 0) {
            dao.deleteById(id)
            return ProfileRemoval.Deleted
        }
        dao.archiveById(id)
        return ProfileRemoval.Archived
    }

    /** mode 必传(Task 7 起对话框带模式选择;禁止缺省,缺省会静默改写既有 profile 的模式) */
    suspend fun updateDurations(id: Long, workMinutes: Int, restMinutes: Int, mode: Int) {
        dao.byId(id)?.let {
            dao.update(it.copy(workMinutes = workMinutes, restMinutes = restMinutes, mode = mode))
        }
    }

    /**
     * v2.2:裸删(不分引用情况),仅保留给「全量重置/导入前清库」等既有路径;
     * 管理页删除请走 [removeOrArchive],否则会留下悬空的 profileId。
     */
    suspend fun delete(entity: ProfileEntity) = dao.delete(entity)
    suspend fun byId(id: Long) = dao.byId(id)
    /** 读取 profile 的计时模式;行不存在时按缺省倒计时处理 */
    suspend fun modeOf(id: Long): Int = dao.modeById(id) ?: ProfileMode.COUNTDOWN
    suspend fun count() = dao.count()
}
