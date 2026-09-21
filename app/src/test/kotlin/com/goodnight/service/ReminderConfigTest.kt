package com.goodnight.service

import com.goodnight.data.ReminderIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderConfigTest {
    @Test fun durations() {
        // v1.13.0:轻档也要有声音(响铃模式下"轻"= 短促响铃),不再有 0 档
        assertEquals(2_000, ReminderPlayer.durationMs(ReminderIntensity.LIGHT))
        assertEquals(3_000, ReminderPlayer.durationMs(ReminderIntensity.STANDARD))
        assertEquals(5_000, ReminderPlayer.durationMs(ReminderIntensity.STRONG))
    }
    @Test fun patterns() {
        val p = ReminderPlayer.pattern(ReminderIntensity.STANDARD)
        assertEquals(0L, p[0]) // 立即开始震动
        assertTrue(p.sum() <= ReminderPlayer.durationMs(ReminderIntensity.STANDARD))
        assertTrue(ReminderPlayer.pattern(ReminderIntensity.STRONG).sum() > 0)
        // 轻档必须有振动(振动模式下"轻"不能等于"什么都不做")
        assertTrue(ReminderPlayer.pattern(ReminderIntensity.LIGHT).sum() > 0)
    }
}
