package com.goodnight.ui.tasks

import com.goodnight.data.db.ProfileEntity
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.RuntimeSnapshot

/**
 * v2.2 Task 4:计时中换时钟的待确认请求。
 * [clock] = 将要开始的时钟(带着它的工作/休息/模式),[taskId] = 新会话沿用的任务绑定。
 */
data class PendingClockSwitch(val clock: ProfileEntity, val taskId: Long?)

/** 点一个时钟时的三态,判定见 [clockPickAction] */
internal enum class ClockPickAction {
    /** 点的就是正在跑的那一个(时钟 id **与**归属任务都相同):什么都不做 */
    SAME_CLOCK,

    /** 已有会话(运行/暂停)-> 先弹确认;**未确认前不发出任何命令** */
    ASK_CONFIRM,

    /** 空闲:直接生效(任务卡片上 = 起画;计时卡上 = 只改选) */
    FREE,
}

/**
 * 设计 §4:计时中**不允许静默换时钟**(45/15 与 25/5 不能混在同一段里)。
 * 暂停也算「有会话」—— 段还没结算,换时钟同样要先终止它。
 *
 * [targetTaskId] = 这次点击**期望新会话绑定的任务**(计时卡上 = 沿用当前绑定;任务卡片上 = 该卡片
 * 的任务)。同一个时钟**换任务**也算变化:在任务 A 的卡片上点正在任务 B 名下跑的通用时钟,
 * 时钟 id 相同但归属不同,旧判定会静默 no-op —— 用户看不到任何反馈。现在这种情况走确认流程。
 */
internal fun clockPickAction(
    snap: RuntimeSnapshot?,
    clockId: Long,
    targetTaskId: Long?,
): ClockPickAction = when {
    snap == null || snap.status == EngineStatus.IDLE -> ClockPickAction.FREE
    snap.profileId == clockId && snap.taskId == targetTaskId -> ClockPickAction.SAME_CLOCK
    else -> ClockPickAction.ASK_CONFIRM
}

/** 选择器里的一个时钟分组:同一作用域(同一个 [taskId])的时钟放一组;null = 通用时钟,单独一组 */
internal data class ClockPickerGroup(val taskId: Long?, val clocks: List<ProfileEntity>)

/**
 * 按所属任务分组:专属组在前、通用时钟单独一组在后;空组不出现在选择器里。
 * 组标题由 UI 解析(专属组用该任务名,通用组用资源文案),故这里只给 taskId。
 */
internal fun clockPickerGroups(clocks: TaskClocks): List<ClockPickerGroup> = buildList {
    if (clocks.specific.isNotEmpty()) add(ClockPickerGroup(clocks.specific.first().taskId, clocks.specific))
    if (clocks.generic.isNotEmpty()) add(ClockPickerGroup(null, clocks.generic))
}
