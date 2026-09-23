package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1 首次绑定(案 B「首次绑定即定义整段」):start() 不接收任务,任务 id 要等异步读库后才 setTask。
 * 这类「给本段定任务」的绑定不是段内切换 —— 只改快照、不落切点、不发边界事件,且**与 RUNNING/PAUSED
 * 无关**(两条路径必须一致),选中的任务适用于整段(含选任务之前已工作的那段时间)。
 * 否则切点会落进 (sessionStartWall, end) 开区间,下游 buildSessionRows 多产出一段归属为 null 的行。
 * 段首归属由段首 taskId 与段内切点表共同表达:段内有切点时,段首 = 最早切点的 fromTaskId。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineTaskHeadBindTest {
    private fun switches(seen: List<EngineEvent>) = seen.filterIsInstance<EngineEvent.TaskSwitched>()

    /** start() 与 setTask() 相隔 1ms(任务 id 异步读库后才下发):不发边界、不落切点、快照已绑定 */
    @Test fun oneMillisecondAfterStartEmitsNoBoundary() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        val seen = recordEvents(e)
        t.el += 1; t.nowMs += 1
        e.setTask(7L)
        advanceUntilIdle()
        assertTrue("段首绑定不得发边界事件,实际 $seen", switches(seen).isEmpty())
        assertEquals(7L, e.snapshot.value!!.taskId)
        assertTrue(
            "段首绑定不落切点(整段归 7,由结算事件 taskId 承载)",
            e.snapshot.value!!.taskCutPoints().isEmpty(),
        )
        assertEquals(7L, saved.last()!!.taskId)
    }

    /** 同一毫秒会话起点绑定(sessionStartWall == atWall)同样是段首绑定,当然也不切 */
    @Test fun sameMillisecondHeadBindEmitsNoBoundary() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        val seen = recordEvents(e)
        e.setTask(7L)
        assertTrue("段首绑定不得发边界事件,实际 $seen", switches(seen).isEmpty())
        assertTrue(e.snapshot.value!!.taskCutPoints().isEmpty())
    }

    /**
     * 暂停中首次绑定与 RUNNING 下首次绑定行为一致:不切段、不发边界、不落切点,
     * 快照直接绑定 —— 该任务定义整段(含暂停前已工作的那段时间),故整段只归 7。
     */
    @Test fun pausedHeadBindDefinesWholeSegment() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)          // wall 1_000_000 / el 10_000
        t.el += 20_000; t.nowMs += 20_000     // 先跑 20s
        val pauseStart = t.nowMs              // 1_020_000
        e.pause()
        t.el += 5_000; t.nowMs += 5_000       // 暂停中 1_025_000
        val seen = recordEvents(e)
        e.setTask(7L)
        advanceUntilIdle()
        assertTrue("暂停中首次绑定不得发边界事件,实际 $seen", switches(seen).isEmpty())
        assertEquals(7L, e.snapshot.value!!.taskId)
        assertTrue("首次绑定不落切点:整段归 7", e.snapshot.value!!.taskCutPoints().isEmpty())
        assertEquals(7L, saved.last()!!.taskId)
        assertEquals("绑定不得改写暂停起点", pauseStart, e.snapshot.value!!.pauseStartWall)
        // 恢复后收尾:结算事件带 7 且切点表为空,即整段(含暂停前 20s)未被切成两段
        t.el += 180_000; t.nowMs += 180_000
        e.resume()
        t.el += 60_000; t.nowMs += 60_000
        e.reset()
        val rs = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals(7L, rs.taskId)
        assertEquals("", rs.taskCuts)
    }

    /** 段首绑定的任务成为段内首个切点的 fromTaskId —— Task 5 据此复原段首归属 */
    @Test fun laterSwitchCutsFromHeadBoundTask() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(5L) // 段首绑定
        t.el += 10_000; t.nowMs += 10_000
        val seen = recordEvents(e)
        e.setTask(7L)
        val cutWall = t.nowMs
        assertEquals(5L, switches(seen).single().fromTaskId)
        assertEquals(listOf(Triple(cutWall, 5L, 7L)), e.snapshot.value!!.taskCutPoints())
    }

    /** 段首绑定后正计时同样切段:正计时与倒计时的切段语义一致 */
    @Test fun countUpSwitchAfterHeadBindEmitsBoundary() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L, countUp = true)
        e.setTask(5L) // 段首绑定:只改快照
        t.el += 10_000; t.nowMs += 10_000
        val seen = recordEvents(e)
        e.setTask(7L)
        val ev = switches(seen).single()
        assertEquals(1_010_000L, ev.atWall)
        assertEquals(5L, ev.fromTaskId)
        assertEquals(7L, e.snapshot.value!!.taskId)
    }
}
