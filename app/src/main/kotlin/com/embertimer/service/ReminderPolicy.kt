package com.embertimer.service

import android.media.AudioManager

/**
 * 提醒通道决策(v1.13.0):按**系统铃声模式**自动适配,不再只按应用内强度决定。
 *
 * | 系统模式 | 通知 | 振动 | 铃声 |
 * |---|---|---|---|
 * | 静音 RINGER_MODE_SILENT | 有(6 秒后自动消失) | 无 | 无 |
 * | 振动 RINGER_MODE_VIBRATE | 无 | 有(节奏由强度决定) | 无 |
 * | 响铃 RINGER_MODE_NORMAL | 无 | 有(节奏由强度决定) | 有(时长由强度决定) |
 *
 * 为什么这样分:振动/响铃模式下用户已经被振动或铃声提醒到了,再叠一条通知纯属噪音;
 * 静音模式没有任何声/振反馈,所以补一条**会自动消失**的通知(不留在通知栏)。
 */
data class ReminderChannels(
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    /** 通知自动消失延时(ms);不通知时为 0 */
    val notifyTimeoutMs: Long,
)

/** 静音模式下通知停留时长:够看清一眼,又不留在通知栏 */
const val REMINDER_NOTIFY_TIMEOUT_MS: Long = 6_000L

/** 系统铃声模式 -> 提醒通道(纯函数,便于单测) */
fun reminderChannelsFor(ringerMode: Int): ReminderChannels = when (ringerMode) {
    AudioManager.RINGER_MODE_SILENT -> ReminderChannels(
        notify = true, vibrate = false, sound = false, notifyTimeoutMs = REMINDER_NOTIFY_TIMEOUT_MS,
    )
    AudioManager.RINGER_MODE_VIBRATE -> ReminderChannels(
        notify = false, vibrate = true, sound = false, notifyTimeoutMs = 0L,
    )
    else -> ReminderChannels(
        notify = false, vibrate = true, sound = true, notifyTimeoutMs = 0L,
    )
}

/** 模式名(诊断日志/设置页提示用) */
fun ringerModeName(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> "静音"
    AudioManager.RINGER_MODE_VIBRATE -> "振动"
    AudioManager.RINGER_MODE_NORMAL -> "响铃"
    else -> "未知($mode)"
}
