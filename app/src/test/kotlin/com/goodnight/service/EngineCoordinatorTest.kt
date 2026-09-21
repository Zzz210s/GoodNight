package com.goodnight.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 协调器契约(v1.12.0):引擎驱动不再依赖前台服务 —— 命令/到期推进/对账都在 [EngineCoordinator]。
 * 覆盖:命令生效、到期幂等推进、对账分支(过期推进/空闲拆除回调)、正计时不到期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = EngineCoordinatorTest.TestApp::class)
class EngineCoordinatorTest {
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var app: GoodNightApp
    private var graph: AppGraph? = null

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        app = ctx as GoodNightApp
    }

    @After fun tearDown() {
        val g = graph ?: return
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun graphFor(store: String, snap: com.goodnight.timer.RuntimeSnapshot? = null): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = store)
        app.graph = g
        graph = g
        g.coordinator.install()
        runBlocking { g.engine.restore(snap) }
        runBlocking { g.coordinator.awaitReadyAndSubscribed() }
        return g
    }

    @Test fun startPauseResumeStopCommands() = runBlocking {
        val g = graphFor("coord_commands")
        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L))
        assertEquals(EngineStatus.RUNNING, g.engine.snapshot.value!!.status)
        g.coordinator.run(TimerCommand(ACTION_PAUSE))
        assertEquals(EngineStatus.PAUSED, g.engine.snapshot.value!!.status)
        g.coordinator.run(TimerCommand(ACTION_RESUME))
        assertEquals(EngineStatus.RUNNING, g.engine.snapshot.value!!.status)
        g.coordinator.run(TimerCommand(ACTION_STOP))
        assertNull(g.engine.snapshot.value)
    }

    /** 到期推进:WORK -> REST;再调一次幂等(不重复推进) */
    @Test fun advanceIfExpiredIsIdempotent() = runBlocking {
        val g = graphFor("coord_advance")
        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L))
        val expired = g.engine.snapshot.value!!.copy(
            startElapsed = g.time.elapsedRealtime() - 70_000L,
            endElapsed = g.time.elapsedRealtime() - 10_000L,
        )
        g.engine.adoptRestored(expired)
        assertTrue(g.coordinator.advanceIfExpired())
        assertEquals(Phase.REST, g.engine.snapshot.value!!.phase)
        assertFalse("二次调用不应再推进", g.coordinator.advanceIfExpired())
    }

    /** 正计时永不到期:即使 endElapsed 已过也不推进 */
    @Test fun countUpNeverExpires() = runBlocking {
        val g = graphFor("coord_countup")
        g.coordinator.run(
            TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L, countUp = true),
        )
        val expired = g.engine.snapshot.value!!.copy(endElapsed = g.time.elapsedRealtime() - 10_000L)
        g.engine.adoptRestored(expired)
        assertFalse(g.coordinator.advanceIfExpired())
        assertEquals(Phase.WORK, g.engine.snapshot.value!!.phase)
    }

    /** 对账:空闲(无快照)-> 触发服务拆除回调;过期运行 -> 推进 */
    @Test fun reconcileTearsDownWhenIdleAndAdvancesWhenExpired() = runBlocking {
        val g = graphFor("coord_reconcile")
        var tore = false
        g.coordinator.onTeardown = { tore = true }
        g.coordinator.reconcile(awaitingStart = false)
        assertTrue("空闲应触发拆除", tore)

        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L))
        g.engine.adoptRestored(
            g.engine.snapshot.value!!.copy(endElapsed = g.time.elapsedRealtime() - 1_000L),
        )
        g.coordinator.reconcile(awaitingStart = false)
        assertEquals(Phase.REST, g.engine.snapshot.value!!.phase)
    }

    /** v1.12.1:检查点只推进游标,不单独累加当日合计(否则出现"合计 > 时间段之和") */
    @Test fun checkpointDoesNotAddToDailyTotal() = runBlocking {
        val g = graphFor("coord_ckpt")
        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 600_000L, restMillis = 60_000L))
        g.engine.adoptRestored(
            g.engine.snapshot.value!!.copy(startElapsed = g.time.elapsedRealtime() - 120_000L),
        )
        g.coordinator.flushCheckpoint(force = true)
        val today = java.time.LocalDate.now().toString()
        val sum = g.totalsRepo.breakdownByDate(today).sumOf { it.total }
        assertEquals("检查点不得写当日合计(段落才是唯一来源)", 0L, sum)
    }

    /** 检查点落账:锁内 flush 不抛异常且能推进累计 */
    @Test fun flushCheckpointWritesTotals() = runBlocking {
        val g = graphFor("coord_flush")
        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 600_000L, restMillis = 60_000L))
        g.engine.adoptRestored(
            g.engine.snapshot.value!!.copy(startElapsed = g.time.elapsedRealtime() - 120_000L),
        )
        g.coordinator.flushCheckpoint(force = true)
        assertNotNull(g.engine.snapshot.value)
    }
}
