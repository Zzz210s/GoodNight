package com.goodnight.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class StateRestorerTest {
    private fun snap(status: EngineStatus) = RuntimeSnapshot(
        profileId = 1, workMillis = 100_000, restMillis = 40_000, phase = Phase.WORK, status = status,
        cycleCount = 2, startElapsed = 10_000, endElapsed = 110_000, endWall = 2_000_000,
        timeSpentPaused = 5_000, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 1_950_000, savedAtElapsed = 60_000, ckptDate = "2026-08-31", ckptAccum = 50_000,
    )

    @Test fun noRebootReturnsSame() {
        assertEquals(snap(EngineStatus.RUNNING), StateRestorer.afterBoot(snap(EngineStatus.RUNNING), nowWall = 1_960_000, nowElapsed = 70_000))
    }

    @Test fun rebootRunningShiftsByWallClock() {
        val s = StateRestorer.afterBoot(snap(EngineStatus.RUNNING), nowWall = 1_980_000, nowElapsed = 1_000)
        // 剩余墙钟 = endWall - nowWall = 20_000
        assertEquals(1_000 + 20_000L, s.endElapsed)
        assertEquals(2, s.cycleCount)
        assertEquals(50_000L, s.ckptAccum) // 游标保守不动,避免重复计数
    }

    @Test fun rebootPausedStaysFrozen() {
        val s = snap(EngineStatus.PAUSED).copy(timeAtPause = 20_000, lastPauseTime = 60_000)
        val r = StateRestorer.afterBoot(s, nowWall = 1_980_000, nowElapsed = 1_000)
        assertEquals(EngineStatus.PAUSED, r.status)
        assertEquals(20_000L, r.timeAtPause)
    }

    @Test fun rebootExpiredWhileOffGivesNegativeRemaining() {
        val s = StateRestorer.afterBoot(snap(EngineStatus.RUNNING), nowWall = 2_500_000, nowElapsed = 1_000)
        // endElapsed < nowElapsed? 不:剩余为负,Reconciler/onExpired 负责结算
        org.junit.Assert.assertTrue(s.endElapsed - 1_000 < 0)
    }

    @Test fun secondRebootAfterReAnchorStillDetected() {
        // Task 8 resume() 重锚后 startElapsed = elapsed - frozenAccrued 可为负
        // (-38_000 = 22_000 - 60_000),旧守卫 nowElapsed >= startElapsed 恒真,二次重启被漏判。
        // savedAtElapsed=60_000 是本次开机最近一次持久化时的单调钟,二次开机后 nowElapsed=1_000 更小
        val s = snap(EngineStatus.RUNNING).copy(startElapsed = -38_000, savedAtElapsed = 60_000)
        val r = StateRestorer.afterBoot(s, nowWall = 1_980_000, nowElapsed = 1_000)
        // 剩余墙钟 = endWall(2_000_000) - nowWall(1_980_000) = 20_000
        assertEquals(1_000 + 20_000L, r.endElapsed)
        // span = 110_000 - (-38_000) = 148_000,新锚点 = 21_000 - 148_000
        assertEquals(-127_000L, r.startElapsed)
        assertEquals(1_000L, r.savedAtElapsed)
    }
}
