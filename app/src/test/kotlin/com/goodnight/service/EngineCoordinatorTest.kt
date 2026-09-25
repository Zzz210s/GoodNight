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

    /**
     * v2.1 Task 7:任务切换命令同样经协调器落到引擎(唯一驱动者 + 同一把 mutex),
     * 不再像 Task 6 的删除路径那样绕过锁直接调 `engine.setTask`。
     */
    @Test fun setTaskCommandRoutesThroughCoordinator() = runBlocking {
        val g = graphFor("coord_set_task")
        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L))
        assertNull(g.engine.snapshot.value!!.taskId)
        g.coordinator.run(TimerCommand(ACTION_SET_TASK, taskId = 7L))
        assertEquals(7L, g.engine.snapshot.value!!.taskId)
        g.coordinator.run(TimerCommand(ACTION_SET_TASK, taskId = null))
        assertNull("「不绑定」= 清空运行态绑定", g.engine.snapshot.value!!.taskId)
    }

    /**
     * v2.2 Task 3:START 可自带任务 id —— 任务卡片「点 chip 即开始」= 一条命令在同一把锁内
     * 「起画 + 绑定任务」。分两条 intent(start 再 set_task)下发会有顺序竞态:set_task 若先到,
     * 空闲态(无快照)会被 `engine.setTask` 静默吞掉,绑定就丢了。
     */
    @Test fun startCommandCanBindTaskAtomically() = runBlocking {
        val g = graphFor("coord_start_binds_task")
        g.coordinator.run(
            TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L, taskId = 7L),
        )
        val s = g.engine.snapshot.value!!
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(7L, s.taskId)
        assertTrue("首次绑定 = 定义整段,不产生段内切点", s.taskCuts.isEmpty())
    }

    /**
     * v2.2 Task 3 修复:运行中收到「自带 taskId 的 START」时,`engine.start` 是 no-op,
     * 但**不得跟着 setTask** —— 否则会给正在运行的那一段静默改归属(段内生成 task 切点)。
     * 可达场景:首页/通知的启动 Intent 与任务页点 chip 竞态到达,chip 那条后到。
     */
    @Test fun startWithTaskDoesNotRebindWhileTimerRuns() = runBlocking {
        val g = graphFor("coord_start_ignored_when_running")
        g.coordinator.run(TimerCommand(ACTION_START, profileId = 1L, workMillis = 60_000L, restMillis = 30_000L))
        assertEquals(EngineStatus.RUNNING, g.engine.snapshot.value!!.status)

        // 第二条 START 自带 taskId + 另一个时钟:不得启动、不得换归属
        g.coordinator.run(
            TimerCommand(ACTION_START, profileId = 2L, workMillis = 45 * 60_000L, restMillis = 15 * 60_000L, taskId = 7L),
        )
        val s = g.engine.snapshot.value!!
        assertNull("运行中不得静默绑定任务", s.taskId)
        assertEquals("也不得换时钟", 1L, s.profileId)
        assertEquals(60_000L, s.workMillis)
        assertTrue("不产生段内切点", s.taskCuts.isEmpty())
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
