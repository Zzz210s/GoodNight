package com.goodnight.ui.report

import com.goodnight.data.db.DayProfileTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.5 健康风报表纯函数测试 */
class ReportMetricsTest {
    private fun d(date: String, profile: Long = 1, min: Long) = DayProfileTotal(date, profile, min * 60_000)

    @Test fun metricsFocusDaysAvgBest() {
        val raw = listOf(
            d("2026-09-01", min = 30), d("2026-09-01", profile = 2, min = 60),
            d("2026-09-03", min = 25), d("2026-09-05", min = 120),
        )
        val m = computeMetrics(raw)
        assertEquals(3, m.focusDays)
        assertEquals(120, m.bestMinutes)
        assertEquals("09-05", m.bestDay)
        // 窗口跨度 09-01..09-05 = 5 天,总 235 分钟 -> 日均 47
        assertEquals(47L, m.avgMinutesPerDay)
    }

    @Test fun streakAcrossConsecutiveDays() {
        val raw = listOf(
            d("2026-09-03", min = 1), d("2026-09-04", min = 1), d("2026-09-05", min = 1),
        )
        assertEquals(3, computeMetrics(raw).streakDays)
    }

    @Test fun streakResetsOnGap() {
        val raw = listOf(
            d("2026-09-01", min = 1), d("2026-09-03", min = 1), d("2026-09-04", min = 1),
        )
        assertEquals(2, computeMetrics(raw).streakDays)
    }

    @Test fun prevDeltaPercentMath() {
        assertEquals(50, prevDeltaPercent(150L, 100L))
        assertEquals(-25, prevDeltaPercent(75L, 100L))
        assertEquals(0, prevDeltaPercent(100L, 100L))
        assertNull(prevDeltaPercent(0L, 0L))
        assertNull(prevDeltaPercent(50L, 0L))
        assertNull(prevDeltaPercent(50L, null))
    }

    @Test fun prevWindowMovesBackSameLength() {
        val (f, t) = prevWindowOf("2026-09-01", "2026-09-05")
        assertEquals("2026-08-27", f)
        assertEquals("2026-08-31", t)
    }
}
