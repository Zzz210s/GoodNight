package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6 / #10 正计时(countUp)引擎语义。硬契约:
 * - 启动即 WORK RUNNING,永不到期:onExpired 不推进不结算
 * - skip() 为 no-op:不结算、不推进、不发事件
 * - pause() 存“暂停时已走时长”(accrued,非剩余);resume() 后 elapsed 连续(暂停不计)
 * - accruedWork 对 countUp 不封顶于 workMillis(可无限累计)
 * - stop(reset) 按既有路径结算已走时长(可超 workMillis),扣 checkpoint 游标
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineCountUpTest {
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

    /** countUp 启动后永不到期:onExpired(远超 work 时长)不推进、不结算、不发事件 */
    @Test fun startCountUpIgnoresExpiry() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(9, 100_000L, 40_000L, countUp = true)
        var s = e.snapshot.value!!
        assertEquals(Phase.WORK, s.phase)
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(0, s.cycleCount)
        assertTrue(s.countUp)
        assertEquals(0L, s.remaining(t.el)) // 正计时无“剩余”概念
        t.el += 1_000_000 // 10 倍 work 时长,远超原定 endElapsed
        e.onExpired()
        s = e.snapshot.value!!
        assertEquals(Phase.WORK, s.phase)
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(0, s.cycleCount)
        assertEquals(1_000_000L, s.accruedWork(t.el)) // 不封顶,超 workMillis 仍全额累计
        val fin = seen.filterIsInstance<EngineEvent.PhaseFinished>()
        assertTrue("countUp 不得有 PhaseFinished,实际 $fin", fin.isEmpty())
        assertEquals(1, seen.filterIsInstance<EngineEvent.PhaseStarted>().size) // 仅启动那一次
    }

    /** countUp skip 为 no-op:RUNNING 与 PAUSED 下快照原样、无任何事件 */
    @Test fun skipIsNoopInCountUp() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 100_000L, 40_000L, countUp = true)
        t.el += 30_000
        val running = e.snapshot.value!!
        e.skip()
        assertEquals("RUNNING skip 后快照原样", running, e.snapshot.value)
        e.pause()
        val paused = e.snapshot.value!!
        e.skip()
        assertEquals("PAUSED skip 后快照原样", paused, e.snapshot.value)
        assertTrue(seen.none { it is EngineEvent.PhaseFinished })
        assertTrue(seen.none { it is EngineEvent.Reset })
        assertEquals(1, seen.filterIsInstance<EngineEvent.PhaseStarted>().size)
    }

    /**
     * pause 存“暂停时已走时长”而非剩余;resume 后 elapsed 连续增长且暂停不计;
     * accruedWork 越过 workMillis 不封顶;二次暂停同样存 accrued。
     */
    @Test fun pauseResumeKeepsElapsedContinuousAndUnbounded() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 60_000L, 20_000L, countUp = true) // endElapsed 仅 70_000,倒计时早已到期
        t.el += 45_000
        e.pause()
        val p = e.snapshot.value!!
        assertEquals(EngineStatus.PAUSED, p.status)
        assertEquals("timeAtPause 应为已走 45s,而非剩余 15s", 45_000L, p.timeAtPause)
        assertEquals(EngineEvent.Paused(45_000L), seen.last())
        t.el += 2_000_000 // 暂停 2000s:不计入 elapsed
        e.resume()
        var s = e.snapshot.value!!
        assertEquals(EngineStatus.RUNNING, s.status)
        assertEquals(45_000L, s.accruedWork(t.el)) // 恢复瞬间从冻结值继续
        t.el += 30_000
        s = e.snapshot.value!!
        assertEquals(75_000L, s.accruedWork(t.el)) // > workMillis 60s:无封顶;暂停 2000s 被排除
        assertEquals(0L, s.remaining(t.el))
        // 越过后端 endElapsed 很久,onExpired 仍不推进
        e.onExpired()
        assertEquals(Phase.WORK, e.snapshot.value!!.phase)
        e.pause() // 二次暂停:仍存已走时长
        assertEquals(75_000L, e.snapshot.value!!.timeAtPause)
        assertEquals(EngineEvent.Paused(75_000L), seen.last())
    }

    /** stop(reset) 结算全额 elapsed(远超 workMillis 也不封顶) */
    @Test fun stopSettlesUnboundedAccrued() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 60_000L, 20_000L, countUp = true)
        t.el += 2_000_000
        e.reset()
        assertNull(e.snapshot.value)
        val ev = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(2_000_000L, ev.settleMillis) // 2 000s 全额,远超 workMillis 60s
        assertEquals(1L, ev.profileId)
    }

    /** stop 结算仍扣 checkpoint 游标(与倒计时一致,只是基数不封顶) */
    @Test fun stopDeductsCheckpointCursor() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 60_000L, 20_000L, countUp = true)
        t.el += 2_000_000
        e.onCheckpointFlushed("2026-09-04", 2_000_000L)
        t.el += 25_000
        e.reset()
        val ev = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(25_000L, ev.settleMillis) // accrued 2 025s - 已 flush 2 000s
    }

    /** PAUSED 下 stop:结算暂停时刻的 accrued(不含暂停时长),与倒计时路径同构 */
    @Test fun stopWhilePausedSettlesAccruedAtPause() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        val seen = recordEvents(e)
        e.start(1, 60_000L, 20_000L, countUp = true)
        t.el += 45_000
        e.pause()
        t.el += 500_000
        e.reset()
        val ev = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(45_000L, ev.settleMillis) // 暂停 500s 不计
    }

    /** 重启换算:countUp RUNNING 快照 span 恒=workMillis,既有 StateRestorer 重锚即得
     *  accrued 连续(关机时长计入,与倒计时到期语义同源);无 countUp 专属分支也须正确 */
    @Test fun rebootReanchorsCountUpWithoutLosingAccrued() = runTest {
        val t = FT()
        val e = engine(t, mutableListOf())
        e.restore(null)
        e.start(1, 60_000L, 20_000L, countUp = true) // wall=1_000_000, el=10_000
        t.el += 45_000; t.nowMs += 45_000            // accrued 45s
        t.nowMs += 120_000; t.el = 5_000             // 关机 120s 后重启,单调钟清零
        e.adoptRestored(StateRestorer.afterBoot(e.snapshot.value!!, t.nowMs, t.el))
        assertEquals(165_000L, e.snapshot.value!!.accruedWork(t.el)) // 45s 冻结 + 120s 关机
        t.el += 10_000
        assertEquals(175_000L, e.snapshot.value!!.accruedWork(t.el)) // 重启后续走
        assertEquals(Phase.WORK, e.snapshot.value!!.phase) // 永不到期
    }

    @Test fun persistCallbacksCarryCountUpFlag() = runTest {
        val t = FT()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = engine(t, saved)
        e.restore(null)
        e.start(3, 60_000L, 20_000L, countUp = true)
        advanceUntilIdle()
        assertTrue(saved.last()!!.countUp)
    }
}
