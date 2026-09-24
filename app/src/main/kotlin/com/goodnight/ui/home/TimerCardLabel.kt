package com.goodnight.ui.home

import com.goodnight.data.db.ProfileEntity
import com.goodnight.timer.RuntimeSnapshot

/**
 * v2.2 Task 4:计时卡横带显示的时钟 id。
 *
 * 有会话(运行/暂停)时取**运行快照的时钟**(引擎里的真值 —— 任务卡片点 chip 启动的时钟由它承载);
 * 空闲时取选中的 [activeProfileId](将要用哪个时钟)。两者由启动/换时钟时的镜像写入保持一致。
 */
internal fun displayedClockId(snap: RuntimeSnapshot?, activeProfileId: Long): Long =
    snap?.profileId ?: activeProfileId

/**
 * v2.2 Task 4:计时卡横带显示的时钟名 —— [clockId] 在配置表里解析。
 * 解析不到(空库 / 时钟刚被删 / 快照里的时钟已归档不在表内)返回 null,由 [timerCardLabel] 决定回退。
 */
internal fun clockNameFor(profiles: List<ProfileEntity>, clockId: Long): String? =
    profiles.firstOrNull { it.id == clockId }?.name

/**
 * v2.2 Task 4:计时卡顶部横带的文案 —— 「任务 · 时钟」(设计 §5)。
 *
 * 1. 有任务有时钟 -> 两者拼接([pairFormat] 由资源提供,中英各一份);
 * 2. 只有时钟(通用时钟 / 未绑任务)-> 只有时钟名;
 * 3. 都没有 -> null,调用方回退「未绑定任务」文案。
 *
 * **不回退相位文案**:顶部横带右侧的相位徽标([PhaseIndicator])已把相位写进 contentDescription,
 * 左侧 chip 再写一遍会被读屏连读两遍。
 *
 * 纯函数:文案规则可以无资源直测(见 TimerCardLabelTest)。
 */
internal fun timerCardLabel(taskTitle: String?, clockName: String?, pairFormat: String): String? = when {
    clockName == null -> taskTitle
    taskTitle == null -> clockName
    else -> pairFormat.format(taskTitle, clockName)
}
