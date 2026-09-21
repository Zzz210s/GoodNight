package com.goodnight.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconcilerTest {
    private fun snap(status: EngineStatus, endElapsed: Long) = RuntimeSnapshot(
        profileId = 1, workMillis = 1, restMillis = 1, phase = Phase.WORK, status = status,
        cycleCount = 0, startElapsed = 0, endElapsed = endElapsed, endWall = 0,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    @Test fun nullStops() = assertEquals(ReconcileAction.STOP_SELF, Reconciler.decide(null, 0))
    @Test fun runningExpiredFinishes() = assertEquals(ReconcileAction.FINISH_EXPIRED, Reconciler.decide(snap(EngineStatus.RUNNING, 100), 150))
    @Test fun runningActiveResumes() = assertEquals(ReconcileAction.RESUME_ACTIVE, Reconciler.decide(snap(EngineStatus.RUNNING, 100), 50))
    @Test fun pausedShows() = assertEquals(ReconcileAction.SHOW_PAUSED, Reconciler.decide(snap(EngineStatus.PAUSED, 100), 150))
}
