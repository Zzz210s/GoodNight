package com.goodnight.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * v2.1 Task 6(评审修复):删除确认文案的「已记录的 N 分钟」必须含**在途工作段** ——
 * 段落只在结算时落库,运行中删当前绑定任务时本段工作时间还没进 focus_session,
 * 但它同样会以未绑定保留,漏掉它就会在用户工作了 40 分钟后弹「已记录的 0 分钟」。
 *
 * 归属口径与结算落段一致(切点分窗 + >=3 分钟暂停不计工作),故这里既覆盖无切点的常见路径,
 * 也覆盖「段内切走再切回」与暂停/休息/未绑定等不计入的分支。用例独立成文件以守住单文件 <=200 行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDeleteInFlightTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "tlvm_inflight_store")
    }

    @After fun tearDown() {
        runCatching { g.db.close() }
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun vm() = TaskListViewModel(g)
    private suspend fun active() = g.taskRepo.observeActive().first()
    private suspend fun idOf(title: String) = active().first { it.title == title }.id

    private fun snap(taskId: Long?) = RuntimeSnapshot(
        profileId = 1, workMillis = 1, restMillis = 1, phase = Phase.WORK,
        status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 1,
        endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0, taskId = taskId,
    )

    private fun assertNear(expected: Long, actual: Long) =
        assertTrue("expected ~$expected, got $actual", abs(expected - actual) < 5_000L)

    @Test fun deletePromptIncludesInFlightWorkOfBoundTask() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1, startAt = 0, endAt = 5 * 60_000L, taskId = id)),
        )
        val now = g.time.now()
        // 运行中、正绑着它:本段已工作 40 分钟,尚未落库
        g.engine.restore(snap(taskId = id).copy(sessionStartWall = now - 40 * 60_000L))

        v.onDeleteRequest(id); pump()
        val prompt = v.deletePrompt.value!!
        assertEquals(45L, prompt.minutes) // 5 已落库 + 40 在途
        // 弹窗标题即任务名(用户要知道删的是哪个):TaskDeleteDialog 直接渲染 prompt.title
        assertEquals("写周报", prompt.title)
        assertEquals(
            "将删除任务,已记录的 45 分钟会保留为未绑定",
            ctx.getString(R.string.task_delete_confirm, prompt.minutes),
        )
    }

    /** 段内切到别的任务再切回来:只算归属本任务的子窗口(整段 accrued 会把别人的时间算进来) */
    @Test fun deletePromptCountsOnlySubWindowsOwnedByThatTask() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        val other = 999L
        val now = g.time.now()
        // 本段 60 分钟:A 前 20、B 中间 20、A 后 20(全部在途)
        g.engine.restore(
            snap(taskId = id).copy(
                sessionStartWall = now - 60 * 60_000L,
                taskCuts = "${now - 40 * 60_000L},$id,$other;${now - 20 * 60_000L},$other,$id",
            ),
        )
        v.onDeleteRequest(id); pump()
        assertEquals(40L, v.deletePrompt.value!!.minutes) // 20 + 20,不含 B 的 20 分钟
    }

    /**
     * 段内切走**不切回**:删除当前绑定任务时,段首窗口归段首任务(最早切点的 fromTaskId),
     * 不能按当前绑定把它算给新任务 —— 否则弹窗分钟数虚高(切得越多差越多)。
     */
    @Test fun deletePromptExcludesHeadWindowAfterSwitchingAway() = runTest {
        val v = vm()
        v.onCreate("写周报"); v.onCreate("其它"); pump()
        val head = idOf("写周报")
        val bound = idOf("其它")
        val now = g.time.now()
        // 本段 60 分钟:段首起 40 分钟归 A(切走后未切回),之后 20 分钟归 B,当前绑定 B
        g.engine.restore(
            snap(taskId = bound).copy(
                sessionStartWall = now - 60 * 60_000L,
                taskCuts = "${now - 20 * 60_000L},$head,$bound",
            ),
        )
        assertNear(20 * 60_000L, v.inFlightMillis(bound))
        v.onDeleteRequest(bound); pump()
        assertEquals(20L, v.deletePrompt.value!!.minutes) // 只含切点之后那段
    }

    @Test fun inFlightStopsAtPauseAndSkipsLongPauseWindows() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        val now = g.time.now()
        // 段起点 60 分钟前,其中 20 分钟前~10 分钟前暂停了 10 分钟:有效工作 50 分钟
        g.engine.restore(
            snap(taskId = id).copy(
                sessionStartWall = now - 60 * 60_000L,
                pauseGaps = "${now - 20 * 60_000L},${now - 10 * 60_000L}",
            ),
        )
        assertNear(50 * 60_000L, v.inFlightMillis(id))

        // 进行中的暂停:工作只算到暂停起点(5 分钟前开始暂停 -> 55 分钟)
        g.engine.restore(
            snap(taskId = id).copy(
                status = EngineStatus.PAUSED,
                sessionStartWall = now - 60 * 60_000L,
                pauseStartWall = now - 5 * 60_000L,
            ),
        )
        assertNear(55 * 60_000L, v.inFlightMillis(id))
    }

    @Test fun inFlightIsIgnoredWhenNotBoundTaskOrOutsideWorkPhase() = runTest {
        val v = vm()
        v.onCreate("写周报"); v.onCreate("其它"); pump()
        val id = idOf("写周报")
        val now = g.time.now()
        val win = now - 40 * 60_000L

        g.engine.restore(snap(taskId = idOf("其它")).copy(sessionStartWall = win))
        assertEquals(0L, v.inFlightMillis(id)) // 绑定的是别的任务

        g.engine.restore(snap(taskId = id).copy(phase = Phase.REST, sessionStartWall = win))
        assertEquals(0L, v.inFlightMillis(id)) // 休息段不落时间账

        g.engine.restore(snap(taskId = null).copy(sessionStartWall = win))
        assertEquals(0L, v.inFlightMillis(id)) // 未绑定

        g.engine.restore(null)
        assertEquals(0L, v.inFlightMillis(id)) // 无快照(IDLE)
    }
}
