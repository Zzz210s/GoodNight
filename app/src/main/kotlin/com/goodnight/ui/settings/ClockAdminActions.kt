package com.goodnight.ui.settings

import com.goodnight.data.db.ProfileEntity
import com.goodnight.diag.DiagLog
import com.goodnight.service.TimerNotifIdle
import com.goodnight.service.stopAndSettle
import com.goodnight.timer.EnginePolicy
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.PolicyAction
import kotlinx.coroutines.CancellationException

/**
 * 时钟管理页的写操作(从 [SettingsViewModel] 拆出,保持单文件 <=200 行):
 * 编辑提交(改归属 + 改名)、按历史分流删除、批量执行删除确认框。
 *
 * 用扩展函数而不是搬进独立类:三者都要 [SettingsViewModel.graph],调用点(`vm.deleteProfiles(..)` /
 * `vm.commitScopeAndName(..)`)保持原样。
 */

/**
 * v2.2 Task 5(复审修复 W2):编辑提交 = 改归属 + 改名,由仓库层按**最终状态**一次写入。
 * 目标作用域已有同名活跃时钟时返回 false 且一行不动 —— 不再有「已换归属、名字未改」的中间态。
 * @return false = 目标作用域的最终名字已被占用(调用方提示「该归属下已有同名时钟」)
 */
suspend fun SettingsViewModel.commitScopeAndName(p: ProfileEntity, taskId: Long?, name: String): Boolean =
    graph.profileRepo.moveAndRename(p.id, taskId, name)

/**
 * 单个时钟的删除(批量路径请走 [deleteProfiles],它负责删除前的停机结算)。
 *
 * v2.2 Task 5(设计 §4 拍板 2):有历史(会话段或每日合计)→ **归档** —— 行保留、列表隐藏、
 * 历史账一行不删;无历史 → 真删。两者都受「至少保留 1 个**活跃**时钟」门控(指针 1/3:归档行
 * 不算数,也不调 [com.goodnight.data.DailyTotalRepository.deleteProfileData] —— 那是清账路径)。
 * 复审修复:归档同样受该门控约束(归档后列表为空 → 首页开始键失效)。
 * @return true = 引擎正暂停在这个时钟上([PolicyAction.RESET_THEN_DELETE]):调用方还需发一条 stop
 */
suspend fun SettingsViewModel.deleteProfile(p: ProfileEntity): Boolean {
    val snap = graph.engine.snapshot.value
    // 运行中的时钟不能被拿掉(既有语义):归档同样要守,否则计时卡解析不出正在跑的时钟
    if (snap?.status == EngineStatus.RUNNING && snap.profileId == p.id) return false
    // 「至少保留 1 个**活跃**时钟」也约束归档 —— 归档 = 列表隐藏,把唯一的活跃时钟归档同样会让
    // 活跃列表清零(首页开始键失效)。判据与 [planDeletion] 同一口径。
    if (graph.profileRepo.countActive() <= 1) return false
    if (graph.profileRepo.hasHistory(p.id)) {
        graph.profileRepo.removeOrArchive(p.id)
        return false
    }
    return when (EnginePolicy.onDelete(snap, p.id, graph.profileRepo.countActive())) {
        PolicyAction.RESET_THEN_DELETE -> {
            graph.profileRepo.removeOrArchive(p.id)
            true // 调用方发 stop(顺序:reset 引擎结算后清快照;DB 行已删)
        }
        PolicyAction.DELETE -> {
            graph.profileRepo.removeOrArchive(p.id)
            false
        }
        else -> false
    }
}

/**
 * 删除确认框的执行体(批量)—— 逐个删除,单个失败不阻断其余。
 *
 * 复审修复 W1:被删时钟正被引擎**暂停**认着时,**先停机结算**(协调器的 [com.goodnight.service.stopAndSettle])
 * 再按结算后的 hasHistory 决定归档/真删 —— 否则 reset 的 Reset 事件会把暂停段写到已删的 profileId 上
 * ([com.goodnight.service.EventApplier] 只校验 taskId 不校验 profile)。停机走进程级协调器;
 * 引擎变空后前台服务自己按快照观察收尾(脱离前台 + 空闲通知 + stopSelf)。
 *
 * 复审修复 W4:归档/删除后若引擎已空闲,重投一次空闲常驻通知 —— 通知栏可能还挂着刚下架的时钟
 * (连「启动」按钮一起)。引擎活跃时(暂停中删别的时钟)不投:那条是计时态通知,不能被空闲态覆盖。
 */
suspend fun SettingsViewModel.deleteProfiles(targets: List<ProfileEntity>) {
    if (targets.any { needsSettleBeforeDelete(graph.engine.snapshot.value, it.id) }) {
        graph.coordinator.stopAndSettle()
    }
    var changed = false
    targets.forEach { p ->
        try {
            deleteProfile(p)
            val row = graph.profileRepo.byId(p.id)
            if (row == null || row.archived) changed = true
        } catch (e: CancellationException) {
            throw e // 作用域取消必须上抛:不能被当成「这一条删失败」吞掉
        } catch (e: Exception) {
            DiagLog.add("Set", "删除时钟失败 id=${p.id}: ${e.message}")
        }
    }
    if (changed && graph.engine.snapshot.value == null) TimerNotifIdle.showIdle(graph.appContext)
}
