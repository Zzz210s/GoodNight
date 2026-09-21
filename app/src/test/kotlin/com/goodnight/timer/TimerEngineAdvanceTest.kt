package com.goodnight.timer

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerEngineAdvanceTest {
    @Test fun workExpirySwitchesToRest() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 100_000
        e.onExpired()
        val s = e.snapshot.value!!
        assertEquals(Phase.REST, s.phase)
        assertEquals(0, s.cycleCount)
        assertEquals(t.el + 40_000L, s.endElapsed)
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(Phase.WORK, fin.finished)
        assertEquals(Phase.REST, fin.next)
        assertEquals(100_000L, fin.settleMillis)
        assertTrue(fin.auto)
    }

    @Test fun restExpirySwitchesToWorkAndIncrementsCycle() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 100_000; e.onExpired() // -> REST
        t.el += 40_000; e.onExpired()  // -> WORK, cycle 1
        val s = e.snapshot.value!!
        assertEquals(Phase.WORK, s.phase)
        assertEquals(1, s.cycleCount)
        assertEquals(t.el + 100_000L, s.endElapsed)
        assertTrue(seen.last() is EngineEvent.PhaseStarted)
    }

    @Test fun onExpiredBeforeDeadlineIsNoop() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 40_000L)
        t.el += 99_999
        e.onExpired()
        assertEquals(Phase.WORK, e.snapshot.value!!.phase)
    }

    @Test fun settleMillisDeductsCheckpointCursor() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 60_000
        e.onCheckpointFlushed("2026-08-31", 60_000L)
        t.el += 40_000
        e.onExpired()
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(40_000L, fin.settleMillis)
    }

    @Test fun skipFromRunningRestAdvancesCycleImmediately() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 100_000; e.onExpired() // REST
        t.el += 5_000
        e.skip() // 跳过休息
        val s = e.snapshot.value!!
        assertEquals(Phase.WORK, s.phase)
        assertEquals(1, s.cycleCount)
        // 此前 onExpired 已产生一条 PhaseFinished(WORK),skip 的这条用 last() 取
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>().last()
        assertEquals(Phase.REST, fin.finished)
        assertEquals(0L, fin.settleMillis)
    }

    @Test fun skipFromRunningWorkSettlesAccrued() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 30_000
        e.skip()
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(30_000L, fin.settleMillis)
        assertEquals(Phase.REST, e.snapshot.value!!.phase)
    }

    @Test fun resetClearsSnapshotAndSettles() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 70_000
        e.reset()
        advanceUntilIdle()
        assertNull(e.snapshot.value)
        val ev = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(70_000L, ev.settleMillis)
        assertEquals(1L, ev.profileId)
        assertNull(saved.last())
    }

    @Test fun restartPhaseOnlyWhenPausedReopensFullDuration() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 50_000
        e.pause()
        e.restartPhase(2, 200_000L, 80_000L)
        val s = e.snapshot.value!!
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(Phase.WORK, s.phase)
        assertEquals(2L, s.profileId)
        assertEquals(200_000L, s.workMillis)
        assertEquals(t.el + 200_000L, s.endElapsed)
        assertEquals(0L, s.ckptAccum)
        val ev = seen.filterIsInstance<EngineEvent.PhaseRestarted>().single()
        assertEquals(50_000L, ev.settleMillis)
        assertEquals(1L, ev.profileId) // 结算归属旧 profile
    }

    /** Fix Round 1(Concern 3): restartPhase 换 profile 时,已累计工作量的结算必须归属旧 profile */
    @Test fun restartPhaseWithDifferentProfileEmitsOldProfileId() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L) // 在 profile 1 下累计工作
        t.el += 50_000
        e.pause()
        e.restartPhase(2, 200_000L, 80_000L) // 用 profile 2 重开同一阶段
        val ev = seen.filterIsInstance<EngineEvent.PhaseRestarted>().single()
        assertEquals(1L, ev.profileId) // 结算归属旧 profile(重启前快照的 profileId)
    }

    /** Fix Round 2(F2): PhaseFinished 自带 profileId —— 结算归属完成推进前的快照,
     *  与后继快照解耦(后继正常流复制同一 profileId,RESET 交错时快照已空) */
    @Test fun phaseFinishedCarriesPreAdvanceProfileId() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(7, 100_000L, 40_000L) // 在 profile 7 下跑完工作阶段
        t.el += 100_000
        e.onExpired()
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(7L, fin.profileId)
        assertEquals(7L, e.snapshot.value!!.profileId) // 后继复制同一 profile(正常流快照仍可佐证)
    }

    @Test fun restartPhaseIgnoredWhileRunning() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 40_000L)
        e.restartPhase(2, 200_000L, 80_000L) // RUNNING,忽略
        assertEquals(1L, e.snapshot.value!!.profileId)
        assertEquals(100_000L, e.snapshot.value!!.workMillis)
    }

    @Test fun expiredWhileDeadSettlesFullWork() = runTest {
        // 进程死亡期间工作阶段已到期:恢复后 onExpired 用 endElapsed 结算
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e1 = testEngine(t, saved)
        e1.restore(null)
        e1.start(1, 100_000L, 40_000L)
        t.el += 60_000
        e1.onCheckpointFlushed("2026-08-31", 60_000L)
        val persisted = saved.last { it != null }!!
        // 进程死后很久,elapsed 继续走
        t.el += 500_000
        val e2 = testEngine(t, saved)
        e2.restore(persisted)
        val seen = recordEvents(e2)
        e2.onExpired()
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals(40_000L, fin.settleMillis) // clamp 到 workMillis,扣游标 60k -> 40k
    }
}
