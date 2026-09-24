package com.goodnight.ui.settings

import com.goodnight.data.ProfileRemoval
import com.goodnight.data.db.ProfileEntity
import com.goodnight.diag.DiagLog
import com.goodnight.service.TimerNotifIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 时钟管理页的写操作(从 [SettingsViewModel] 拆出,保持单文件 <=200 行):
 * 编辑提交(改归属 + 改名)、按历史分流删除、批量执行删除确认框。
 *
 * 用扩展函数而不是搬进独立类:三者都要 [SettingsViewModel.graph],调用点(`vm.deleteProfiles(..)` /
 * `vm.commitScopeAndName(..)`)保持原样。
 */

/**
 * v2.2 Task 5(复审修复 W2):编辑提交 = 改归属 + 改名,由仓库层按**最终状态**一条带条件的
 * UPDATE 写入。目标作用域已有同名活跃时钟时返回 false 且一行不动 —— 不再有「已换归属、名字未改」
 * 的中间态。
 * @return false = 目标作用域的最终名字已被占用(调用方提示「该归属下已有同名时钟」)
 */
suspend fun SettingsViewModel.commitScopeAndName(p: ProfileEntity, taskId: Long?, name: String): Boolean =
    graph.profileRepo.moveAndRename(p.id, taskId, name)

/**
 * 单个时钟的删除:判定 → 归档或真删。判据顺序(与 [planDeletion] 同一口径):
 *
 *  1. 活跃时钟只剩这一个 → 一行不动(返回 null)。归档同样是列表隐藏,守不住门控会让首页开始键失效。
 *  2. **正被引擎选中**(快照 profileId,含 RUNNING 与 PAUSED)→ 归档:行永远在库里,计时继续跑,
 *     之后的结算写进来都合法;即使它没有历史也归档 —— 真删 + 结算在飞 = 悬空引用。
 *  3. 有历史(会话段或每日合计)→ 归档(既有语义)。
 *  4. 其余 → 真删(仍走原子的 [com.goodnight.data.ProfileRepository.removeOrArchive])。
 *
 * **本路径不停机**:正在用的时钟归档后计时继续(行没删,只是从列表隐藏),所以不需要 stop、
 * 不需要等结算、不需要超时兜底 —— 「引擎已停但一个时钟都没删」的半成品不再可能。停机只由用户
 * 主动停止(通知/首页)触发。
 *
 * @return null = 被「至少保留 1 个活跃时钟」挡下、一行未动;否则是这次的实际结局
 */
suspend fun SettingsViewModel.deleteProfile(p: ProfileEntity): ProfileRemoval? {
    if (graph.profileRepo.countActive() <= 1) return null
    val inUse = graph.engine.snapshot.value?.profileId == p.id
    if (inUse || graph.profileRepo.hasHistory(p.id)) {
        graph.profileRepo.archive(p.id)
        return ProfileRemoval.Archived
    }
    return graph.profileRepo.removeOrArchive(p.id)
}

/**
 * 删除确认框的执行体(批量)—— 逐个删除,单个失败不阻断其余。
 *
 * 写库整段包在 [NonCancellable] 里:调用方是 Compose 的 rememberCoroutineScope,用户点确认后
 * 立刻返回/退出删除模式就会把这门协程取消 —— 不包的话「删了一个、剩下几个没动」的半成品会留在库里。
 *
 * 空闲常驻通知的归属(复审修复):**写库之后的补投由本函数负责**(引擎空闲时列表已经变了,
 * 而服务早已拆走,没人会再投);服务侧 [com.goodnight.service.TimerService] 的 `tearDownToIdle`
 * 只负责「计时 → 空闲」那一瞬间的投递。两侧用 `serviceAttached` 单侧仲裁,同一时刻只有一侧投,
 * 不再出现两次投递互相覆盖。
 */
suspend fun SettingsViewModel.deleteProfiles(targets: List<ProfileEntity>) {
    withContext(NonCancellable) {
        var changed = false
        targets.forEach { p ->
            try {
                if (deleteProfile(p) != null) changed = true
            } catch (e: CancellationException) {
                throw e // 作用域取消必须上抛:不能被当成「这一条删失败」吞掉
            } catch (e: Exception) {
                DiagLog.add("Set", "删除时钟失败 id=${p.id}: ${e.message}")
            }
        }
        // 服务还挂着时由服务的收尾负责投递(它马上就会投);服务已拆才由这里补投
        if (changed && graph.engine.snapshot.value == null && !graph.coordinator.serviceAttached) {
            TimerNotifIdle.showIdle(graph.appContext)
        }
    }
}
