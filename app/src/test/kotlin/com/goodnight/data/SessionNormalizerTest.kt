package com.goodnight.data

import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.timer.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.11.1:规则落到数据层 —— 写入即合并/丢弃;历史行清洗后与展示一致 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionNormalizerTest {
    private val ctx: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = GoodNightDatabase.build(ctx, "session_normalizer.db")
    private val zone: java.time.ZoneId = ZoneId.systemDefault()
    private val now = 1_700_000_000_000L
    private val time = object : TimeProvider {
        override fun now(): Long = now
        override fun elapsedRealtime(): Long = now
    }
    private val repo = DailyTotalRepository(db, db.dailyTotalDao(), db.focusSessionDao(), time)
    private val dao = db.focusSessionDao()
    private val day = LocalDate.of(2027, 3, 1)
    private fun ms(min: Long) = day.atStartOfDay(zone).toInstant().toEpochMilli() + min * 60_000L

    @After fun close() = db.close()

    @Test fun writePathMergesAndDropsShortSpans() = kotlinx.coroutines.test.runTest {
        // 一次 0~43 的专注,期间两次暂停:2~10 分钟(8 分钟,>3 分钟 -> 切开且前段 2 分钟被丢弃)
        // 与 40~41 分钟(1 分钟,<=3 分钟 -> 不切,前后段合并)
        repo.recordWorkSessionSplit(
            1L, ms(0), ms(43),
            listOf(longArrayOf(ms(2), ms(10)), longArrayOf(ms(40), ms(41))),
            zone = zone,
        )
        val stored = dao.between(ms(0), ms(0) + 86_400_000L)
        org.junit.Assert.assertEquals(1, stored.size)               // 只留合并后的一段
        org.junit.Assert.assertEquals(33 * 60_000L, stored[0].endAt - stored[0].startAt)
    }

    @Test fun normalizerCleansLegacyRows() = kotlinx.coroutines.test.runTest {
        // 模拟旧数据:1 分钟段 + 5 分钟段 + 4 分钟段(间隔 1 分钟)
        dao.insertAll(
            listOf(
                FocusSessionEntity(profileId = 2, startAt = ms(0), endAt = ms(1)),
                FocusSessionEntity(profileId = 2, startAt = ms(100), endAt = ms(105)),
                FocusSessionEntity(profileId = 2, startAt = ms(106), endAt = ms(110)),
            ),
        )
        SessionNormalizer.normalizeAllSessionsOnce(db, dao, repo, zone)
        val stored = dao.between(ms(0), ms(0) + 86_400_000L)
        org.junit.Assert.assertEquals(1, stored.size)               // 1 分钟段删除;100~110 合并
        org.junit.Assert.assertEquals(10 * 60_000L, stored[0].endAt - stored[0].startAt)
    }
}
