package com.goodnight.ui.settings

import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.TaskEntity
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.RuntimeSnapshot

/**
 * v2.2 Task 5:时钟管理页的「通用 / 任务专属」两段 + 删除预告的纯计算。
 *
 * 单独成文件有两个原因:①[ProfilesScreen] 与对话框宿主要同时用这些类型,放屏幕文件里会互相
 * 反向依赖;②这些判定全是**纯函数**(无 DAO、无资源),可以脱离 Compose/Room 直接单测
 * (见 `ProfileManageTest`)。
 */

/**
 * 一段时钟。[taskId] 为 null = 「通用」段;[title] 是该任务的标题(通用段为空串 ——
 * 段头文案由界面层出资源,本文件不碰资源,便于无资源环境单测)。
 */
data class ClockSection(
    val taskId: Long?,
    val title: String,
    val clocks: List<ProfileEntity>,
)
/** 归属选择器的一项:[taskId] 为 null = 通用层;标签由调用方出资源 */
internal data class ClockScope(val taskId: Long?, val label: String)

/**
 * 分组:**通用段恒在首**,然后是「有专属时钟的任务」按 [tasks] 顺序成段(无专属时钟的任务不出现)。
 * 归档时钟是历史解析用的行,列表上一律不出现(两段都不进)。
 *
 * 归属任务已不存在的时钟(理论上不可能:删任务先转通用、导入会归一化悬挂引用)归入通用段 ——
 * 宁可展示得不准,也不能把用户的时钟从列表里弄丢。
 */
internal fun clockSections(clocks: List<ProfileEntity>, tasks: List<TaskEntity>): List<ClockSection> {
    val active = clocks.filter { !it.archived }
    val known = tasks.map { it.id }.toSet()
    val generic = active.filter { it.taskId == null || it.taskId !in known }
    val groups = tasks.mapNotNull { t ->
        active.filter { it.taskId == t.id }.takeIf { it.isNotEmpty() }?.let { ClockSection(t.id, t.title, it) }
    }
    return listOf(ClockSection(taskId = null, title = "", clocks = generic)) + groups
}

/**
 * 删除预告:[clocks] 里哪些会**归档**(有历史:会话段或每日合计)、一共保留多少**分钟**。
 * 归档行不删、账不动,所以文案必须说清「保留多少分钟」而不是「不可撤销」。
 *
 * 分钟数**会话段优先,没有段才用每日合计**:两者记的是同一笔账,相加会重复计数。
 * 折算分钟时就近取整((毫秒 + 30 秒) / 60 秒),与删任务的「已记录的 N 分钟」同口径。
 */
internal data class DeletePlan(
    val clocks: List<ProfileEntity>,
    val archivedIds: Set<Long> = emptySet(),
    /** 归档保留的**分钟**数(库里的毫秒会在这里折成分钟:文案直接用它,不再除) */
    val archiveMinutes: Long = 0L,
    /** 被「至少保留 1 个活跃时钟」挡下、这次不会变动的行(按钮计数与确认框文案的差额来源) */
    val keptIds: Set<Long> = emptySet(),
) {
    val count: Int get() = clocks.size
    val archiveCount: Int get() = archivedIds.size
    val anyArchive: Boolean get() = archivedIds.isNotEmpty()

    /** 无历史、会真删的那些 */
    val removed: List<ProfileEntity> get() = clocks.filter { it.id !in archivedIds }
}

/** 每个时钟已记录多少毫秒:**会话段优先,没有段才用每日合计**(两者记的是同一笔账,相加会重复计数) */
private fun historyOf(
    clocks: List<ProfileEntity>,
    sessionMillis: Map<Long, Long>,
    dailyTotals: Map<Long, Long>,
): Map<Long, Long> = clocks.associate { it.id to (sessionMillis[it.id] ?: dailyTotals[it.id] ?: 0L) }

/**
 * 按「哪些有历史」出计划。[keepId] 非 null 时它**必须留在活跃列表**(既不归档也不真删),
 * 归档分钟数按剩下的归档行重算 —— 否则文案会把被扣掉的那条的分钟数也算进去。
 */
private fun planOf(clocks: List<ProfileEntity>, history: Map<Long, Long>, keepId: Long?): DeletePlan {
    val kept = setOfNotNull(keepId)
    val archived = history.filter { (id, millis) -> millis > 0L && id !in kept }.keys
    val planned = clocks.filter { it.id in archived || (it.id !in kept && history.getValue(it.id) == 0L) }
    val millis = archived.sumOf { history.getValue(it) }
    return DeletePlan(planned, archived, (millis + 30_000L) / 60_000L, kept)
}

internal fun deletePlan(
    clocks: List<ProfileEntity>,
    sessionMillis: Map<Long, Long>,
    dailyTotals: Map<Long, Long>,
): DeletePlan = planOf(clocks, historyOf(clocks, sessionMillis, dailyTotals), keepId = null)

/**
 * 选中集 → **真正会执行**的删除计划:有历史的归档,无历史的真删。
 *
 * 「至少保留 1 个**活跃**时钟」约束**两种结局**(复审修复):归档只是从列表隐藏,把唯一的
 * 活跃时钟归档同样会让活跃列表清零(首页开始键失效)。判据是「选中集覆盖了全部活跃时钟」——
 * 此时留最后一个(与 [com.goodnight.ui.settings.SettingsViewModel.deleteProfile] 同一口径);
 * 还有没选中的活跃时钟时不必保留。
 */
internal fun planDeletion(
    selected: List<ProfileEntity>,
    activeCount: Int,
    sessionMillis: Map<Long, Long>,
    dailyTotals: Map<Long, Long>,
): DeletePlan = planOf(
    selected,
    historyOf(selected, sessionMillis, dailyTotals),
    keepId = if (selected.size >= activeCount) selected.lastOrNull()?.id else null,
)

/**
 * v2.2 Task 5(复审修复):删除后要不要发 stop —— 被删的正是**引擎当前认的**时钟。
 * RUNNING 的卡片不可勾选,所以实际只会碰到 PAUSED;判据仍写上两者,免得「只看 RUNNING」的
 * 旧口径再漏掉暂停态(暂停中删行后快照仍指着它,之后 resume/终止结算会写出悬空 profileId)。
 */
internal fun shouldStopAfterDelete(snap: RuntimeSnapshot?, deletedId: Long): Boolean =
    snap?.profileId == deletedId &&
        (snap.status == EngineStatus.RUNNING || snap.status == EngineStatus.PAUSED)
