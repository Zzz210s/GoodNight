package com.embertimer.service

import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.embertimer.data.ReminderIntensity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.13.0:提醒动作由 [ReminderChannels] 决定(随系统静音/振动/响铃模式)。
 * 这里锁死"静音不振动、振动/响铃要振动"这两条最容易被改坏的边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderPlayerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val vibrator: Vibrator get() = ctx.getSystemService(Vibrator::class.java)
    private val player = ReminderPlayer(ctx)

    @Test
    fun vibrateModeVibrates() {
        player.play(ReminderIntensity.STANDARD, reminderChannelsFor(android.media.AudioManager.RINGER_MODE_VIBRATE))
        assertTrue("振动模式应振动", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun ringModeVibrates() {
        player.play(ReminderIntensity.STRONG, reminderChannelsFor(android.media.AudioManager.RINGER_MODE_NORMAL))
        assertTrue("响铃模式应振动", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun silentModeDoesNotVibrate() {
        player.play(ReminderIntensity.STRONG, reminderChannelsFor(android.media.AudioManager.RINGER_MODE_SILENT))
        assertFalse("静音模式不应振动(只发自动消失的通知)", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun lightIntensityStillVibratesInVibrateMode() {
        player.play(ReminderIntensity.LIGHT, reminderChannelsFor(android.media.AudioManager.RINGER_MODE_VIBRATE))
        assertTrue("振动模式下轻档也必须振动", shadowOf(vibrator).isVibrating)
    }
}
