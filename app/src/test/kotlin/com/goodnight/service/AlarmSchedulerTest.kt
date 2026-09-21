package com.goodnight.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import com.goodnight.timer.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlarmSchedulerTest.TestApp::class)
class AlarmSchedulerTest {
    /** 空 onCreate:GoodNightApp 冷启会武装报表闹钟,会污染本类对 AlarmManager 的断言 */
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private val time = object : TimeProvider {
        override fun now() = 0L
        override fun elapsedRealtime() = 0L
    }

    private fun plan(primary: Long) = AlarmPlan(primaryElapsed = primary, safetyElapsed = primary + SAFETY_MS)

    /** v1.12.0:双闹钟冗余 —— 主到点 + 安全网 */
    @Test fun armSchedulesPrimaryAndSafety() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sched = AlarmScheduler(ctx, time)
        sched.arm(plan(123_456L))
        val am = ctx.getSystemService(AlarmManager::class.java)
        val triggers = shadowOf(am).scheduledAlarms.map { it.triggerAtTime }.sorted()
        assertEquals(listOf(123_456L, 123_456L + SAFETY_MS), triggers)
    }

    @Test fun cancelClearsBothAlarms() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sched = AlarmScheduler(ctx, time)
        sched.arm(plan(123_456L))
        sched.cancel()
        val am = ctx.getSystemService(AlarmManager::class.java)
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    /** 重复武装:同名 PendingIntent 覆盖,不累积 */
    @Test fun armTwiceKeepsTwoAlarms() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sched = AlarmScheduler(ctx, time)
        sched.arm(plan(1L)); sched.arm(plan(2L))
        val am = ctx.getSystemService(AlarmManager::class.java)
        assertEquals(2, shadowOf(am).scheduledAlarms.size)
    }

    /** 未授权精确闹钟时不走 exact,退化为 inexact 可唤醒闹钟(图标不变,仍然是两个) */
    @Test fun exactNotPermittedFallsBackToInexact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val sched = AlarmScheduler(ctx, time)
            sched.arm(plan(50_000L))
            val am = ctx.getSystemService(AlarmManager::class.java)
            assertEquals(2, shadowOf(am).scheduledAlarms.size)
            assertTrue(shadowOf(am).scheduledAlarms.all { it.triggerAtTime >= 50_000L })
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }

    /** setExact* 抛 SecurityException(授权在检查与调用之间被撤销)→ 降级 inexact,不崩不丢 */
    @Test fun exactThrowsSecurityExceptionFallsBackToInexact() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sched = ThrowingExactScheduler(ctx, time)
        sched.arm(plan(123_456L))
        val am = ctx.getSystemService(AlarmManager::class.java)
        assertEquals(2, shadowOf(am).scheduledAlarms.size)
    }

    /** 计划推导:只有"运行中 + 倒计时"才武装 */
    @Test fun planOnlyForRunningCountdown() {
        assertEquals(null, alarmPlanFor(null, 0L))
        assertEquals(null, alarmPlanFor(snap(EngineStatus.PAUSED, 1_000L), 0L))
        assertEquals(null, alarmPlanFor(snap(EngineStatus.RUNNING, 1_000L, countUp = true), 0L))
        val p = alarmPlanFor(snap(EngineStatus.RUNNING, 600_000L), 0L)!!
        assertEquals(600_000L, p.primaryElapsed)
        assertEquals(600_000L + SAFETY_MS, p.safetyElapsed)
    }

    /** 已过期:立刻扰动(now+1s),让被冻结的进程尽快醒来 */
    @Test fun expiredPlansImmediateNudge() {
        val p = alarmPlanFor(snap(EngineStatus.RUNNING, 1_000L), 99_000L)!!
        assertEquals(99_000L + 1_000L, p.primaryElapsed)
    }

    /** 便捷入口:无快照/暂停时 arm(snap) 等于取消 */
    @Test fun armSnapshotCancelsWhenNotRunning() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sched = AlarmScheduler(ctx, time)
        sched.arm(plan(123_456L))
        sched.arm(null as RuntimeSnapshot?)
        val am = ctx.getSystemService(AlarmManager::class.java)
        assertNull(shadowOf(am).peekNextScheduledAlarm())
    }

    private fun snap(status: EngineStatus, end: Long, countUp: Boolean = false) = RuntimeSnapshot(
        profileId = 1L, workMillis = 60_000L, restMillis = 60_000L, phase = Phase.WORK,
        status = status, cycleCount = 0, startElapsed = 0L, endElapsed = end, endWall = end,
        timeSpentPaused = 0L, lastPauseTime = 0L, timeAtPause = 0L,
        savedAtWall = 0L, savedAtElapsed = 0L, ckptDate = null, ckptAccum = 0L, countUp = countUp,
    )

    private class ThrowingExactScheduler(ctx: Context, time: TimeProvider) : AlarmScheduler(ctx, time) {
        override fun scheduleExactAlarm(elapsed: Long, pi: PendingIntent) {
            throw SecurityException("SCHEDULE_EXACT_ALARM revoked")
        }
    }
}
