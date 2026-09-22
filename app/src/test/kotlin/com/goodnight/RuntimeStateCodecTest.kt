package com.goodnight

import com.goodnight.data.RuntimeStateCodec
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateCodecTest {
    private val snap = RuntimeSnapshot(
        profileId = 7, workMillis = 1_500_000, restMillis = 300_000,
        phase = Phase.WORK, status = EngineStatus.PAUSED, cycleCount = 3,
        startElapsed = 100, endElapsed = 1_500_100, endWall = 1_726_000_000_000,
        timeSpentPaused = 5_000, lastPauseTime = 900, timeAtPause = 200_000,
        savedAtWall = 1_725_999_000_000, savedAtElapsed = 800,
        ckptDate = "2026-08-31", ckptAccum = 123_456,
    )

    @Test fun roundTrip() {
        val restored = RuntimeStateCodec.fromMap(RuntimeStateCodec.toMap(snap))
        assertEquals(snap, restored)
    }

    @Test fun nullToEmptyAndBack() {
        assertTrue(RuntimeStateCodec.toMap(null).isEmpty())
        assertNull(RuntimeStateCodec.fromMap(emptyMap()))
    }

    @Test fun ignoresForeignKeys() {
        val m = RuntimeStateCodec.toMap(snap) + mapOf("unrelated" to "x")
        assertEquals(snap, RuntimeStateCodec.fromMap(m))
    }

    /** v2.1:当前任务随运行态持久化(杀进程/重启后绑定仍在) */
    @Test fun taskIdSurvivesRoundTrip() {
        val bound = snap.copy(taskId = 42L)
        assertEquals("42", RuntimeStateCodec.toMap(bound)["rt_task_id"])
        assertEquals(bound, RuntimeStateCodec.fromMap(RuntimeStateCodec.toMap(bound)))
        assertEquals(42L, RuntimeStateCodec.fromMap(RuntimeStateCodec.toMap(bound))!!.taskId)
    }

    /** 未绑定时不写键:既有会话快照序列化逐字节不变 */
    @Test fun taskIdKeyOmittedWhenUnbound() {
        assertTrue("rt_task_id" !in RuntimeStateCodec.toMap(snap))
        assertNull(RuntimeStateCodec.fromMap(RuntimeStateCodec.toMap(snap))!!.taskId)
    }

    /** 旧库状态没有该键:解析为 null(未绑定),不崩 */
    @Test fun legacyStateWithoutTaskIdDecodesToNull() {
        val legacy = RuntimeStateCodec.toMap(snap) - "rt_task_id"
        assertNull(RuntimeStateCodec.fromMap(legacy)!!.taskId)
    }
}
