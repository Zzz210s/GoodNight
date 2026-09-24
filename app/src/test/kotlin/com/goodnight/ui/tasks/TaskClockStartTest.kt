package com.goodnight.ui.tasks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.service.ACTION_START
import com.goodnight.service.EXTRA_COUNT_UP
import com.goodnight.service.EXTRA_PROFILE_ID
import com.goodnight.service.EXTRA_REST_MILLIS
import com.goodnight.service.EXTRA_TASK_ID
import com.goodnight.service.EXTRA_WORK_MILLIS
import com.goodnight.service.NO_TASK_ID
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.2 Task 3:点 chip 即开始的两条守卫(自 [TaskClocksTest] 拆出,守 200 行)。
 *
 * 1. 空闲且引擎就绪 = 发**既有命令入口**([com.goodnight.service.TimerCommands.start]),命令里同时
 *    带上卡片任务 id 与该 chip 的时钟 id —— 用 intent extra 断言(服务->协调器->引擎,不直接调引擎);
 * 2. 计时中 no-op:静默换时钟会让 45/15 与 25/5 混在同一段里,换时钟确认流程是 Task 4 的范围;
 * 3. `engine.ready` 为 false(冷启动 restore() 未完成)也 no-op:此时快照为空,放行会把尚未恢复的
 *    运行快照覆盖掉(`engine.start` -> `save()`),丢掉本段未落账的时间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TaskClockStartTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var g: AppGraph

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "task_clock_start")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = TaskListViewModel(g)
    private suspend fun newTask(title: String) = g.taskRepo.create(title, g.time.now())!!
    private suspend fun newClock(name: String, taskId: Long?, mode: Int = ProfileMode.COUNTDOWN) =
        g.profileRepo.create(name, 25, 5, mode, taskId)!!

    private fun snap(status: EngineStatus) = RuntimeSnapshot(
        profileId = 1, workMillis = 60_000, restMillis = 30_000, phase = Phase.WORK, status = status,
        cycleCount = 0, startElapsed = 0, endElapsed = 60_000, endWall = 0,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    /** 点专属 chip:命令带该时钟 id + 该卡片任务 id,且把时钟参数(工作/休息/正计时)照抄 */
    @Test fun tappingSpecificClockStartsTimerWithThatClockAndTask() = runTest {
        g.engine.restore(null) // 生产路径由 AppGraph.bootstrap() 就绪;这里对齐
        val a = newTask("写周报")
        val clock = g.profileRepo.byId(newClock("A 专属", taskId = a, mode = ProfileMode.COUNTUP))!!

        vm().onStartClock(a, clock)

        val started = shadowOf(app).nextStartedService
        assertEquals(ACTION_START, started.action)
        assertEquals("时钟 id", clock.id, started.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals("任务由卡片决定", a, started.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        assertEquals(25 * 60_000L, started.getLongExtra(EXTRA_WORK_MILLIS, -1L))
        assertEquals(5 * 60_000L, started.getLongExtra(EXTRA_REST_MILLIS, -1L))
        assertTrue("正计时时钟照抄 countUp", started.getBooleanExtra(EXTRA_COUNT_UP, false))
    }

    /** 点通用 chip:clock id 是该通用时钟,taskId 是**这张卡片**的任务(不是通用时钟的空作用域) */
    @Test fun tappingGenericClockBindsTheCardTask() = runTest {
        g.engine.restore(null)
        val a = newTask("写周报")
        val common = g.profileRepo.byId(newClock("通用 25/5", taskId = null))!!

        vm().onStartClock(a, common)

        val started = shadowOf(app).nextStartedService
        assertEquals(ACTION_START, started.action)
        assertEquals(common.id, started.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals(a, started.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
    }

    /**
     * 修复:`ready` 为 false 时点 chip 必须 no-op。冷启动 `restore()` 未完成时快照为空,
     * 旧门控会判为「空闲」直接放行 -> 覆盖尚未恢复的运行快照并丢掉本段未落账的时间。
     */
    @Test fun startIsIgnoredWhenEngineNotReady() = runTest {
        val cold = AppGraph(ctx, useInMemoryDb = true, storeFileName = "task_clock_start_cold")
        try {
            assertFalse("冷启动窗口:engine 尚未就绪", cold.engine.ready.value)
            val a = cold.taskRepo.create("写周报", cold.time.now())!!
            val clock = cold.profileRepo.byId(cold.profileRepo.create("A 专属", 25, 5, ProfileMode.COUNTDOWN, a)!!)!!

            TaskListViewModel(cold).onStartClock(a, clock)

            assertNull("未就绪不得发出开始命令", shadowOf(app).nextStartedService)
        } finally {
            // 必须先把 appScope 停掉再关库:冷图的 DataStore/引擎协程还活着时关库,异常会变成
            // 「uncaught exceptions before the test started」污染同一 JVM 里的下一个测试类
            runBlocking { cold.appScope.coroutineContext.job.cancelAndJoin(); cold.db.close() }
        }
    }

    /** 计时中不静默换时钟(Task 4 之前只要求「运行中点 chip 不发出任何命令」) */
    @Test fun startIsIgnoredWhileTimerRuns() = runTest {
        val a = newTask("写周报")
        val clock = g.profileRepo.byId(newClock("A 专属", taskId = a))!!
        g.engine.restore(snap(EngineStatus.RUNNING))

        vm().onStartClock(a, clock)

        assertNull("运行中不得发出开始命令", shadowOf(app).nextStartedService)
    }
}
