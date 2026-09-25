package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 Critical 回归:**暂停中结算的窗口终点必须停在暂停起点**。
 *
 * 旧 `workWindow` 的终点恒为当前墙钟,而进行中的暂停此刻只存在 `pauseStartWall`(要到 `resume()`
 * 才并入 `pauseGaps`),于是 PAUSED 下的 `Reset(sessionStart, now, gaps=已完成空档)` 被
 * EventApplier 整段按窗口落库 —— 暂停那段被记成工作。四个结算点(换时钟 reset / 终止 stop /
 * 跳过 skip / 重启相位 restartPhase)共用 `workWindow`,故逐点钉住。
 *
 * 场景统一为:工作 30 分钟 -> 暂停 -> 暂停中再等 30 分钟 -> 结算。
 * 正确账:结算 30 分钟,窗口 [1_000_000, 2_800_000](暂停起点),进行中的暂停不并入 pauseWindows。
 * 旧行为:窗口 [1_000_000, 4_600_000] -> 落库 60 分钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnginePausedSettleWindowTest {

    /** 工作 30 分钟(wall 1_000_000 -> 2_800_000)后暂停,再暂停 30 分钟(-> 4_600_000) */
    private suspend fun workThenPause(): Pair<FakeTime, TimerEngine> {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 3_600_000L, 300_000L)
        t.el += 1_800_000; t.nowMs += 1_800_000
        e.pause()
        t.el += 1_800_000; t.nowMs += 1_800_000
        return t to e
    }

    /** 换时钟与终止走的是同一个 [TimerEngine.reset](服务层分别发 SWITCH / STOP 命令) */
    @Test fun pausedResetSettlesWorkOnlyUpToPauseStart() = runTest {
        val (_, e) = workThenPause()
        val seen = recordEvents(e)

        e.reset()

        val r = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals("起点 = 本段开始", 1_000_000L, r.sessionStartWall)
        assertEquals("终点 = 暂停起点,不含暂停中的 30 分钟", 2_800_000L, r.sessionEndWall)
        assertEquals("结算增量 = 暂停前那段工作", 1_800_000L, r.settleMillis)
        assertTrue("窗口已止于暂停起点,进行中的暂停不必再当空档剔除", r.pauseWindows.isEmpty())
    }

    @Test fun pausedSkipSettlesWorkOnlyUpToPauseStart() = runTest {
        val (_, e) = workThenPause()
        val seen = recordEvents(e)

        e.skip()

        val pf = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(Phase.WORK, pf.finished)
        assertEquals(1_000_000L, pf.sessionStartWall)
        assertEquals("终点 = 暂停起点", 2_800_000L, pf.sessionEndWall)
        assertEquals(1_800_000L, pf.settleMillis)
        assertTrue(pf.pauseWindows.isEmpty())
    }

    @Test fun pausedRestartPhaseSettlesWorkOnlyUpToPauseStart() = runTest {
        val (_, e) = workThenPause()
        val seen = recordEvents(e)

        e.restartPhase(2L, 3_600_000L, 300_000L)

        val pr = seen.filterIsInstance<EngineEvent.PhaseRestarted>().single()
        assertEquals(1_000_000L, pr.sessionStartWall)
        assertEquals("终点 = 暂停起点", 2_800_000L, pr.sessionEndWall)
        assertEquals(1_800_000L, pr.settleMillis)
        assertTrue(pr.pauseWindows.isEmpty())
    }

    /** 不得回退:续跑(RUNNING)后终点仍为当前墙钟,已完成的暂停仍随事件带出 */
    @Test fun resumeThenResetStillEndsAtCurrentWall() = runTest {
        val (t, e) = workThenPause()
        e.resume() // wall 4_600_000:暂停 [2_800_000, 4_600_000] 并入 pauseGaps
        t.el += 600_000; t.nowMs += 600_000
        val seen = recordEvents(e)

        e.reset()

        val r = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(1_000_000L, r.sessionStartWall)
        assertEquals("续跑后终点回到当前墙钟", 5_200_000L, r.sessionEndWall)
        assertEquals(1, r.pauseWindows.size)
        assertEquals(2_800_000L, r.pauseWindows[0][0])
        assertEquals(4_600_000L, r.pauseWindows[0][1])
        assertEquals("暂停 30 分钟不计工作,续跑 10 分钟计入", 1_800_000L + 600_000L, r.settleMillis)
    }
}
