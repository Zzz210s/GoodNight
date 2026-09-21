package com.goodnight.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckpointerTest {
    private fun snap(phase: Phase, status: EngineStatus, lastPause: Long = 0) = RuntimeSnapshot(
        profileId = 1, workMillis = 100_000, restMillis = 40_000, phase = phase, status = status,
        cycleCount = 0, startElapsed = 0, endElapsed = 100_000, endWall = 1_000_000,
        timeSpentPaused = 0, lastPauseTime = lastPause, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    @Test fun runningWorkYieldsDelta() {
        val f = Checkpointer.compute(snap(Phase.WORK, EngineStatus.RUNNING), nowElapsed = 70_000, today = "2026-08-31")
        assertEquals(70_000L, f.deltaMillis)
        assertEquals(70_000L, f.newAccum)
        assertEquals("2026-08-31", f.date)
    }
    @Test fun deltaDeductsCursor() {
        val s = snap(Phase.WORK, EngineStatus.RUNNING).copy(ckptAccum = 60_000, ckptDate = "2026-08-31")
        val f = Checkpointer.compute(s, nowElapsed = 70_000, today = "2026-08-31")
        assertEquals(10_000L, f.deltaMillis)
    }
    @Test fun restPhaseYieldsZero() {
        val f = Checkpointer.compute(snap(Phase.REST, EngineStatus.RUNNING), 50_000, "2026-08-31")
        assertEquals(0L, f.deltaMillis)
    }
    @Test fun pausedYieldsZeroAndKeepsCursor() {
        val s = snap(Phase.WORK, EngineStatus.PAUSED, lastPause = 30_000).copy(ckptAccum = 30_000)
        val f = Checkpointer.compute(s, 999_999, "2026-08-31")
        assertEquals(0L, f.deltaMillis)
        assertEquals(30_000L, f.newAccum)
    }
    @Test fun crossMidnightAttributesToToday() {
        // 昨天 23:59 开始,今天 00:01 checkpoint:增量全归今天(60 秒误差已接受)
        // accruedWork clamp 到 workMillis=100_000
        val s = snap(Phase.WORK, EngineStatus.RUNNING).copy(startElapsed = 0, ckptAccum = 0)
        val f = Checkpointer.compute(s, nowElapsed = 120_000, today = "2026-09-01")
        assertEquals("2026-09-01", f.date)
        assertEquals(100_000L, f.deltaMillis)
    }
}
