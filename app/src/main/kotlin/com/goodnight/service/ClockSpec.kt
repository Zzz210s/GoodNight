package com.goodnight.service

import com.goodnight.timer.RuntimeSnapshot

/**
 * 运行态时钟基线(v1.9.4):基于 SystemClock.elapsedRealtime() 的绝对基 —— Chronometer 只认这条时间轴。
 * v2.1 从 TimerNotifications.kt 拆出(该文件逼近 200 行上限),行为逐字节不变。
 */
internal data class ClockSpec(val base: Long, val countDown: Boolean)

internal fun buildClockSpec(snap: RuntimeSnapshot): ClockSpec =
    if (snap.countUp) ClockSpec(snap.startElapsed + snap.timeSpentPaused, false)
    else ClockSpec(snap.endElapsed, true)
