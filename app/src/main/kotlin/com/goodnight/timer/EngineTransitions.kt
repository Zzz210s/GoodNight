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
    // v1.12.1:进入 WORK 开启新窗口,进入 REST/结束清空(窗口随快照持久化)
    sessionStartWall = if (phase == Phase.WORK) wall else null,
    pauseStartWall = null, pauseGaps = "",
)

/** 工作阶段应落库增量 = 已流逝 - 已 flush 游标(仅 WORK) */
internal fun RuntimeSnapshot.settleMillis(settleAtElapsed: Long): Long =
    if (phase == Phase.WORK) (accruedWork(settleAtElapsed) - ckptAccum).coerceAtLeast(0) else 0L
