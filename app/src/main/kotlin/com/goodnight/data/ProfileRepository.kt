package com.goodnight.data

import com.goodnight.data.db.ProfileDao
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.timer.TimeProvider
import kotlinx.coroutines.flow.Flow

/** v2.2 Task 1:删时钟的两种结局 —— 无任何历史 → 真删;有历史(会话段或每日合计)→ 归档 */
enum class ProfileRemoval { Deleted, Archived }

/**
 * v2.2:时钟的「作用域内唯一」**只由本仓库保证** —— 库里 `profile.name` 的唯一索引已去掉
 * (SQLite 唯一索引把 NULL 视为互不相同,表达不了「通用时钟之间也唯一」),
 * 所有写入口([create]/[rename]/[moveTo]/[TaskRepository.deleteTask])都必须过这里的同名判定。
 */
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
     * v2.2:改归属,不碰任何历史记录。**不校验目标任务是否存在**(前置条件由调用方保证)。
     * @return true = 已改归属或本来就在目标作用域;false = 目标作用域已有同名**活跃**时钟
     * (保持原归属:作用域内唯一优先,否则能造出「同一任务下两个同名时钟」),调用方据此提示
     */
    suspend fun moveTo(id: Long, taskId: Long?): Boolean {
        val row = dao.byId(id) ?: return false
        if (row.taskId == taskId) return true
        if (dao.byNameInScope(row.name, taskId) != null) return false
        dao.moveTo(id, taskId)
        return true
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
     * v2.2:删时钟。有会话段或每日合计引用 → 归档(行保留,历史仍可解析其名);无引用 → 真删。
     * 「无引用才删」是**一条原子 SQL**([ProfileDao.deleteIfUnreferenced]):先计数再删的话,
     * 两步之间落下的段落会让这次删除留下悬空 profileId。归档方向保守(最坏多留一行归档)。
     *
     * rowsAffected = 0 有两种原因:有引用,**或行根本不存在**(未知 id / 并发双删)。归档前必须
     * 区分二者(判存在放在原子删除**之后**:放在之前的话,两个并发调用会双双判「存在」,
     * 后到的那个仍会返回 Archived),否则会对着已不存在的行谎称「已归档,历史保留」。
     */
    suspend fun removeOrArchive(id: Long): ProfileRemoval {
        if (dao.deleteIfUnreferenced(id) > 0) return ProfileRemoval.Deleted
        if (dao.byId(id) == null) return ProfileRemoval.Deleted
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
