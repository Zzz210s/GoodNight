package com.goodnight.ui.report

import com.goodnight.data.db.DayProfileTotal
import com.goodnight.data.db.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 纯 JVM 聚合单测:窗口边界与行/合计语义(不依赖 Room/Android) */
class ReportAggregateTest {
    private fun dp(date: String, profileId: Long, minutes: Long) =
        DayProfileTotal(date, profileId, minutes * 60_000L)

    private fun minutes(m: Long) = m * 60_000L

    // 2026-09-06 是周日(所在 ISO 周首为 2026-08-31 周一);2026-08-31 本身是周一
    @Test fun weekWindowMondayAnchored() {
        assertEquals(
            "2026-08-31" to "2026-09-06",
            reportWindow(ReportRange.WEEK, LocalDate.of(2026, 9, 6)),
        )
        assertEquals(
            "2026-08-31" to "2026-08-31",
            reportWindow(ReportRange.WEEK, LocalDate.of(2026, 8, 31)),
        )
    }

    @Test fun monthWindowFirstToToday() {
        assertEquals(
            "2026-09-01" to "2026-09-25",
            reportWindow(ReportRange.MONTH, LocalDate.of(2026, 9, 25)),
        )
        assertEquals(
            "2026-09-01" to "2026-09-01",
            reportWindow(ReportRange.MONTH, LocalDate.of(2026, 9, 1)),
        )
    }

    @Test fun weekRowsLabelByDay() {
        val today = LocalDate.of(2026, 9, 6)
        val raw = listOf(dp("2026-08-31", 1, 60), dp("2026-09-01", 1, 30), dp("2026-09-01", 2, 10))
        val rows = reportRows(ReportRange.WEEK, today, raw)
        assertEquals(listOf("08-31", "09-01"), rows.map { it.label })
        assertEquals(listOf(minutes(60), minutes(40)), rows.map { it.millis }) // 同日跨配置累加
    }

    @Test fun monthRowsBucketBySevenDaySlices() {
        val today = LocalDate.of(2026, 9, 25)
        val raw = listOf(
            dp("2026-09-02", 1, 60),
            dp("2026-09-10", 1, 30),
            dp("2026-09-25", 2, 45),
        )
        val rows = reportRows(ReportRange.MONTH, today, raw)
        assertEquals(
            listOf("第 1 周(09-01~09-07)", "第 2 周(09-08~09-14)", "第 4 周(09-22~09-25)"),
            rows.map { it.label },
        )
        assertEquals(listOf(minutes(60), minutes(30), minutes(45)), rows.map { it.millis })
    }

    @Test fun profileTotalsSkipDeletedProfiles() {
        // v1.10.8:已删除配置的孤儿行不再参与统计(删除时已级联清理,这里兜底过滤)
        val profiles = listOf(ProfileEntity(5, "深度", 25, 5, 0))
        val raw = listOf(dp("2026-09-01", 5, 30), dp("2026-09-02", 9, 90), dp("2026-09-03", 5, 10))
        val totals = reportProfileTotals(profiles, raw)
        assertEquals(listOf("深度"), totals.map { it.profileName })
        assertEquals(listOf(minutes(40)), totals.map { it.millis })
    }
}
