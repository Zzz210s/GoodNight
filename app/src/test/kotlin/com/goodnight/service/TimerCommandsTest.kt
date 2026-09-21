package com.goodnight.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerCommandsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** Task 7 / #10:start intent 携带 countUp 信号(缺省 false = 倒计时逐字节不变) */
    @Test fun startIntentCarriesCountUpFlag() {
        val down = TimerCommands.startIntent(ctx, 3L, 60_000L, 20_000L, countUp = false)
        assertEquals(ACTION_START, down.action)
        assertEquals(3L, down.getLongExtra(EXTRA_PROFILE_ID, -1))
        assertEquals(60_000L, down.getLongExtra(EXTRA_WORK_MILLIS, 0))
        assertEquals(20_000L, down.getLongExtra(EXTRA_REST_MILLIS, 0))
        assertFalse("倒计时缺省不得携带正计时信号", down.getBooleanExtra(EXTRA_COUNT_UP, false))
        val up = TimerCommands.startIntent(ctx, 3L, 60_000L, 20_000L, countUp = true)
        assertTrue("正计时须显式携带 count_up", up.getBooleanExtra(EXTRA_COUNT_UP, false))
    }

    /** Task 7:暂停中重开(改时长/换配置)同样携带目标模式,服务据此以新模式重开会话 */
    @Test fun restartPhaseIntentCarriesCountUpFlag() {
        val up = TimerCommands.restartPhaseIntent(ctx, 9L, 45_000L, 10_000L, countUp = true)
        assertEquals(ACTION_RESTART_PHASE, up.action)
        assertEquals(9L, up.getLongExtra(EXTRA_PROFILE_ID, -1))
        assertTrue(up.getBooleanExtra(EXTRA_COUNT_UP, false))
        val down = TimerCommands.restartPhaseIntent(ctx, 9L, 45_000L, 10_000L, countUp = false)
        assertFalse(down.getBooleanExtra(EXTRA_COUNT_UP, true))
    }
}
