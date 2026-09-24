package com.goodnight.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * v2.2 Task 3:任务卡片「点 chip 即开始」把任务 id 搭在 START 上(与时钟同一条命令),
     * 既有调用方不传 = 哨兵值 = 不绑定(逐字节不变);解析回 null 与 SET_TASK 同口径。
     */
    @Test fun startIntentCarriesTaskIdOnlyWhenGiven() {
        val plain = TimerCommands.startIntent(ctx, 3L, 60_000L, 20_000L)
        assertEquals(NO_TASK_ID, plain.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        assertNull(plain.toTimerCommand(ACTION_START).taskId)
        val bound = TimerCommands.startIntent(ctx, 3L, 60_000L, 20_000L, taskId = 42L)
        assertEquals(ACTION_START, bound.action)
        assertEquals(42L, bound.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        assertEquals(42L, bound.toTimerCommand(ACTION_START).taskId)
    }

    /**
     * v2.2 Task 4:换时钟的命令载荷与 start 同形(时钟/工作/休息/模式/任务),但 action 不同 ——
     * 服务据此在同一把锁内「终止当前 + 重新开始」(不新增切点类型)。
     */
    @Test fun switchClockIntentCarriesClockAndTask() {
        val plain = TimerCommands.switchClockIntent(ctx, 3L, 60_000L, 20_000L)
        assertEquals(ACTION_SWITCH_CLOCK, plain.action)
        assertEquals(3L, plain.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals(60_000L, plain.getLongExtra(EXTRA_WORK_MILLIS, 0L))
        assertEquals(20_000L, plain.getLongExtra(EXTRA_REST_MILLIS, 0L))
        assertFalse(plain.getBooleanExtra(EXTRA_COUNT_UP, false))
        assertNull("未绑定任务用哨兵值,解析回 null", plain.toTimerCommand(ACTION_SWITCH_CLOCK).taskId)
        val bound = TimerCommands.switchClockIntent(ctx, 3L, 60_000L, 20_000L, countUp = true, taskId = 42L)
        assertTrue(bound.getBooleanExtra(EXTRA_COUNT_UP, false))
        assertEquals(42L, bound.toTimerCommand(ACTION_SWITCH_CLOCK).taskId)
    }

    /** v2.1 Task 7:任务切换命令携带任务 id(「不绑定」用哨兵值表达 null,解析回 null) */
    @Test fun setTaskIntentCarriesTaskId() {
        val bound = TimerCommands.setTaskIntent(ctx, 42L)
        assertEquals(ACTION_SET_TASK, bound.action)
        assertEquals(42L, bound.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        val unbound = TimerCommands.setTaskIntent(ctx, null)
        assertEquals(ACTION_SET_TASK, unbound.action)
        assertEquals(NO_TASK_ID, unbound.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        assertNull(unbound.toTimerCommand(ACTION_SET_TASK).taskId)
        assertEquals(42L, bound.toTimerCommand(ACTION_SET_TASK).taskId)
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
