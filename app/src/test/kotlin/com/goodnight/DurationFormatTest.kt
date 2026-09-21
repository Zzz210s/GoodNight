package com.goodnight

import com.goodnight.timer.DurationFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test fun hmVariants() {
        assertEquals("0 分钟", DurationFormat.hm(0))
        assertEquals("30 分钟", DurationFormat.hm(30 * 60_000L))
        assertEquals("1 小时 0 分钟", DurationFormat.hm(60 * 60_000L))
        assertEquals("3 小时 25 分钟", DurationFormat.hm(3 * 3_600_000L + 25 * 60_000L))
        assertEquals("1 分钟", DurationFormat.hm(59_999L))
    }
    @Test fun msVariants() {
        assertEquals("00:00", DurationFormat.ms(0))
        assertEquals("00:59", DurationFormat.ms(59_999L))
        assertEquals("24:59", DurationFormat.ms(24 * 60_000L + 59_000L))
        assertEquals("150:05", DurationFormat.ms(150 * 60_000L + 5_000L))
        assertEquals("00:00", DurationFormat.ms(-5_000L))
    }
}
