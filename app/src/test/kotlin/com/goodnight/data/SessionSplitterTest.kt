package com.goodnight.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3 #6:跨午夜切分纯函数测试(Asia/Shanghai 定区,避免 CI 时区漂移) */
class SessionSplitterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        java.time.LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun sameDayYieldsSingleSegment() {
        val segs = splitAtMidnights(ms(2026, 9, 4, 9, 15), ms(2026, 9, 4, 9, 40), zone)
        assertEquals(1, segs.size)
        assertEquals(ms(2026, 9, 4, 9, 15), segs[0].first)
        assertEquals(ms(2026, 9, 4, 9, 40), segs[0].second)
    }

    @Test fun crossOneMidnightSplitsAt0000() {
        val segs = splitAtMidnights(ms(2026, 9, 4, 23, 30), ms(2026, 9, 5, 0, 20), zone)
        assertEquals(2, segs.size)
        assertEquals(ms(2026, 9, 4, 23, 30), segs[0].first)
        assertEquals(ms(2026, 9, 5, 0, 0), segs[0].second) // 前段止于 00:00(Q2)
        assertEquals(ms(2026, 9, 5, 0, 0), segs[1].first) // 后段起于 00:00(Q2)
        assertEquals(ms(2026, 9, 5, 0, 20), segs[1].second)
    }

    @Test fun crossTwoMidnightsYieldsThreeSegments() {
        val segs = splitAtMidnights(ms(2026, 9, 4, 23, 0), ms(2026, 9, 6, 0, 10), zone)
        assertEquals(3, segs.size)
        // segs[0]=9/4 23:00~9/5 00:00;segs[1]=9/5 整天;segs[2]=9/6 00:00~00:10
        assertEquals(ms(2026, 9, 5, 0, 0), segs[0].second)
        assertEquals(ms(2026, 9, 5, 0, 0), segs[1].first)
        assertEquals(ms(2026, 9, 6, 0, 0), segs[1].second)
        assertEquals(ms(2026, 9, 6, 0, 0), segs[2].first)
        assertEquals(ms(2026, 9, 6, 0, 10), segs[2].second)
    }

    @Test fun exactlyAtMidnightBoundaryKeepsSingleDaySegment() {
        // 23:59:59.x ~ 00:00 边界:end==nextMidnight 时前段已含全部,不产生空后段
        val end = ms(2026, 9, 5, 0, 0)
        val segs = splitAtMidnights(ms(2026, 9, 4, 23, 58), end, zone)
        assertEquals(1, segs.size)
        assertEquals(end, segs[0].second)
    }

    @Test fun emptyOrInvertedRangeYieldsNothing() {
        val t = ms(2026, 9, 4, 10, 0)
        assertTrue(splitAtMidnights(t, t, zone).isEmpty())
        assertTrue(splitAtMidnights(t, t - 1000, zone).isEmpty())
    }
}
