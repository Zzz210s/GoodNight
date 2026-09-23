package com.goodnight.timer

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎重启/检查点回填用例(自 TimerEngineBasicTest 拆分,维持单文件 <= 200 行) */
class TimerEngineRestoreTest {
    @Test fun onCheckpointFlushedUpdatesCursor() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        e.start(1, 100_000L, 50_000L)
        e.onCheckpointFlushed("2026-08-31", 42_000L)
        advanceUntilIdle()
        val s = e.snapshot.value!!
        assertEquals("2026-08-31", s.ckptDate)
        assertEquals(42_000L, s.ckptAccum)
    }

    /** v2.1:杀进程/重启后绑定仍在 —— 恢复带任务的快照即可;同值重复下发不得产生假段边界 */
    @Test fun restoredSnapshotKeepsTaskBinding() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 60_000L, 30_000L)
        e.setTask(7L)
        val before = e.snapshot.value!!
        t.el = 2_000L; t.nowMs += 90_000L // 模拟设备重启(elapsed 清零、墙钟前移)
        e.adoptRestored(StateRestorer.afterBoot(before, t.nowMs, t.el))
        assertEquals(7L, e.snapshot.value!!.taskId)
        val seen = recordEvents(e)
        e.setTask(7L) // UI 恢复后按旧值重复下发
        assertTrue(seen.none { it is EngineEvent.TaskSwitched })
    }

    /** Task 7 review 缺陷回归:重启期间 PAUSED,resume 后 accruedWork 必须从冻结值继续,
     *  不得坍缩导致 ckptAccum 游标倒退、已落库工作量重复计数 */
    @Test fun resumeAfterBootReanchorsAccruedWork() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 50_000L) // WORK 100s,起始 el=10_000
        t.el += 40_000               // 已工作 40s
        e.pause()                    // 冻结 accrued=40_000
        e.onCheckpointFlushed("2026-08-31", 40_000L) // 模拟已 flush 40s
        // 模拟设备重启:elapsedRealtime 清零为小值,墙钟前移
        t.el = 2_000L
        t.nowMs += 120_000L
        val restored = StateRestorer.afterBoot(e.snapshot.value!!, t.nowMs, t.el)
        e.adoptRestored(restored)
        e.resume()
        // 恢复瞬间 accrued 必须保持在冻结值 40s(坍缩即游标倒退)
        assertEquals(40_000L, e.snapshot.value!!.accruedWork(t.el))
        // 再过 10s:accrued 从冻结值连续增长到 50s
        t.el += 10_000
        assertEquals(50_000L, e.snapshot.value!!.accruedWork(t.el))
        // 游标永不倒退:任意采样点 accrued 不低于已 flush 的 40s 且单调不减
        var prev = 50_000L
        repeat(20) {
            t.el += 100
            val a = e.snapshot.value!!.accruedWork(t.el)
            assertTrue("accrued regressed: $a < $prev", a >= prev)
            prev = a
        }
    }

    /** Fix Round 1 回归:pause()/resume()/onCheckpointFlushed() 必须刷新 savedAtElapsed/savedAtWall,
     *  StateRestorer 的重启守卫(nowElapsed >= savedAtElapsed)依赖此不变量 */
    @Test fun pauseResumeCheckpointRefreshSavedAtClocks() = runTest {
        val t = FakeTime()
        val saved = mutableListOf<RuntimeSnapshot?>()
        val e = testEngine(t, saved)
        e.restore(null)
        e.start(1, 100_000L, 50_000L) // savedAtElapsed=10_000, savedAtWall=1_000_000
        t.el += 40_000; t.nowMs += 40_000
        e.pause()
        var s = e.snapshot.value!!
        assertEquals(50_000L, s.savedAtElapsed)
        assertEquals(1_040_000L, s.savedAtWall)
        t.el += 5_000; t.nowMs += 5_000
        e.onCheckpointFlushed("2026-08-31", 40_000L)
        s = e.snapshot.value!!
        assertEquals(55_000L, s.savedAtElapsed)
        assertEquals(1_045_000L, s.savedAtWall)
        t.el += 10_000; t.nowMs += 10_000
        e.resume()
        s = e.snapshot.value!!
        assertEquals(65_000L, s.savedAtElapsed)
        assertEquals(1_055_000L, s.savedAtWall)
        advanceUntilIdle()
        // 持久化回调拿到的同样是刷新过游标的快照
        assertEquals(65_000L, saved.last()!!.savedAtElapsed)
        assertEquals(1_055_000L, saved.last()!!.savedAtWall)
    }

    /** Fix Round 1 回归(完整 stale-small 场景,经由引擎):
     *  start -> pause -> 重启#1(PAUSED 分支,savedAtElapsed=N1) -> resume(startElapsed 重锚为负)
     *  -> 重启#2(nowElapsed=N2 满足 N1 <= N2 < resume 时刻)必须仍被识别为重启。
     *  旧代码 resume 不刷新游标,守卫 N2 >= N1 漏判 -> 不重锚,endElapsed 留在死时钟上 */
    @Test fun secondRebootAfterResumeStillDetected() = runTest {
        val t = FakeTime()
        val e = testEngine(t)
        e.restore(null)
        e.start(1, 100_000L, 50_000L) // el=10_000, wall=1_000_000; end=110_000 / 1_100_000
        t.el += 40_000; t.nowMs += 40_000
        e.pause()                     // 冻结 accrued=40_000, timeAtPause=60_000
        // 重启#1:关机 2 分钟,BOOT_COMPLETED 在开机后 5s 送达
        // (N1=5_000 < start 时刻的游标 10_000,新旧代码都能识别这次重启)
        t.nowMs += 120_000; t.el = 5_000
        e.adoptRestored(StateRestorer.afterBoot(e.snapshot.value!!, t.nowMs, t.el))
        assertEquals(5_000L, e.snapshot.value!!.savedAtElapsed) // N1
        // 开机 20s 后 resume:startElapsed 重锚 = 25_000 - 40_000 = -15_000(负值)
        t.el += 20_000; t.nowMs += 20_000
        e.resume()
        assertEquals(-15_000L, e.snapshot.value!!.startElapsed)
        // 重启#2:关机 30s,BOOT_COMPLETED 在开机后 15s 送达:
        // N2=15_000 >= N1=5_000(旧代码守卫漏判)但 < resume 刷新的游标 25_000(修复后识别)
        t.nowMs += 30_000; t.el = 15_000
        val r = StateRestorer.afterBoot(e.snapshot.value!!, t.nowMs, t.el)
        assertEquals(EngineStatus.RUNNING, r.status)
        // 剩余墙钟 = endWall(1_240_000) - nowWall(1_210_000) = 30_000,折算到新单调钟
        assertEquals(45_000L, r.endElapsed)
        // span = 85_000 - (-15_000) = 100_000,重锚后 = 45_000 - 100_000
        assertEquals(-55_000L, r.startElapsed)
        assertEquals(15_000L, r.savedAtElapsed)
        e.adoptRestored(r)
        assertEquals(30_000L, e.snapshot.value!!.remaining(t.el))
        assertEquals(70_000L, e.snapshot.value!!.accruedWork(t.el)) // 40s 冻结 + 关机期间 30s
    }
}
