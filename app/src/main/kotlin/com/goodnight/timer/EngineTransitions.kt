package com.goodnight.timer

/**
 * TimerEngine 的纯状态迁移辅助(v1.9.12 项目清理拆分):把构造新快照/结算的纯函数
 * 移出引擎类,保证核心状态机文件 <=200 行且职责单一。这些函数只依赖 RuntimeSnapshot
 * 与传入的墙钟时刻,不触碰引擎实例可变状态 —— 由调用方(TimerEngine)在锁内调用。
 */

/** 构造一个迁移后的快照(阶段推进/重开共用)。wall 为当前墙钟,用于 endWall/savedAtWall。 */
internal fun RuntimeSnapshot.toAdvancedSnapshot(
    profileId: Long,
    workMillis: Long,
    restMillis: Long,
    phase: Phase,
    status: EngineStatus,
    cycle: Int,
    e: Long,
    dur: Long,
    countUp: Boolean,
    wall: Long,
): RuntimeSnapshot = RuntimeSnapshot(
    profileId = profileId, workMillis = workMillis, restMillis = restMillis, phase = phase, status = status,
    cycleCount = cycle, startElapsed = e, endElapsed = e + dur, endWall = wall + dur,
    timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
    savedAtWall = wall, savedAtElapsed = e, ckptDate = null, ckptAccum = 0, countUp = countUp,
    // v2.1:任务绑定跨阶段/跨周期保留(用户在会话内未改任务则一直生效)
    taskId = taskId,
    // v1.12.1:进入 WORK 开启新窗口,进入 REST/结束清空(窗口随快照持久化)
    sessionStartWall = if (phase == Phase.WORK) wall else null,
    pauseStartWall = null, pauseGaps = "",
)

/** 工作阶段应落库增量 = 已流逝 - 已 flush 游标(仅 WORK) */
internal fun RuntimeSnapshot.settleMillis(settleAtElapsed: Long): Long =
    if (phase == Phase.WORK) (accruedWork(settleAtElapsed) - ckptAccum).coerceAtLeast(0) else 0L

/** 结算窗口(工作段专属):起点取快照持久化的 sessionStartWall,缺失则退化为传入的当前墙钟;终点恒为当前墙钟 */
internal fun RuntimeSnapshot.workWindow(wall: Long): Pair<Long?, Long?> =
    if (phase == Phase.WORK) (sessionStartWall ?: wall) to wall else null to null

/**
 * v2.1 任务绑定的纯迁移:返回新快照(刷新 savedAt 与 taskId)与应发的段边界事件。
 *
 * 绑定事件只在 **WORK + RUNNING** 时产生 —— 那是唯一“时间账正在按任务切分”的状态:
 * 暂停/休息中切换只改快照(那段时间账仍属旧任务,归属由下一次结算事件自带的 taskId 定);
 * 无边界时返回 null(同值重复选择也走不到这里,调用方已早早退出)。
 */
internal fun RuntimeSnapshot.bindTask(
    taskId: Long?,
    atWall: Long,
    atElapsed: Long,
): Pair<RuntimeSnapshot, EngineEvent.TaskSwitched?> {
    val next = copy(taskId = taskId, savedAtWall = atWall, savedAtElapsed = atElapsed)
    val boundary = if (phase == Phase.WORK && status == EngineStatus.RUNNING) {
        EngineEvent.TaskSwitched(atWall, this.taskId, taskId)
    } else null
    return next to boundary
}
