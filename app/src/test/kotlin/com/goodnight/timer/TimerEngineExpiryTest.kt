package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4 到期推进的引擎级回归:三重兜底(ticker/闹钟对账/进程死亡对账)会串行调用
 * onExpired,本组测试钉住"到期必恰好推进一次,重复/迟到调用一律被门控挡下"。
 * 门控:status != RUNNING 或 el < endElapsed 均为 no-op;推进后新阶段 endElapsed
 * 在未来,同刻二次调用天然被挡(引擎自身幂等,服务侧自愈是另一道防线)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineExpiryTest {
    private class FT(var nowMs: Long = 1_000_000L, var el: Long = 10_000L) : TimeProvider {
        override fun now() = nowMs
        override fun elapsedRealtime() = el
    }

    private fun engine(t: FT, saved: MutableList<RuntimeSnapshot?>) =
        TimerEngine(t, TestScope(UnconfinedTestDispatcher()), persist = { saved += it })

    private fun recordEvents(e: TimerEngine): MutableList<EngineEvent> {
        val seen = mutableListOf<EngineEvent>()
        TestScope(UnconfinedTestDispatcher()).launch { e.events.collect { seen += it } }
        return seen
    }

    /** ticker 与闹钟/对账在到期同刻串行双发:只允许一次推进,不连环、不重复 emit */
    @Test fun repeatedOnExpiredAtDeadlineAdvancesExactlyOnce() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 100_000
        e.onExpired() // ticker 或闹钟对账先到
        e.onExpired() // 另一条腿串行后到:必须被新 endElapsed 门控挡下
        val s = e.snapshot.value!!
        assertEquals(Phase.REST, s.phase)
        assertEquals(0, s.cycleCount)
        assertEquals(t.el + 40_000L, s.endElapsed)
        assertEquals(1, seen.filterIsInstance<EngineEvent.PhaseFinished>().size) // 只推进一次
        assertEquals(2, seen.filterIsInstance<EngineEvent.PhaseStarted>().size)  // start 1 次 + 推进后 1 次
    }

    /** 暂停跨过原定截止时刻后仍不推进:到期推进只属于 RUNNING,恢复后由新锚点重新计时 */
    @Test fun onExpiredIgnoredWhilePausedEvenPastDeadline() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 40_000
        e.pause()
        val paused = e.snapshot.value!!
        t.el += 500_000 // 远超原定截止(110_000)
        e.onExpired()
        assertEquals(paused, e.snapshot.value) // 快照原样,不推进
        assertTrue(seen.none { it is EngineEvent.PhaseFinished })
    }

    /** reset 后快照为 null:迟到 onExpired(null 快照门控)必须 no-op,不复活不崩 */
    @Test fun onExpiredNoopAfterReset() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        e.start(1, 100_000L, 40_000L)
        t.el += 150_000
        e.reset()
        assertNull(e.snapshot.value)
        e.onExpired() // 旧 ticker 迭代迟到:null 门控挡下
        assertNull(e.snapshot.value)
    }
}
