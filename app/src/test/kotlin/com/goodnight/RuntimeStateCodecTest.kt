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
}
