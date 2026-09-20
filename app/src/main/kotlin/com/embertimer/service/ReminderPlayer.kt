package com.embertimer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.embertimer.data.ReminderIntensity

/**
 * 阶段提醒播放(v1.13.0):动作由 [ReminderChannels] 决定(随系统静音/振动/响铃模式自动适配),
 * 应用内强度只决定**振动节奏**与**铃声时长**。
 *
 * - 静音模式:不振动、不响铃(由 ServiceNotifier 发一条会自动消失的通知)
 * - 振动模式:只振动
 * - 响铃模式:振动 + 系统闹铃音(按时长自停)
 */
class ReminderPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null

    fun play(intensity: ReminderIntensity, channels: ReminderChannels) {
        if (channels.vibrate) vibrate(pattern(intensity))
        if (channels.sound) playRingtone(durationMs(intensity))
        com.embertimer.diag.DiagLog.add(
            "Remind",
            "提醒 振=${channels.vibrate} 响=${channels.sound} 通知=${channels.notify} 强度=$intensity",
        )
    }

    private fun vibrate(pattern: LongArray) {
        if (pattern.isEmpty()) return
        val v = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun playRingtone(durMs: Int) {
        if (durMs <= 0) return
        stopRingtone()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val rt = RingtoneManager.getRingtone(context, uri) ?: return
        rt.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = rt
        runCatching { rt.play() }
        handler.postDelayed({ stopRingtone() }, durMs.toLong())
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    companion object {
        /** 铃声时长(响铃模式);同时是振动节奏的时长上限(测试据此断言) */
        fun durationMs(i: ReminderIntensity): Int = when (i) {
            ReminderIntensity.LIGHT -> 2_000
            ReminderIntensity.STANDARD -> 3_000
            ReminderIntensity.STRONG -> 5_000
        }

        /** 振动节奏:轻=单次短振,标准=两次,强=三次长振 */
        fun pattern(i: ReminderIntensity): LongArray = when (i) {
            ReminderIntensity.LIGHT -> longArrayOf(0, 250)
            ReminderIntensity.STANDARD -> longArrayOf(0, 500, 300, 500)
            ReminderIntensity.STRONG -> longArrayOf(0, 700, 300, 700, 300, 700)
        }
    }
}
