package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1 任务绑定:引擎携带当前任务,工作段内切换任务发出段边界事件(供服务层按 atWall 切段)。
 * 契约:任何状态都更新快照(随运行态持久化);首次绑定(本段尚无任务历史,不论 RUNNING/PAUSED)
 * 与休息段不发事件;已有任务历史后切换,WORK + RUNNING 用当前墙钟、WORK + PAUSED 用暂停起点发 TaskSwitched;
 * 结算类事件(自动完成/跳过/终止/重启)自带该段的 taskId 与本段切点表 taskCuts。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineTaskTest {
    private fun switches(seen: List<EngineEvent>) = seen.filterIsInstance<EngineEvent.TaskSwitched>()

    /** WORK+RUNNING 切换任务:边界事件 atWall = 当前墙钟,from/to 为旧新值,快照与落库同步更新 */
    @Test fun switchingWhileWorkRunningEmitsBoundaryAtWallClock() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(5L) // 段首绑定:只改快照,不发事件
        t.el += 10_000; t.nowMs += 10_000 // 墙钟 1_010_000
        val seen = recordEvents(e)
        e.setTask(7L)
        advanceUntilIdle()
        val ev = switches(seen).single()
        assertEquals(1_010_000L, ev.atWall)
        assertEquals(5L, ev.fromTaskId)
        assertEquals(7L, ev.toTaskId)
        assertEquals(7L, e.snapshot.value!!.taskId)
        assertEquals(7L, saved.last()!!.taskId)
    }

    /** 解绑(切到 null)同样是段边界:旧段带旧任务,新段未绑定 */
    @Test fun switchingToNullEmitsBoundaryWithPreviousTask() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(7L)
        val seen = recordEvents(e)
        t.nowMs += 5_000
        e.setTask(null)
        val ev = switches(seen).single()
        assertEquals(t.nowMs, ev.atWall)
        assertEquals(7L, ev.fromTaskId)
        assertNull(ev.toTaskId)
        assertNull(e.snapshot.value!!.taskId)
    }

    /** 重复选择同一个任务不是切换:不发边界事件,避免把一段时间账切成同任务两份 */
    @Test fun sameTaskDoesNotEmitSpuriousBoundary() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(7L)
        val seen = recordEvents(e)
        e.setTask(7L)
        assertTrue("同值 setTask 不得发出段边界,实际 $seen", switches(seen).isEmpty())
        assertEquals(7L, e.snapshot.value!!.taskId)
    }

    /**
     * 已有任务历史后,暂停中切换到 B:发边界,时刻回退到暂停起点
     * (暂停前那段仍属旧任务,详见 TimerEngineTaskCutTest)。
     * 「本段首次绑定」的情形不在此列 —— 见 TimerEngineTaskHeadBindTest.pausedHeadBindDefinesWholeSegment。
     */
    @Test fun switchingWhilePausedEmitsBoundaryAtPauseStart() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(5L) // 段首绑定:本段已有任务历史
        t.el += 20_000; t.nowMs += 20_000
        val pauseStart = t.nowMs
        e.pause()
        val seen = recordEvents(e)
        t.el += 5_000; t.nowMs += 5_000
        e.setTask(9L)
        val ev = switches(seen).single()
        assertEquals(pauseStart, ev.atWall)
        assertEquals(5L, ev.fromTaskId)
        assertEquals(9L, e.snapshot.value!!.taskId)
    }

    /** 休息段中切换:只改快照;下一工作段由快照携带新任务 */
    @Test fun switchingDuringRestOnlyUpdatesSnapshot() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        t.el += 60_000; t.nowMs += 60_000
        e.onExpired() // 进入 REST
        assertEquals(Phase.REST, e.snapshot.value!!.phase)
        val seen = recordEvents(e)
        e.setTask(9L)
        assertTrue(switches(seen).isEmpty())
        assertEquals(9L, e.snapshot.value!!.taskId)
        // 休息结束回到 WORK:绑定继续生效
        t.el += 30_000; t.nowMs += 30_000
        e.onExpired()
        assertEquals(Phase.WORK, e.snapshot.value!!.phase)
        assertEquals(9L, e.snapshot.value!!.taskId)
    }

    /** 无快照(未开始 / 重置后 / 启动恢复为空)时 setTask 为 no-op,不复活快照 */
    @Test fun switchingWithoutSnapshotIsIgnored() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.setTask(7L)
        assertNull(e.snapshot.value)
        assertTrue(switches(seen).isEmpty())

        // 重置后(快照已清空)迟到的 setTask 不得复活快照
        val e2 = testEngine(t)
        e2.restore(null)
        e2.start(1, 60_000L, 30_000L)
        e2.setTask(7L)
        e2.reset()
        assertNull(e2.snapshot.value)
        e2.setTask(null)
        assertNull(e2.snapshot.value)
    }

    /** 工作段自动完成:事件自带该段 taskId(切换后新段归属新任务) */
    @Test fun workFinishedEventCarriesBoundTaskId() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        t.el += 10_000; t.nowMs += 10_000
        e.setTask(7L)
        val seen = recordEvents(e)
        t.el += 50_000; t.nowMs += 50_000
        e.onExpired()
        val pf = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(Phase.WORK, pf.finished)
        assertEquals(7L, pf.taskId)
    }

    /** 跳过/终止/重启:结算事件同样自带该段 taskId(快照事后已换值或已清空) */
    @Test fun skipResetAndRestartCarryBoundTaskId() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        t.el += 10_000; t.nowMs += 10_000
        e.setTask(7L)
        val seen = recordEvents(e)
        e.skip()
        val pf = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(7L, pf.taskId)

        e.setTask(9L) // REST 中换任务:只改快照
        e.reset()
        val rs = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(9L, rs.taskId)
    }

    /** 重启当前阶段(终止并重开)时,已结算段仍归属旧任务 */
    @Test fun restartPhaseCarriesBoundTaskId() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        t.el += 20_000; t.nowMs += 20_000
        e.setTask(7L)
        e.pause()
        val seen = recordEvents(e)
        e.restartPhase(1L, 60_000L, 30_000L)
        val pr = seen.filterIsInstance<EngineEvent.PhaseRestarted>().single()
        assertEquals(7L, pr.taskId)
        assertEquals(7L, e.snapshot.value!!.taskId)
    }
}
