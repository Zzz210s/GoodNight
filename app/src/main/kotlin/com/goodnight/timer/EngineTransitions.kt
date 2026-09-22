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
    // v2.1:切点只对本工作段有效 —— 新段重开窗口,旧切点不得残留(残留会把新段段首误归旧任务)
    taskCuts = "",
)

/** 工作阶段应落库增量 = 已流逝 - 已 flush 游标(仅 WORK) */
internal fun RuntimeSnapshot.settleMillis(settleAtElapsed: Long): Long =
    if (phase == Phase.WORK) (accruedWork(settleAtElapsed) - ckptAccum).coerceAtLeast(0) else 0L

/** 结算窗口(工作段专属):起点取快照持久化的 sessionStartWall,缺失则退化为传入的当前墙钟;终点恒为当前墙钟 */
internal fun RuntimeSnapshot.workWindow(wall: Long): Pair<Long?, Long?> =
    if (phase == Phase.WORK) (sessionStartWall ?: wall) to wall else null to null

/**
 * v2.1 任务绑定的纯迁移:返回新快照(刷新 savedAt、taskId,并追加切点)与应发的段边界事件。
 *
 * 边界时刻取**该次连续工作停止的时刻**(一次连续工作只属一个任务):
 * - WORK + RUNNING:atWall(当前墙钟)—— 时间账正在按任务切分;
 * - WORK + PAUSED:pauseStartWall ?: atWall —— 暂停中的切换,暂停前那段工作仍属旧任务,
 *   边界必须回退到暂停起点,否则那段时间账会被整段记到新任务;
 * - 其它状态(休息段、IDLE):只改快照不发事件(不落时间账,无段可切)。
 * 发事件时把同一切点编进快照的 [RuntimeSnapshot.taskCuts](随运行态持久化,重启不丢)。
 * 同值重复选择由调用方早早退出,到不了这里。
 */
internal fun RuntimeSnapshot.bindTask(
    taskId: Long?,
    atWall: Long,
    atElapsed: Long,
): Pair<RuntimeSnapshot, EngineEvent.TaskSwitched?> {
    val boundaryAt = when {
        phase != Phase.WORK -> null
        status == EngineStatus.RUNNING -> atWall
        status == EngineStatus.PAUSED -> pauseStartWall ?: atWall
        else -> null
    }
    val next = copy(
        taskId = taskId, savedAtWall = atWall, savedAtElapsed = atElapsed,
        taskCuts = boundaryAt?.let { encodeTaskCut(taskCuts, it, this.taskId, taskId) } ?: taskCuts,
    )
    val boundary = boundaryAt?.let { EngineEvent.TaskSwitched(it, this.taskId, taskId) }
    return next to boundary
}

/** 追加一项任务切点(编码 "atWall,from,to",空字段 = null);返回新编码,空串 = 无切点 */
internal fun encodeTaskCut(prev: String, atWall: Long, from: Long?, to: Long?): String =
    (if (prev.isEmpty()) "" else "$prev;") + "$atWall,${from ?: ""},${to ?: ""}"
