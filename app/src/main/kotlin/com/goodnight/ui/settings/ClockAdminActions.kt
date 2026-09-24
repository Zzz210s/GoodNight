package com.goodnight.ui.settings

import com.goodnight.data.ProfileRemoval
import com.goodnight.data.db.ProfileEntity
import com.goodnight.diag.DiagLog
import com.goodnight.service.TimerNotifIdle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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
 * 单个时钟的删除:判定 → 归档或真删。判定在**写库这一刻重做**(不信任对话框时刻的计划):
 *
 *  1. 活跃时钟只剩这一个 → 一行不动(返回 null)。归档同样是列表隐藏,守不住门控会让首页开始键失效。
 *  2. **可能马上被引擎使用** → 归档:①快照 profileId 等于它(含 RUNNING 与 PAUSED),或
 *     ②它就是 `activeProfileId` —— 通知栏/首页的「开始」只能启动这个 id(见 [TimerNotifIdle.showIdle]),
 *     对话框停留期间用户点一下就会把引擎指过来。归档让行永远在库里,之后的结算写进来都合法;
 *     即使它没有历史也归档 —— 真删 + 结算在飞 = 悬空引用。
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
    val activeId = graph.settingsRepo.activeProfileId.first()
    val inUse = graph.engine.snapshot.value?.profileId == p.id || activeId == p.id
    if (inUse || graph.profileRepo.hasHistory(p.id)) {
        return if (graph.profileRepo.archive(p.id)) ProfileRemoval.Archived else ProfileRemoval.Deleted
    }
    return graph.profileRepo.removeOrArchive(p.id)
}

/**
 * 删除确认框的执行体(批量)—— 逐个删除,单个失败不阻断其余。
 *
 * 写库整段包在 [NonCancellable] 里:调用方是 Compose 的 rememberCoroutineScope,用户点确认后
 * 立刻返回/退出删除模式就会把这门协程取消 —— 不包的话「删了一个、剩下几个没动」的半成品会留在库里。
 *
 * 空闲常驻通知的归属(复审修复):**写库之后的补投由本函数负责** —— 引擎空闲时列表已经变了,
 * 而在管着通知的只可能是服务(有人点了开始)或本函数。本函数的投递是**幂等**的:只要
 * `snapshot == null`(空闲)就重投一次,不仲裁服务是否挂载 —— 服务挂载着但最后一次空闲投递
 * 已经发生过时,单侧仲裁会让两侧都不投,通知栏永远停在刚下架的时钟名上。
 * `snapshot != null` 才是唯一需要退让的情形:通知栏由服务的前台计时通知占着,空闲通知不许覆盖它。
 */
suspend fun SettingsViewModel.deleteProfiles(targets: List<ProfileEntity>) {
    withContext(NonCancellable) {
        var changed = false
        targets.forEach { p ->
            try {
                if (deleteProfile(p) != null) changed = true
            } catch (e: Exception) {
                // NonCancellable 下调用方取消不会在这里变成 CancellationException,故无需再上抛:
                // 能捕到的都是真实失败(单条失败不阻断其余)
                DiagLog.add("Set", "删除时钟失败 id=${p.id}: ${e.message}")
            }
        }
        if (changed && graph.engine.snapshot.value == null) TimerNotifIdle.showIdle(graph.appContext)
    }
}
