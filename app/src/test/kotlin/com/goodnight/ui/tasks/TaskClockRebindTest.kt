package com.goodnight.ui.tasks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import com.goodnight.service.ACTION_SWITCH_CLOCK
import com.goodnight.service.EXTRA_PROFILE_ID
import com.goodnight.service.EXTRA_TASK_ID
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 评审 Minor 回归:同一个时钟**换任务**也要走确认流程。
 *
 * 旧 [clockPickAction] 只比 `profileId`,于是在任务 A 的卡片上点「正在任务 B 名下跑的通用时钟 X」
 * 会静默 no-op —— 用户点了却什么都没有发生,归属也没改成 A。现在归属参与判定,不一致就确认,
 * 确认后一条 SWITCH 命令把新会话绑到**这张卡片**的任务上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TaskClockRebindTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var g: AppGraph

    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "task_clock_rebind_${testName.methodName}")
        runBlocking { g.engine.restore(null) }
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun runningSnap(profileId: Long, taskId: Long?) = RuntimeSnapshot(
        profileId = profileId, workMillis = 600_000, restMillis = 60_000, phase = Phase.WORK,
        status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 600_000,
        endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0, taskId = taskId,
    )

    @Test fun sameClockOnAnotherTaskCardAsksAndRebinds() = runTest {
        val a = g.taskRepo.create("写周报", g.time.now())!!
        val b = g.taskRepo.create("读论文", g.time.now())!!
        val shared = g.profileRepo.byId(g.profileRepo.create("通用 25/5", 25, 5, 0, null)!!)!!
        g.engine.restore(runningSnap(shared.id, taskId = b))
        val model = TaskListViewModel(g)

        model.onStartClock(a, shared)

        assertEquals("同一时钟换任务:先确认", PendingClockSwitch(shared, a), model.pendingClockSwitch.value)
        assertNull("未确认前零命令", shadowOf(app).nextStartedService)

        model.onConfirmClockSwitch()

        val cmd = shadowOf(app).nextStartedService
        assertEquals(ACTION_SWITCH_CLOCK, cmd.action)
        assertEquals(shared.id, cmd.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals("归属改成这张卡片的任务", a, cmd.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
    }

    /** 时钟与归属都没变:仍是静默 no-op(不得因为本修复新增多余弹窗) */
    @Test fun sameClockOnItsOwnTaskCardStaysANoOp() = runTest {
        val a = g.taskRepo.create("写周报", g.time.now())!!
        val shared = g.profileRepo.byId(g.profileRepo.create("通用 25/5", 25, 5, 0, null)!!)!!
        g.engine.restore(runningSnap(shared.id, taskId = a))
        val model = TaskListViewModel(g)

        model.onStartClock(a, shared)

        assertNull(model.pendingClockSwitch.value)
        assertNull(shadowOf(app).nextStartedService)
    }
}
