package com.goodnight.timer

import com.goodnight.data.RuntimeStateCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1 任务切点:一次连续工作只属一个任务,边界时刻 = **该次工作停止的时刻**。
 * 切点同时编进运行态快照(rt_task_cuts)—— 工作段跨重启存活,切点也必须存活,
 * 否则杀进程/重启后重启前那段工作会被归到重启后的任务。切点只对本工作段有效。
 * 段首绑定(本段尚无任务历史)只改快照、不落切点,也不发边界事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerEngineTaskCutTest {
    /** 段内切过一次的引擎:段首绑 7(无切点)→ 09:05 切到 9 → 返回 (引擎, 切点墙钟) */
    private suspend fun engineWithCut(t: FakeTime): Pair<TimerEngine, Long> {
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 3_600_000L, 300_000L)
        e.setTask(7L)
        t.el += 300_000; t.nowMs += 300_000
        e.setTask(9L)
        return e to t.nowMs
    }

    /**
     * 09:00 开始绑 A → 09:10 暂停(T1)→ 09:12 暂停中切到 B(T2)→ 09:15 恢复 → 09:40 收尾。
     * 边界必须落在 T1(该次工作停止的时刻)而非 T2,否则 09:00-09:10 那 10 分钟会被整段记到 B。
     */
    @Test fun pausedSwitchCutsAtPauseStartNotSwitchMoment() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 3_600_000L, 300_000L)        // 09:00:el=10_000, wall=1_000_000
        e.setTask(1L)                            // 段首绑 A:只改快照,不落切点
        t.el += 600_000; t.nowMs += 600_000      // 09:10
        val t1 = t.nowMs
        e.pause()
        val seen = recordEvents(e)
        t.el += 120_000; t.nowMs += 120_000      // 09:12(暂停中)
        e.setTask(2L)
        val sw = seen.filterIsInstance<EngineEvent.TaskSwitched>().single()
        assertEquals("边界必须取暂停起点", t1, sw.atWall)
        assertEquals(1L, sw.fromTaskId)
        assertEquals(2L, sw.toTaskId)
        assertEquals(2L, e.snapshot.value!!.taskId)
        assertEquals(listOf(Triple(t1, 1L, 2L)), e.snapshot.value!!.taskCutPoints())
        t.el += 180_000; t.nowMs += 180_000      // 09:15 恢复
        e.resume()
        t.el += 1_500_000; t.nowMs += 1_500_000  // 09:40 收尾
        e.reset()
        assertEquals(2L, seen.filterIsInstance<EngineEvent.Reset>().single().taskId)
    }

    /** 暂停中重复选择同一任务仍是 no-op:不得产生假切点(会把同任务时间账切成两份) */
    @Test fun sameTaskWhilePausedDoesNotEmitBoundary() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(7L)
        e.pause()
        val seen = recordEvents(e)
        e.setTask(7L)
        assertTrue("同值切换不得发边界,实际 $seen", seen.none { it is EngineEvent.TaskSwitched })
    }

    /** 切点随运行态持久化:重启后仍在,且重启后新增的切点追加在后(旧切点不丢) */
    @Test fun cutsSurviveRestart() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 3_600_000L, 300_000L)
        e.setTask(1L)                            // 段首绑定:不落切点
        t.el += 300_000; t.nowMs += 300_000      // 09:05 切到 B
        e.setTask(2L)
        val cuts = listOf(Triple(t.nowMs, 1L, 2L))
        assertEquals(cuts, e.snapshot.value!!.taskCutPoints())
        // 模拟设备重启:先落库(编码)再读回(解码),然后走重启换算与恢复路径 —— 切点表必须原样带回
        val before = e.snapshot.value!!
        val persisted = RuntimeStateCodec.fromMap(RuntimeStateCodec.toMap(before))!!
        t.el = 2_000L; t.nowMs += 120_000L
        e.adoptRestored(StateRestorer.afterBoot(persisted, t.nowMs, t.el))
        assertEquals(cuts, e.snapshot.value!!.taskCutPoints())
        assertEquals(2L, e.snapshot.value!!.taskId)
        // 重启后继续切:新切点追加,旧切点保留
        t.el += 60_000; t.nowMs += 60_000
        e.setTask(3L)
        assertEquals(cuts + Triple(t.nowMs, 2L, 3L), e.snapshot.value!!.taskCutPoints())
    }

    /** 切点只对本工作段有效:阶段推进后清空,旧切点不得残留影响新段归属 */
    @Test fun cutsClearedWhenSegmentAdvances() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(7L)                            // 段首绑定:不落切点
        t.el += 10_000; t.nowMs += 10_000
        e.setTask(9L)                            // 段内切换 -> 切点
        assertTrue(e.snapshot.value!!.taskCutPoints().isNotEmpty())
        t.el += 50_000; t.nowMs += 50_000
        e.onExpired()                            // 工作段收尾 -> REST
        assertEquals(Phase.REST, e.snapshot.value!!.phase)
        assertTrue("旧段切点不得带入休息段", e.snapshot.value!!.taskCutPoints().isEmpty())
        t.el += 30_000; t.nowMs += 30_000
        e.onExpired()                            // 回到 WORK:新段,绑定延续、切点清空
        assertEquals(Phase.WORK, e.snapshot.value!!.phase)
        assertTrue("旧段切点不得带入新工作段", e.snapshot.value!!.taskCutPoints().isEmpty())
        assertEquals(9L, e.snapshot.value!!.taskId)
    }

    /** 结算事件必须自带本段切点表(编码同 rt_task_cuts):Task 5 在快照被清空后仍能拿到切点 */
    @Test fun phaseFinishedCarriesSegmentCutTable() = runTest {
        val t = FakeTime()
        val (e, cutWall) = engineWithCut(t)
        assertEquals("$cutWall,7,9", e.snapshot.value!!.taskCuts)
        val seen = recordEvents(e)
        e.skip()
        val pf = seen.filterIsInstance<EngineEvent.PhaseFinished>().single()
        assertEquals("$cutWall,7,9", pf.taskCuts)
        assertEquals(listOf(Triple(cutWall, 7L, 9L)), parseTaskCuts(pf.taskCuts))
        assertTrue("新段快照已清空,切点只能靠事件带回", e.snapshot.value!!.taskCutPoints().isEmpty())
    }

    /** 终止(reset)同样自带本段切点表 */
    @Test fun resetCarriesSegmentCutTable() = runTest {
        val t = FakeTime()
        val (e, cutWall) = engineWithCut(t)
        val seen = recordEvents(e)
        e.reset()
        val rs = seen.filterIsInstance<EngineEvent.Reset>().single()
        assertEquals("$cutWall,7,9", rs.taskCuts)
        assertEquals(9L, rs.taskId)              // 末子段任务(段首归属取最早切点 from,见 KDoc)
    }

    /** 重启阶段同样自带本段切点表 */
    @Test fun phaseRestartedCarriesSegmentCutTable() = runTest {
        val t = FakeTime()
        val (e, cutWall) = engineWithCut(t)
        e.pause()
        val seen = recordEvents(e)
        e.restartPhase(1L, 3_600_000L, 300_000L)
        val pr = seen.filterIsInstance<EngineEvent.PhaseRestarted>().single()
        assertEquals("$cutWall,7,9", pr.taskCuts)
        assertEquals(9L, pr.taskId)
        assertTrue("重开的新段快照切点已清空", e.snapshot.value!!.taskCutPoints().isEmpty())
    }
}
