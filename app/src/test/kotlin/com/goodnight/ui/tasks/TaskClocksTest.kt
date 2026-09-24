package com.goodnight.ui.tasks

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * v2.2 Task 3:任务卡片上的时钟 chip。
 *
 * VM 层钉三件事:
 * 1. 卡片数据 = 该任务**专属** + 全部**通用**(排除归档),分两组交给 UI(通用加淡色边框);
 * 2. 点 chip = 发**既有命令入口**([com.goodnight.service.TimerCommands.start]),且命令里同时带上
 *    卡片任务 id 与该时钟 id —— 用 intent extra 断言(服务->协调器->引擎,不直接调引擎);
 * 3. 「+ 添加时钟」= 在**该任务作用域**内新建(create 收到 taskId)。
 *
 * 计时中不换时钟(Task 4 的确认流程)在本任务只要求 no-op,见 [startIsIgnoredWhileTimerRuns]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TaskClocksTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var g: AppGraph

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "task_clocks")
    }

    @After fun tearDown() {
        runCatching { g.db.close() }
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun vm() = TaskListViewModel(g)

    private suspend fun newTask(title: String) = g.taskRepo.create(title, g.time.now())!!
    private suspend fun newClock(name: String, taskId: Long?, mode: Int = ProfileMode.COUNTDOWN) =
        g.profileRepo.create(name, 25, 5, mode, taskId)!!

    /** 归档一条时钟:先造一段引用(有历史才归档),再走仓库的 removeOrArchive */
    private suspend fun archive(id: Long) {
        g.db.focusSessionDao().insertAll(listOf(FocusSessionEntity(profileId = id, startAt = 0, endAt = 60_000)))
        g.profileRepo.removeOrArchive(id)
    }

    private fun snap(status: EngineStatus) = RuntimeSnapshot(
        profileId = 1, workMillis = 60_000, restMillis = 30_000, phase = Phase.WORK, status = status,
        cycleCount = 0, startElapsed = 0, endElapsed = 60_000, endWall = 0,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    /** 卡片数据:每个任务拿到「专属 + 通用」两组,归档时钟两边都不出现 */
    @Test fun cardClocksAreSpecificPlusGenericAndSkipArchived() = runTest {
        val a = newTask("写周报")
        val b = newTask("读论文")
        newClock("通用 25/5", taskId = null)
        newClock("A 专属", taskId = a)
        newClock("B 专属", taskId = b)
        val deadGeneric = newClock("旧通用", taskId = null)
        val deadA = newClock("旧 A 专属", taskId = a)
        archive(deadGeneric)
        archive(deadA)

        val clocks = vm().clocksByTask.first { it.containsKey(a) && it.containsKey(b) }

        assertEquals("A 的专属时钟", listOf("A 专属"), clocks.getValue(a).specific.map { it.name })
        assertEquals("A 的通用时钟", listOf("通用 25/5"), clocks.getValue(a).generic.map { it.name })
        assertEquals("B 的专属时钟", listOf("B 专属"), clocks.getValue(b).specific.map { it.name })
        assertEquals("B 的通用时钟", listOf("通用 25/5"), clocks.getValue(b).generic.map { it.name })
        assertTrue("归档时钟不出现在任何卡片", clocks.values.none { row -> row.all.any { it.name.startsWith("旧") } })
    }

    /** 点专属 chip:命令带该时钟 id + 该卡片任务 id,且把时钟参数(工作/休息/正计时)照抄 */
    @Test fun tappingSpecificClockStartsTimerWithThatClockAndTask() = runTest {
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
        val a = newTask("写周报")
        val common = g.profileRepo.byId(newClock("通用 25/5", taskId = null))!!

        vm().onStartClock(a, common)

        val started = shadowOf(app).nextStartedService
        assertEquals(ACTION_START, started.action)
        assertEquals(common.id, started.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals(a, started.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
    }

    /** 「+ 添加时钟」:弹窗状态挂在该任务上,确认后新建的时钟 taskId == 该任务 */
    @Test fun addClockCreatesUnderThatTask() = runTest {
        val a = newTask("写周报")
        val b = newTask("读论文")
        val v = vm()

        v.onAddClockRequest(a)
        assertEquals(a, v.addClockTaskId.value)

        v.onCreateClock(a, "A 新时钟", 45, 15, ProfileMode.COUNTDOWN)
        pump()

        val created = g.profileRepo.availableFor(a).first().first { it.name == "A 新时钟" }
        assertEquals("新建时钟归属该任务", a, created.taskId)
        assertTrue("不得落到别的任务或通用层", g.profileRepo.availableFor(b).first().none { it.name == "A 新时钟" })
        assertTrue("通用层也不应多出这一条", g.profileRepo.availableFor(null).first().none { it.name == "A 新时钟" })
        assertNull("新建成功后弹窗状态清空", v.addClockTaskId.value)

        v.onAddClockDismiss()
        assertNull(v.addClockTaskId.value)
    }

    /** 同作用域重名:仓库拒绝(返回 null),弹窗保持打开(用户可改名),一行不落库 */
    @Test fun addClockRejectedByScopeKeepsDialogOpen() = runTest {
        val a = newTask("写周报")
        val v = vm()
        v.onAddClockRequest(a)
        v.onCreateClock(a, "A 专属", 25, 5, ProfileMode.COUNTDOWN)
        pump()
        v.onAddClockRequest(a)
        v.onCreateClock(a, "A 专属", 30, 6, ProfileMode.COUNTDOWN)
        pump()

        assertEquals("重名被拒:同作用域只留一条", 1, g.profileRepo.availableFor(a).first().count { it.name == "A 专属" })
        assertNotNull("弹窗保持打开,用户可改名", v.addClockTaskId.value)
    }

    /**
     * 计时中不静默换时钟(设计 §4:换时钟确认流程是 Task 4 的范围)。
     * 本任务只保证「运行中点 chip 不发出任何命令」。
     */
    @Test fun startIsIgnoredWhileTimerRuns() = runTest {
        val a = newTask("写周报")
        val clock = g.profileRepo.byId(newClock("A 专属", taskId = a))!!
        g.engine.restore(snap(EngineStatus.RUNNING))

        vm().onStartClock(a, clock)

        assertNull("运行中不得发出开始命令", shadowOf(app).nextStartedService)
    }

    /** 卡片只跟进行中任务:勾完成后该任务不再有卡片(与首页任务选择器「只列进行中」同口径) */
    @Test fun doneTaskLosesItsCard() = runTest {
        val a = newTask("写周报")
        newClock("A 专属", taskId = a)
        val v = vm()
        assertTrue("进行中任务有卡片时钟组", v.clocksByTask.first { it.containsKey(a) }.containsKey(a))

        v.onToggleDone(a)
        pump()

        assertTrue("完成后卡片组一并消失", v.clocksByTask.first { !it.containsKey(a) }.isEmpty())
    }
}
