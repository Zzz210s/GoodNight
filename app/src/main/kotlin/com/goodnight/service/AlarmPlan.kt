package com.goodnight.service

import com.goodnight.timer.EngineStatus
import com.goodnight.timer.RuntimeSnapshot

/**
 * v1.12.0 到期闹钟计划(纯函数,可 JVM 测试)。
 *
 * 设计背景:用户不接受 `setAlarmClock`(会在状态栏显示闹钟图标,改动 UI/UX),
 * 因此到点可靠性只能靠**同一种精确闹钟的多重冗余 + 送达后进程内直达推进**:
 * ① primary:阶段到期时刻;
 * ② safety :到期后 [SAFETY_MS] —— 若 primary 被 OEM/Doze 吞掉或被系统清理,仍有第二次机会。
 * 两次都送到同一个接收器,接收器幂等(仅"运行中且已到期"才推进)。
 *
 * 正计时/暂停/空闲都不需要到期闹钟(正计时永不到期)。
 */
data class AlarmPlan(val primaryElapsed: Long, val safetyElapsed: Long)

/** 冗余闹钟的安全网延迟:主闹钟被吞后的第二次机会(够短以免长时间负秒,够长以免打扰) */
const val SAFETY_MS: Long = 45_000L

/** 已过期时仍武装一个"立刻"的扰动闹钟,让被冻结的进程尽快醒来推进 */
private const val IMMEDIATE_MS: Long = 1_000L

fun alarmPlanFor(snap: RuntimeSnapshot?, nowElapsed: Long): AlarmPlan? {
    if (snap == null) return null
    if (snap.status != EngineStatus.RUNNING) return null
    if (snap.countUp) return null
    val primary = if (snap.endElapsed > nowElapsed) snap.endElapsed else nowElapsed + IMMEDIATE_MS
    return AlarmPlan(primaryElapsed = primary, safetyElapsed = primary + SAFETY_MS)
}
