package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.timer.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10.8:合计与每日详情"同源"的护栏 + 自动备份的数据变动心跳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TotalsConsistencyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = GoodNightDatabase.build(ctx, "totals_consistency.db")
    private val now = 1_700_000_000_000L
    private val time = object : TimeProvider {
        override fun now(): Long = now
        override fun elapsedRealtime(): Long = now
    }
    private val repo = DailyTotalRepository(db, db.dailyTotalDao(), db.focusSessionDao(), time)
    private val zone: ZoneId = ZoneId.systemDefault()

    @After fun tearDown() = db.close()

    /** 用固定的历史日期,避免同一 Robolectric 沙箱里其它用例(或今天的数据)造成相互干扰 */
    /** 每个用例用各自独立的日期:同一 Robolectric 沙箱共用一个 DB 文件,避免相互污染 */
    private var day: LocalDate = LocalDate.of(2026, 9, 1)
    private fun dayMs(): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 间隔 <=3 分钟的相邻段落:展示合并为一条,合计 = 合并后时长(而不是各段原样相加) */
    /** 同一 Robolectric 沙箱的 DB 文件会跨用例/跨运行残留 —— 每个用例开头清空三张表,彻底隔离 */
    private suspend fun wipe() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() }

    @Test fun totalsEqualMergedSpans() = runTest {
        wipe()
        val t0 = dayMs() + 9 * 3_600_000L
        repo.recordWorkSessionSplit(1L, t0, t0 + 10 * 60_000L, emptyList(), zone = zone)
        repo.recordWorkSessionSplit(1L, t0 + 12 * 60_000L, t0 + 32 * 60_000L, emptyList(), zone = zone)
        val date = day.toString()
        val rows = repo.breakdownByDate(date)
        assertEquals(1, rows.size)
        // 10 + 2(空档) + 20 = 32 分钟 —— 与展示合并后的时间段之和一致
        assertEquals(32 * 60_000L, rows[0].total)
        val spans = mergeSessions(
            repo.sessionsBetween(dayMs(), dayMs() + 86_400_000L).map { it.startAt to it.endAt },
        )
        assertEquals(rows[0].total, spans.sumOf { it.second - it.first })
    }

    /** 超过 3 分钟的空档不并入:合计只算实际两段 */
    @Test fun longGapStaysSplit() = runTest {
        wipe()
        day = LocalDate.of(2026, 9, 2)
        val t0 = dayMs() + 9 * 3_600_000L
        repo.recordWorkSessionSplit(1L, t0, t0 + 10 * 60_000L, emptyList(), zone = zone)
        repo.recordWorkSessionSplit(1L, t0 + 20 * 60_000L, t0 + 30 * 60_000L, emptyList(), zone = zone)
        val rows = repo.breakdownByDate(day.toString())
        assertEquals(20 * 60_000L, rows[0].total)
    }

    /** 删除配置:段落与合计级联清理 */
    @Test fun deletingProfileCascadesData() = runTest {
        wipe()
        day = LocalDate.of(2026, 9, 5)
        val t0 = dayMs() + 9 * 3_600_000L
        repo.recordWorkSessionSplit(7L, t0, t0 + 30 * 60_000L, emptyList(), zone = zone)
        assertEquals(1, repo.breakdownByDate(day.toString()).size)
        repo.deleteProfileData(7L)
        assertTrue(repo.breakdownByDate(day.toString()).isEmpty())
        assertTrue(repo.sessionsBetween(dayMs(), dayMs() + 86_400_000L).isEmpty())
    }

    /** 数据变动心跳:任何写入都会改变它(自动备份的触发源) */
    @Test fun dataTickChangesOnWrite() = runTest {
        // 不在此用例清表:dataTick() 是观察 Flow(持有查询),清表会与它抢锁(SQLITE_BUSY)。
        // 断言只要求"写入后心跳变化",与存量数据无关。
        day = LocalDate.of(2026, 9, 4)
        val before = repo.dataTick().first()
        repo.recordWorkSessionSplit(3L, dayMs() + 3_600_000L, dayMs() + 3_600_000L + 600_000L, emptyList(), zone = zone)
        val after = repo.dataTick().first()
        assertNotEquals(before, after)
    }
}
