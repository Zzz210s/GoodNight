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
 * v1.12.3:提醒改为**只振动、不响铃**。
 * 这里锁死行为:STANDARD/STRONG 必须振动,LIGHT 完全不振动(安静),
 * 且不再触发任何铃声播放(铃声代码已移除,若回归引入会体现在此处与实现审查)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderPlayerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val vibrator: Vibrator get() = ctx.getSystemService(Vibrator::class.java)

    @Test
    fun standardVibrates() {
        ReminderPlayer(ctx).play(ReminderIntensity.STANDARD)
        assertTrue("STANDARD 应振动", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun strongVibrates() {
        ReminderPlayer(ctx).play(ReminderIntensity.STRONG)
        assertTrue("STRONG 应振动", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun lightStaysSilent() {
        ReminderPlayer(ctx).play(ReminderIntensity.LIGHT)
        assertFalse("LIGHT 应完全安静(不振动)", shadowOf(vibrator).isVibrating)
    }
}
