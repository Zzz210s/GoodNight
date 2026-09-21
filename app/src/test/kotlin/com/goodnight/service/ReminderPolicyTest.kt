package com.goodnight.service

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.13.0 提醒随系统静音/振动/响铃模式自动适配 —— 通道矩阵钉死:
 * 静音=仅通知(会自动消失),振动=仅振动(无通知),响铃=振动+铃声(无通知)。
 */
class ReminderPolicyTest {
    @Test fun silentPostsAutoDismissNotificationOnly() {
        val c = reminderChannelsFor(AudioManager.RINGER_MODE_SILENT)
        assertTrue("静音模式要发通知", c.notify)
        assertTrue("静音模式通知必须会自动消失", c.notifyTimeoutMs > 0)
        assertFalse("静音模式不振动", c.vibrate)
        assertFalse("静音模式不响铃", c.sound)
    }

    @Test fun vibrateModeVibratesWithoutNotification() {
        val c = reminderChannelsFor(AudioManager.RINGER_MODE_VIBRATE)
        assertTrue("振动模式要振动", c.vibrate)
        assertFalse("振动模式不响铃", c.sound)
        assertFalse("振动模式不发通知", c.notify)
        assertEquals(0L, c.notifyTimeoutMs)
    }

    @Test fun normalModeRingsAndVibratesWithoutNotification() {
        val c = reminderChannelsFor(AudioManager.RINGER_MODE_NORMAL)
        assertTrue("响铃模式要振动", c.vibrate)
        assertTrue("响铃模式要响铃", c.sound)
        assertFalse("响铃模式不发通知", c.notify)
    }

    @Test fun unknownModeFallsBackToRing() {
        val c = reminderChannelsFor(Int.MIN_VALUE)
        assertTrue(c.vibrate && c.sound)
        assertFalse(c.notify)
    }

    @Test fun modeNames() {
        assertEquals("静音", ringerModeName(AudioManager.RINGER_MODE_SILENT))
        assertEquals("振动", ringerModeName(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals("响铃", ringerModeName(AudioManager.RINGER_MODE_NORMAL))
    }
}
