package com.embertimer.service

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.embertimer.data.ReminderIntensity

/**
 * 阶段提醒播放(v1.12.3 调整):**只振动,不响铃**。
 *
 * 到点提示 = 振动 + 通知(通知渠道本身已 `setSound(null, null)`),不再播放系统闹铃/通知音,
 * 避免在图书馆、课堂、会议等场合外放。强度设置仍决定振动:
 * - LIGHT    : 不振动(完全安静,仅通知)
 * - STANDARD : 两次短振
 * - STRONG   : 三次长振
 */
class ReminderPlayer(private val context: Context) {
    fun play(intensity: ReminderIntensity) {
        val durMs = durationMs(intensity)
        if (durMs <= 0) {
            com.embertimer.diag.DiagLog.add("Remind", "提醒(静默)强度=$intensity")
            return
        }
        vibrate(pattern(intensity))
        com.embertimer.diag.DiagLog.add("Remind", "提醒(仅振动)强度=$intensity 上限=${durMs}ms")
    }

    private fun vibrate(pattern: LongArray) {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    companion object {
        fun durationMs(i: ReminderIntensity): Int = when (i) {
            ReminderIntensity.LIGHT -> 0
            ReminderIntensity.STANDARD -> 3_000
            ReminderIntensity.STRONG -> 5_000
        }

        fun pattern(i: ReminderIntensity): LongArray = when (i) {
            ReminderIntensity.LIGHT -> longArrayOf()
            ReminderIntensity.STANDARD -> longArrayOf(0, 500, 300, 500)
            ReminderIntensity.STRONG -> longArrayOf(0, 700, 300, 700, 300, 700)
        }
    }
}
