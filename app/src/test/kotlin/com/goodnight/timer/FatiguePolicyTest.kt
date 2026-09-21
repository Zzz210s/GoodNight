package com.goodnight.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.14.0 疲劳提醒策略:连续工作累计(忽略中间短休息)+ 阈值/冷却。
 * 阈值 90 分钟依据超日节律(Kleitman BRAC:注意力约 90 分钟一个周期)。
 */
class FatiguePolicyTest {
    private val min = 60_000L

    @Test fun noHistoryCountsCurrentPhaseOnly() {
        assertEquals(12 * min, FatiguePolicy.continuousWorkMs(emptyList(), 0L, 12 * min))
    }

    @Test fun singleSegmentJoinsCurrentPhase() {
        // 45 分钟历史 + 15 分钟休息 + 当前阶段已 5 分钟 = 连续 50 分钟
        val sessions = listOf(0L to 45 * min)
        assertEquals(50 * min, FatiguePolicy.continuousWorkMs(sessions, 60 * min, 5 * min))
    }

    @Test fun shortBreaksAreIgnored() {
        // 45 分钟工作 + 15 分钟休息 + 45 分钟工作 + 15 分钟休息 + 当前 10 分钟
        val sessions = listOf(0L to 45 * min, 60 * min to 105 * min)
        assertEquals(100 * min, FatiguePolicy.continuousWorkMs(sessions, 120 * min, 10 * min))
    }

    @Test fun gapExactlyAtThresholdStaysContinuous() {
        // 空档正好 30 分钟:仍算连续(边界取"不超过阈值")
        val sessions = listOf(0L to 45 * min, 75 * min to 120 * min)
        assertEquals(90 * min, FatiguePolicy.continuousWorkMs(sessions, 150 * min, 0L))
    }

    @Test fun longGapResetsStreak() {
        // 空档 40 分钟 > 30 分钟:视为真正休息过,只算当前阶段
        val sessions = listOf(0L to 45 * min, 85 * min to 100 * min)
        assertEquals(5 * min, FatiguePolicy.continuousWorkMs(sessions, 140 * min, 5 * min))
    }

    @Test fun overlappingSegmentsCountUnionOnce() {
        val sessions = listOf(0L to 60 * min, 30 * min to 90 * min)
        assertEquals(90 * min, FatiguePolicy.continuousWorkMs(sessions, 100 * min, 0L))
    }

    @Test fun invalidSegmentsIgnored() {
        assertEquals(30 * min, FatiguePolicy.continuousWorkMs(listOf(0L to 0L), 100 * min, 30 * min))
    }

    @Test fun remindsOnlyAtThreshold() {
        assertFalse(FatiguePolicy.shouldRemind(89 * min, nowMs = 0L, lastRemindMs = null))
        assertTrue(FatiguePolicy.shouldRemind(90 * min, nowMs = 0L, lastRemindMs = null))
    }

    @Test fun cooldownSuppressesRepeat() {
        val now = 10_000_000L
        assertFalse("冷却内不重复提醒", FatiguePolicy.shouldRemind(120 * min, now, now - 5 * min))
        assertTrue("冷却过后可再提醒", FatiguePolicy.shouldRemind(120 * min, now, now - 31 * min))
    }
}
