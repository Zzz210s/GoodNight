package com.goodnight

import com.goodnight.data.RuntimeStateCodec
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6 / #10:RuntimeSnapshot.countUp 的序列化契约。
 * 键为可选项 —— false 时不写入(倒计时旧快照序列化逐字节不变),
 * 旧库解析缺省 false(向前兼容 v1 会话文件)。
 */
class CountUpStateCodecTest {
    private val base = RuntimeSnapshot(
        profileId = 7, workMillis = 1_500_000, restMillis = 300_000,
        phase = Phase.WORK, status = EngineStatus.PAUSED, cycleCount = 3,
        startElapsed = 100, endElapsed = 1_500_100, endWall = 1_726_000_000_000,
        timeSpentPaused = 5_000, lastPauseTime = 900, timeAtPause = 200_000,
        savedAtWall = 1_725_999_000_000, savedAtElapsed = 800,
        ckptDate = "2026-08-31", ckptAccum = 123_456,
    )

    @Test fun countUpTrueWritesKeyAndRoundTrips() {
        val s = base.copy(countUp = true)
        val m = RuntimeStateCodec.toMap(s)
        assertEquals("true", m["rt_count_up"])
        assertEquals(s, RuntimeStateCodec.fromMap(m))
    }

    @Test fun countUpFalseOmitsKeySoOldSavesStayByteIdentical() {
        val m = RuntimeStateCodec.toMap(base) // countUp=false
        assertFalse("false 时不得写键(旧会话文件字节等价)", m.containsKey("rt_count_up"))
        assertEquals(base, RuntimeStateCodec.fromMap(m))
    }

    @Test fun missingKeyDefaultsToFalse() {
        val m = RuntimeStateCodec.toMap(base).filterKeys { it != "rt_count_up" }
        val restored = RuntimeStateCodec.fromMap(m)
        assertTrue(!restored!!.countUp) // 旧 v1 快照文件无此键 -> 倒计时语义
        assertEquals(base, restored)
    }
}
