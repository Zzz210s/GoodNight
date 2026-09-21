package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7 / #10 restartPhase 模式跟随:countUp 由调用方按目标配置显式传入(暂停中改模式/
 * 换配置后重开须以新模式继续);正计时无休息相 → 强制 WORK。缺省 false 保持倒计时既有语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineCountUpRestartTest {
    private class FT(var nowMs: Long = 1_000_000L, var el: Long = 10_000L) : TimeProvider {
        override fun now() = nowMs
        override fun elapsedRealtime() = el
    }

    private fun engine(t: FT, saved: MutableList<RuntimeSnapshot?> = mutableListOf()) =
        TimerEngine(t, TestScope(UnconfinedTestDispatcher()), persist = { saved += it })

    private fun recordEvents(e: TimerEngine): MutableList<EngineEvent> {
        val seen = mutableListOf<EngineEvent>()
        TestScope(UnconfinedTestDispatcher()).launch { e.events.collect { seen += it } }
        return seen
    }

    /** 暂停中的倒计时会话编辑为正计时后重开:会话变 countUp(phase 恒 WORK),结算仍归旧 profile */
    @Test fun restartIntoCountUpForcesWorkAndKeepsSettleOwnership() = runTest {
        val t = FT()
        val e = engine(t)
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L)
        t.el += 50_000
        e.pause() // 倒计时暂停:剩余 50s
        e.restartPhase(2, 200_000L, 80_000L, countUp = true) // 编辑模式切正计时后重开
        val s = e.snapshot.value!!
        assertTrue("重开后必须为正计时", s.countUp)
        assertEquals(Phase.WORK, s.phase)
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(2L, s.profileId)
        assertEquals(0L, s.remaining(t.el)) // 正计时无剩余
        assertEquals(200_000L, s.workMillis)
        assertEquals(t.el + 200_000L, s.endElapsed)
        val ev = seen.filterIsInstance<EngineEvent.PhaseRestarted>().single()
        assertEquals(50_000L, ev.settleMillis) // 倒计时暂停 50s 已走量结算
        assertEquals(1L, ev.profileId) // 归属旧 profile
    }

    /** countdown 缺省重启:行为与既有 3 参调用逐字节一致(回归网锚点) */
    @Test fun restartDefaultKeepsCountdownSemantics() = runTest {
        val t = FT()
        val e = engine(t)
        e.restore(null)
        e.start(1, 100_000L, 40_000L)
        t.el += 50_000
        e.pause()
        e.restartPhase(2, 200_000L, 80_000L) // 3 参调用:countUp = false
        val s = e.snapshot.value!!
        assertTrue(!s.countUp)
        assertEquals(Phase.WORK, s.phase) // 倒计时:保留被暂停阶段
        assertEquals(t.el + 200_000L, s.endElapsed)
    }

    /** 暂停中的正计时会话(phase 恒 WORK)重开仍为正计时:不因缺省/显式 false 意外降级倒计时 */
    @Test fun restartCountUpSessionKeepsCountUpWhenExplicit() = runTest {
        val t = FT()
        val e = engine(t)
        e.restore(null)
        e.start(1, 100_000L, 40_000L, countUp = true)
        t.el += 45_000
        e.pause()
        e.restartPhase(1, 200_000L, 80_000L, countUp = true)
        val s = e.snapshot.value!!
        assertTrue(s.countUp)
        assertEquals(Phase.WORK, s.phase)
        assertEquals(0L, s.remaining(t.el))
    }
}
