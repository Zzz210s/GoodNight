package com.goodnight.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.10:每日详情时段合并(间隔 <= 3 分钟)与大时段标识纯函数测试 */
class DaySegmentsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(h: Int, m: Int) =
        java.time.LocalDateTime.of(2026, 9, 4, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun gapWithinThreeMinutesMerges() {
        val out = mergeSessions(listOf(ms(9, 0) to ms(9, 25), ms(9, 27) to ms(9, 50)))
        assertEquals(1, out.size)
        assertEquals(ms(9, 0), out[0].first)
        assertEquals(ms(9, 50), out[0].second)
    }

    @Test fun gapOverThreeMinutesStaysSeparate() {
        val out = mergeSessions(listOf(ms(9, 0) to ms(9, 25), ms(9, 30) to ms(9, 50)))
        assertEquals(2, out.size)
        assertEquals(ms(9, 25), out[0].second)
        assertEquals(ms(9, 30), out[1].first)
    }

    @Test fun exactlyThreeMinutesMergesInclusive() {
        val out = mergeSessions(listOf(ms(9, 0) to ms(9, 25), ms(9, 28) to ms(9, 50)))
        assertEquals(1, out.size)
    }

    @Test fun threeMinutesPlusOneSecondSplits() {
        val out = mergeSessions(listOf(ms(9, 0) to ms(9, 25), (ms(9, 28) + 1_000) to ms(9, 50)))
        assertEquals(2, out.size)
    }

    @Test fun unsortedInputIsSortedAndMerged() {
        val out = mergeSessions(listOf(ms(9, 27) to ms(9, 50), ms(9, 0) to ms(9, 25)))
        assertEquals(1, out.size)
        assertEquals(ms(9, 0), out[0].first)
        assertEquals(ms(9, 50), out[0].second)
    }

    @Test fun degenerateSegmentsAreDropped() {
        assertEquals(0, mergeSessions(listOf(ms(9, 0) to ms(9, 0), ms(10, 0) to ms(9, 0))).size)
        assertEquals(0, mergeSessions(emptyList()).size)
    }

    @Test fun nestedSegmentKeepsWidestEnd() {
        val out = mergeSessions(listOf(ms(9, 0) to ms(9, 40), ms(9, 10) to ms(9, 20)))
        assertEquals(1, out.size)
        assertEquals(ms(9, 40), out[0].second)
    }

    /** 凌晨 0-5 / 早上 6-8 / 上午 9-11 / 下午 12-17 / 晚上 18-23 */
    @Test fun periodBucketsByStartHour() {
        assertEquals(DayPeriod.DAWN, dayPeriodOf(ms(0, 0), zone))
        assertEquals(DayPeriod.DAWN, dayPeriodOf(ms(5, 59), zone))
        assertEquals(DayPeriod.EARLY_MORNING, dayPeriodOf(ms(6, 0), zone))
        assertEquals(DayPeriod.EARLY_MORNING, dayPeriodOf(ms(8, 59), zone))
        assertEquals(DayPeriod.MORNING, dayPeriodOf(ms(9, 0), zone))
        assertEquals(DayPeriod.MORNING, dayPeriodOf(ms(11, 59), zone))
        assertEquals(DayPeriod.AFTERNOON, dayPeriodOf(ms(12, 0), zone))
        assertEquals(DayPeriod.AFTERNOON, dayPeriodOf(ms(17, 59), zone))
        assertEquals(DayPeriod.EVENING, dayPeriodOf(ms(18, 0), zone))
        assertEquals(DayPeriod.EVENING, dayPeriodOf(ms(23, 59), zone))
    }

    /** 展示分组:同一大时段的连续段归一绋,不同时段分开(保序) */
    @Test fun groupByPeriodKeepsOrderAndSplitsByPeriod() {
        val a = ms(9, 0) to ms(9, 25)
        val b = ms(9, 40) to ms(9, 55)
        val c = ms(14, 0) to ms(14, 20)
        val out = groupByPeriod(listOf(a, b, c), zone)
        assertEquals(2, out.size)
        assertEquals(DayPeriod.MORNING, out[0].first)
        assertEquals(listOf(a, b), out[0].second)
        assertEquals(DayPeriod.AFTERNOON, out[1].first)
        assertEquals(listOf(c), out[1].second)
    }

    @Test fun groupByPeriodEmpty() {
        assertEquals(0, groupByPeriod(emptyList(), zone).size)
    }
}

/** v1.11.1:合并后仍不足 3 分钟的段落应整段丢弃(展示与合计共用同一函数) */
class MinSpanDropTest {
    private fun m(mins: Long, secs: Long = 0) = mins * 60_000L + secs * 1_000L

    @Test fun shortSpanIsDropped() {
        // 一段 2 分钟(无相邻段可合并)-> 丢弃
        org.junit.Assert.assertTrue(mergeSessions(listOf(m(0) to m(2))).isEmpty())
    }

    @Test fun shortSpansMergeIntoKeptSpan() {
        // 2 分钟 + 1 分钟空档 + 2 分钟 = 合并后 5 分钟 -> 保留
        val out = mergeSessions(listOf(m(0) to m(2), m(3) to m(5)))
        org.junit.Assert.assertEquals(1, out.size)
        org.junit.Assert.assertEquals(m(5), out[0].second - out[0].first)
    }

    @Test fun keptSpansSumEqualsDisplayedTotal() {
        val out = mergeSessions(listOf(m(0) to m(2), m(10) to m(40), m(41) to m(43)))
        // 2 分钟段丢弃;10~40 与 41~43(1 分钟空档)合并为 33 分钟
        org.junit.Assert.assertEquals(1, out.size)
        org.junit.Assert.assertEquals(m(33), out[0].second - out[0].first)
    }
}
