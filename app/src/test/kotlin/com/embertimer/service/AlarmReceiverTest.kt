package com.embertimer.service

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.embertimer.EmberApp
import com.embertimer.di.AppGraph
import com.embertimer.timer.EngineStatus
import com.embertimer.timer.Phase
import com.embertimer.timer.RuntimeSnapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AlarmReceiver 契约钉(**v1.12.0 新契约**):到点由接收器**在进程内直接推进阶段**,
 * 不再把推进交给前台服务(这是"负秒不切换"的根因修复):
 * - 已到期 RUNNING:引擎推进(WORK→REST)、按新阶段重新武装闹钟、尽力起服务;
 * - 已到期但 start 被拒:引擎**照样推进**(无死路),闹钟仍武装;
 * - 未到期 RUNNING:引擎不动,重新武装到期闹钟,起服务;
 * - PAUSED/null:无副作用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlarmReceiverTest.TestApp::class)
class AlarmReceiverTest {
    class TestApp : EmberApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var app: EmberApp
    private var graph: AppGraph? = null

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        app = ctx as EmberApp
    }

    @After fun tearDown() {
        val g = graph ?: return
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun snap(status: EngineStatus, endElapsed: Long) = RuntimeSnapshot(
        profileId = 1L, workMillis = 100_000L, restMillis = 40_000L,
        phase = Phase.WORK, status = status, cycleCount = 0,
        startElapsed = 0L, endElapsed = endElapsed, endWall = 1_000_000L,
        timeSpentPaused = 0L, lastPauseTime = 0L, timeAtPause = 0L,
        savedAtWall = 1_000_000L, savedAtElapsed = 0L, ckptDate = null, ckptAccum = 0L,
    )

    /** 受控 graph:restore 同时置 ready;协调器与接收器共用(install 由测试显式调用) */
    private fun graphFor(storeName: String, snap: RuntimeSnapshot?): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = storeName)
        app.graph = g
        graph = g
        g.coordinator.install()
        runBlocking { g.engine.restore(snap) }
        return g
    }

    private fun fire(context: Context = ctx) =
        AlarmReceiver().onReceive(context, Intent("com.embertimer.ALARM"))

    private fun awaitCond(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
    }

    private fun nextAlarmTrigger(): Long? {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return shadowOf(am).peekNextScheduledAlarm()?.triggerAtTime
    }

    /** 全部已武装闹钟的触发时刻(主闹钟=墙钟轴,安全网=elapsed 轴,数值量级不同) */
    private fun scheduledTriggers(): List<Long> {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return shadowOf(am).scheduledAlarms.map { it.triggerAtTime }
    }

    /** 到点:接收器直接推进到 REST,并按新阶段武装下一段闹钟 */
    @Test fun expiredRunningAdvancesPhaseInProcess() {
        val g = graphFor("rx_expired_ok", null)
        runBlocking { g.engine.restore(snap(EngineStatus.RUNNING, g.time.elapsedRealtime() - 1_000)) }
        fire()
        awaitCond { g.engine.snapshot.value?.phase == Phase.REST }
        val s = g.engine.snapshot.value!!
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(Phase.REST, s.phase)
        // 事件反应(重武装)在锁外异步执行,等待其完成
        awaitCond { nextAlarmTrigger() != null }
        assertTrue("新阶段应已武装到期闹钟", nextAlarmTrigger() != null)
        awaitCond { shadowOf(app).peekNextStartedService() != null }
    }

    /** 到点但后台起服务被拒:引擎**仍然推进**(不再死路),闹钟仍武装 */
    @Test fun expiredRunningWithDeniedStartStillAdvances() {
        val g = graphFor("rx_expired_denied", null)
        runBlocking { g.engine.restore(snap(EngineStatus.RUNNING, g.time.elapsedRealtime() - 1_000)) }
        fire(DeniedStartContext(ctx))
        awaitCond { g.engine.snapshot.value?.phase == Phase.REST }
        assertEquals(Phase.REST, g.engine.snapshot.value!!.phase)
        awaitCond { nextAlarmTrigger() != null }
        assertTrue("起服务失败也必须留有下一段闹钟", nextAlarmTrigger() != null)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    /** 未到期:引擎不动,重新武装到期闹钟(主=setAlarmClock 墙钟 / 安全网=elapsed),拉起服务 */
    @Test fun activeRunningRearmsAndStartsService() {
        var end = 0L
        val g = graphFor("rx_active", null)
        end = g.time.elapsedRealtime() + 300_000
        runBlocking { g.engine.restore(snap(EngineStatus.RUNNING, end)) }
        fire()
        // 接收器在协程里先后武装两个闹钟:等到两个都到齐再断言,避免读到中间态
        awaitCond { scheduledTriggers().size >= 2 }
        val triggers = scheduledTriggers()
        assertTrue(
            "安全网闹钟应落在 elapsed 轴的 end+45s:$triggers",
            triggers.contains(end + SAFETY_MS),
        )
        assertTrue(
            "主闹钟应是墙钟触发的用户可见闹钟(setAlarmClock):$triggers",
            triggers.any { it >= System.currentTimeMillis() + 250_000 },
        )
        awaitCond { shadowOf(app).peekNextStartedService() != null }
        assertNotNull(shadowOf(app).nextStartedService)
    }

    /** PAUSED / null:无副作用 */
    @Test fun pausedOrNullDoesNothing() {
        graphFor("rx_paused", snap(EngineStatus.PAUSED, 0L))
        fire()
        Thread.sleep(300)
        assertNull(nextAlarmTrigger())
        assertNull(shadowOf(app).peekNextStartedService())
    }

    private class DeniedStartContext(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName =
            throw IllegalStateException("background FGS start denied")

        override fun startService(service: Intent): ComponentName? =
            throw IllegalStateException("background start denied")
    }
}
