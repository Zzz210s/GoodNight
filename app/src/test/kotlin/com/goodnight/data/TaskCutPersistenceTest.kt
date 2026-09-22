package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.timer.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 3:任务切点与 taskId 落库(Robolectric 库名唯一,避免沙箱串扰)。
 * 既有暂停切分语义不得回归 —— 第二个用例锁定默认参数下的老行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskCutPersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = GoodNightDatabase.build(ctx, "task_cut_persistence.db")
    private val now = 1_700_000_000_000L
    private val time = object : TimeProvider {
        override fun now(): Long = now
        override fun elapsedRealtime(): Long = now
    }
    private val repo = DailyTotalRepository(db, db.dailyTotalDao(), db.focusSessionDao(), time)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @After fun tearDown() = db.close()

    /** 同一 Robolectric 沙箱的库文件会跨运行残留 —— 每个用例开头清空(Room 要求非主线程) */
    private suspend fun wipe() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() }

    private fun dayMs(): Long =
        LocalDate.of(2026, 9, 22).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun taskIdAndCutsPersistPerSegment() = runTest {
        wipe()
        val t0 = dayMs() + 9 * 3_600_000L
        repo.recordWorkSessionSplit(
            1L, t0, t0 + 60 * 60_000L, emptyList(),
            taskId = 42L, extraCuts = listOf(t0 + 30 * 60_000L), zone = zone,
        )
        val rows = repo.sessionsBetweenMs(t0, t0 + 60 * 60_000L)
        assertEquals(
            listOf(t0 to t0 + 30 * 60_000L, t0 + 30 * 60_000L to t0 + 60 * 60_000L),
            rows.map { it.startAt to it.endAt },
        )
        assertEquals(listOf(42L, 42L), rows.map { it.taskId })
    }

    @Test fun defaultParametersKeepLegacyPauseBehaviour() = runTest {
        wipe()
        val t0 = dayMs() + 14 * 3_600_000L
        val pause = longArrayOf(t0 + 20 * 60_000L, t0 + 40 * 60_000L)
        repo.recordWorkSessionSplit(1L, t0, t0 + 60 * 60_000L, listOf(pause), zone = zone)
        val rows = repo.sessionsBetweenMs(t0, t0 + 60 * 60_000L)
        assertEquals(
            listOf(t0 to t0 + 20 * 60_000L, t0 + 40 * 60_000L to t0 + 60 * 60_000L),
            rows.map { it.startAt to it.endAt },
        )
        assertTrue(rows.all { it.taskId == null })
    }
}
