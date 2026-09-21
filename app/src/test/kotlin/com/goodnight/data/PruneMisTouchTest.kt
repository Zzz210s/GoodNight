package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.timer.TimeProvider
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.6 误触规则:短于 1 分钟的段被清理且不残留合计 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PruneMisTouchTest {
    private val time = object : TimeProvider {
        var nowMs = 1000L
        override fun now() = nowMs
        override fun elapsedRealtime() = 0L
    }
    private var db: GoodNightDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase("goodnight.db")
    }

    @Test fun longPauseSplitsWorkSession() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = GoodNightDatabase.build(ctx, "prune_mistouch.db")
        val repo = DailyTotalRepository(db!!, db!!.dailyTotalDao(), db!!.focusSessionDao(), time)
        val zone = ZoneId.of("UTC")
        val dayStart = java.time.LocalDate.of(2026, 9, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        // 整段 09:00~12:00,中间 10:00~10:30 暂停(30min > 5min):应切成 2 段(09:00-10:00, 10:30-12:00)
        val start = dayStart + 9 * 3600_000L
        val end = dayStart + 12 * 3600_000L
        val pause = listOf(longArrayOf(dayStart + 10 * 3600_000L, dayStart + 10 * 3600_000L + 30 * 60_000L))
        repo.recordWorkSessionSplit(1L, start, end, pause, 5 * 60_000L, zone)
        val segs = db!!.focusSessionDao().between(dayStart, dayStart + 86_400_000)
        assertEquals(2, segs.size)
        val span0 = segs[0].endAt - segs[0].startAt
        val span1 = segs[1].endAt - segs[1].startAt
        assertEquals(60 * 60_000L, span0) // 09:00-10:00
        assertEquals(90 * 60_000L, span1) // 10:30-12:00
        assertEquals(dayStart + 10 * 3600_000L, segs[0].endAt)
        assertEquals(dayStart + 10 * 3600_000L + 30 * 60_000L, segs[1].startAt)
        // 各段不含暂停间隙(sum = 150 分钟);daily_total 由结算事件另计,此处仅验证段切分
    }

    @Test fun pruneRemovesShortSessionsAndDeductsTotals() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = GoodNightDatabase.build(ctx, "prune_mistouch.db")
        val repo = DailyTotalRepository(db!!, db!!.dailyTotalDao(), db!!.focusSessionDao(), time)
        val zone = ZoneId.of("UTC")
        // 同一天:一段 20s(误触)+ 一段 10 分钟(正常)
        val dayStart = java.time.LocalDate.of(2026, 9, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        repo.addWork("2026-09-05", 1L, 620_000L) // 20s+600s
        repo.recordWorkSession(1L, dayStart + 60_000, dayStart + 80_000, zone)
        repo.recordWorkSession(1L, dayStart + 1_800_000, dayStart + 2_400_000, zone)
        assertEquals(2, db!!.focusSessionDao().count())

        repo.pruneMisTouchSessions(60_000L, zone)

        assertEquals(1, db!!.focusSessionDao().count())
        val left = db!!.focusSessionDao().between(dayStart, dayStart + 86_400_000).single()
        assertEquals(dayStart + 1_800_000L, left.startAt)
        // 合计 620s -> 扣 20s = 600s
        val total = repo.breakdownByDate("2026-09-05").single().total
        assertEquals(600_000L, total)
        // 清理幂等:再跑一次无副作用
        repo.pruneMisTouchSessions(60_000L, zone)
        assertEquals(600_000L, repo.breakdownByDate("2026-09-05").single().total)
    }
}
