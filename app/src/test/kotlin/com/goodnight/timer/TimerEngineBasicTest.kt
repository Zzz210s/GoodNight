package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineBasicTest {
    /** Fix Round 1 契约钉:replay=0 —— 无订阅者时发出的事件,订阅后不补发 */
    @Test fun eventsNotDeliveredToLateSubscriber() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 50_000L) // 无订阅者:PhaseStarted 被丢弃
        t.el += 40_000
        e.pause()                    // 同样无订阅者:Paused 被丢弃
        val seen = recordEvents(e)   // 之后才订阅
        advanceUntilIdle()
        assertTrue("replay=0: 迟订阅者不得收到订阅前的事件,实际收到 $seen", seen.isEmpty())
    }

    @Test fun startBeginsWorkPhaseCountdown() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(3, 25 * 60_000L, 5 * 60_000L)
        advanceUntilIdle()
        val s = e.snapshot.value!!
        assertEquals(Phase.WORK, s.phase)
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(0, s.cycleCount)
        assertEquals(t.el + 25 * 60_000L, s.endElapsed)
        assertEquals(t.nowMs + 25 * 60_000L, s.endWall)
        assertEquals(EngineEvent.PhaseStarted(Phase.WORK, t.el + 25 * 60_000L, t.nowMs + 25 * 60_000L), seen.last())
        assertEquals(s, saved.last())
    }

    @Test fun startIgnoredWhileActive() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 50_000L)
        e.start(2, 999_000L, 999_000L) // 忽略
        assertEquals(1L, e.snapshot.value!!.profileId)
        assertEquals(100_000L, e.snapshot.value!!.workMillis)
    }

    @Test fun pauseFreezesRemainingAndResumeShiftsEnd() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        e.start(1, 100_000L, 50_000L)
        val seen = recordEvents(e)
        t.el += 40_000
        e.pause()
        val p = e.snapshot.value!!
        assertEquals(EngineStatus.PAUSED, p.status)
        assertEquals(60_000L, p.timeAtPause)
        assertEquals(EngineEvent.Paused(60_000L), seen.last())
        t.el += 3_000_000 // 暂停很久
        e.resume()
        val r = e.snapshot.value!!
        assertEquals(EngineStatus.RUNNING, r.status)
        assertEquals(t.el + 60_000L, r.endElapsed)
        // resume 采用重锚方案:startElapsed 回退冻结 accrued,timeSpentPaused 归零;
        // 暂停时长改由锚点差隐式表达,公共不变量 accruedWork 保持连续
        assertEquals(0L, r.timeSpentPaused)
        assertEquals(40_000L, r.accruedWork(t.el))
        assertEquals(60_000L, r.remaining(t.el))
        assertTrue(seen.last() is EngineEvent.Resumed)
    }

    @Test fun pauseIgnoredWhenNotRunning() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.pause() // 无快照,忽略
        assertNull(e.snapshot.value)
    }

    @Test fun accruedWorkAccountsForPause() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 50_000L)
        t.el += 30_000
        e.pause()
        t.el += 500_000
        e.resume()
        t.el += 20_000
        val s = e.snapshot.value!!
        assertEquals(50_000L, s.accruedWork(t.el))
    }

    @Test fun readyGate() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        assertFalse(e.ready.value)
        e.restore(null)
        assertTrue(e.ready.value)
    }

}
