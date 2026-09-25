package com.goodnight.service

import com.goodnight.data.ProfileRepository
import com.goodnight.data.TaskRepository
import com.goodnight.timer.RuntimeSnapshot

/**
 * v2.2 Task 7:通知标题的两段名字(任务名 / 时钟名)—— 从 [ServiceNotifier] 拆出以守住 200 行。
 *
 * 缓存按 **(taskId, profileId)** 记 —— 同 v2.1 `cachedTitle` 的理由:到期钳制重发、服务前台化
 * 重发等路径**不带名字参数**,没有兜底会从「工作中 · 写周报 · 番茄」退回「工作中」。
 * 键里新增 profileId 是 v2.2 的口径:换时钟后同一 taskId 的旧时钟名不得粘住。
 */
internal class NotifTitles(
    private val taskRepo: TaskRepository,
    private val profileRepo: ProfileRepository,
) {
    /** 未绑定 / 已删除 / 时钟解析不到 / DB 查询失败时对应字段为 null(标题回退到有的部分) */
    data class Names(val taskId: Long?, val profileId: Long, val task: String?, val clock: String?)

    @Volatile private var last: Names? = null

    /**
     * 解析(带缓存):身份与上次相同直接复用,避免每次重发通知都查库。
     * [refresh] = true 时**强制重查**(任务改名/删除后必须让缓存失效,否则旧名粘住)。
     * 只兜 [Exception]:作用域取消期间的 [kotlinx.coroutines.CancellationException] 必须继续上抛,
     * 否则服务 onDestroy 后调用方还会接着走前台化路径。
     */
    suspend fun resolve(snap: RuntimeSnapshot?, refresh: Boolean = false): Names {
        if (snap == null) return Names(null, -1L, null, null)
        if (!refresh) last?.let { if (it.taskId == snap.taskId && it.profileId == snap.profileId) return it }
        val names = Names(
            taskId = snap.taskId,
            profileId = snap.profileId,
            task = snap.taskId?.let { id -> query { taskRepo.titleById(id) } },
            // 归档时钟仍能解析出名字(历史行必须在,与计时卡/报表同口径)
            clock = query { profileRepo.byId(snap.profileId)?.name },
        )
        last = names
        return names
    }

    /** 只读缓存(不查库):身份不匹配返回 null,由调用方回退到显式传入的片段 */
    fun cached(snap: RuntimeSnapshot?): Names? =
        last?.takeIf { it.taskId == snap?.taskId && it.profileId == snap?.profileId }

    private suspend fun <T> query(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
