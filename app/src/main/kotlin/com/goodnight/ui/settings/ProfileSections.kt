package com.goodnight.ui.settings

import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.TaskEntity

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
) {
    val count: Int get() = clocks.size
    val archiveCount: Int get() = archivedIds.size
    val anyArchive: Boolean get() = archivedIds.isNotEmpty()

    /** 无历史、会真删的那些 */
    val removed: List<ProfileEntity> get() = clocks.filter { it.id !in archivedIds }
}

internal fun deletePlan(
    clocks: List<ProfileEntity>,
    sessionMillis: Map<Long, Long>,
    dailyTotals: Map<Long, Long>,
): DeletePlan {
    val history = clocks.associate { it.id to (sessionMillis[it.id] ?: dailyTotals[it.id] ?: 0L) }
    val archived = history.filterValues { it > 0L }.keys
    val millis = archived.sumOf { history.getValue(it) }
    return DeletePlan(clocks = clocks, archivedIds = archived, archiveMinutes = (millis + 30_000L) / 60_000L)
}

/**
 * 选中集 → **真正会执行**的删除计划:有历史的归档,无历史的真删。
 *
 * 「至少保留 1 个时钟」只约束真删:归档只是从列表隐藏(行还在,历史还能解析它的名字),
 * 不该被这条规则挡住(否则「唯一一个有时钟记录的时钟」永远删不掉)。真删全选时留最后一个活跃时钟,
 * 与 [com.goodnight.ui.settings.SettingsViewModel.deleteProfile] 里的门控同一口径。
 */
internal fun planDeletion(
    selected: List<ProfileEntity>,
    activeCount: Int,
    sessionMillis: Map<Long, Long>,
    dailyTotals: Map<Long, Long>,
): DeletePlan {
    val base = deletePlan(selected, sessionMillis, dailyTotals)
    val removal = base.removed
    val allowed = if (removal.size >= activeCount) removal.dropLast(1) else removal
    val keep = allowed.map { it.id }.toSet()
    return base.copy(clocks = base.clocks.filter { it.id in base.archivedIds || it.id in keep })
}
