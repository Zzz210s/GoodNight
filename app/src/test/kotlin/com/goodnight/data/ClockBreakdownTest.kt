package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity
import com.goodnight.timer.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 6:报表「按任务」区块展开后的**时钟明细**聚合口径。
 *
 * 行级口径(排序/未绑定末位/悬挂 taskId)见 TaskBreakdownTest,这里只测新增的时钟层:
 * 「任务 × 时钟」分组、通用时钟按会话 taskId 归属、归档时钟名字仍可解析、跨午夜切段、
 * 以及任务行 == 其时钟行之和这条不变式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClockBreakdownTest {
    private val dbName = "goodnight-clock-breakdown.db"
    private var db: GoodNightDatabase? = null
    private lateinit var repo: DailyTotalRepository
    private lateinit var tasks: TaskRepository
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val time = object : TimeProvider {
        override fun now(): Long = 1_700_000_000_000L
        override fun elapsedRealtime(): Long = now()
    }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName) // 残留文件会让 build() 复用旧库,先清
        db = GoodNightDatabase.build(ctx, dbName)
        repo = DailyTotalRepository(db!!, db!!.dailyTotalDao(), db!!.focusSessionDao(), time)
        tasks = TaskRepository(db!!)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    private fun dayStart(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun window(from: String, to: String): Pair<Long, Long> =
        dayStart(from) to LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun minutes(m: Long) = m * 60_000L

    private val noon: Long get() = dayStart("2026-09-01") + 12 * 3_600_000L

    /** 直接插一段(不走切分),用于纯聚合用例 */
    private suspend fun insert(taskId: Long?, startAt: Long, mins: Long, profileId: Long = 1) {
        db!!.focusSessionDao().insertAll(
            listOf(
                FocusSessionEntity(
                    profileId = profileId, startAt = startAt, endAt = startAt + minutes(mins), taskId = taskId,
                )
            )
        )
    }

    /** 建一个时钟行(直插 dao:本类不关心创建校验);[archived] 模拟「归档时钟仍在库里」 */
    private suspend fun clock(name: String, archived: Boolean = false): Long =
        db!!.profileDao().insert(
            ProfileEntity(name = name, workMinutes = 25, restMinutes = 5, createdAt = 0, archived = archived)
        )

    private suspend fun breakdown(from: String, to: String): List<TaskSlice> {
        val (a, b) = window(from, to)
        return repo.taskBreakdown(a, b)
    }

    // ---------- 仓库层:走 focus_session 真表 ----------

    /** 行级口径不变:任务行时长/次数 == 其时钟明细之和(同一份段落派生) */
    @Test fun taskRowEqualsSumOfItsClockRows() = runTest {
        val a = tasks.create("A", 1)!!
        val p = clock("番茄")
        val q = clock("深度工作")
        insert(a, noon, 30, p)
        insert(a, noon, 60, q)
        insert(null, noon, 10)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf(minutes(90), minutes(10)), s.map { it.millis })
        assertEquals(s.map { it.millis }, s.map { row -> row.clocks.sumOf { it.millis } })
        assertEquals(s.map { it.count }, s.map { row -> row.clocks.sumOf { it.count } })
    }

    /** 通用时钟(taskId = NULL 的时钟)的记录按会话的 taskId 归到任务行下 */
    @Test fun genericClockRecordsGoToOwningTaskRow() = runTest {
        val a = tasks.create("A", 1)!!
        val generic = clock("通用时钟")
        insert(a, noon, 25, generic)
        val s = breakdown("2026-09-01", "2026-09-07")
        // 「未绑定」行恒在末位(2.1 既有口径),此处为 0 且无时钟明细
        assertEquals(listOf<Long?>(a, null), s.map { it.taskId })
        assertEquals(listOf("通用时钟" to minutes(25)), s.first().clocks.map { it.name to it.millis })
        assertEquals(emptyList<ClockSlice>(), s.last().clocks)
    }

    /** 归档时钟:行仍在库里(archived = 1),名字照常解析,不做过滤 */
    @Test fun archivedClockKeepsItsName() = runTest {
        val a = tasks.create("A", 1)!!
        val p = clock("归档时钟")
        insert(a, noon, 45, p)
        db!!.profileDao().archiveById(p)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf("归档时钟"), s.first().clocks.map { it.name })
        assertEquals(listOf(minutes(45)), s.first().clocks.map { it.millis })
    }

    /** 跨午夜:一次调用切成两段 → 任务行与时钟行都记 2 次,时长合计一致 */
    @Test fun crossMidnightKeepsTaskAndClockDetail() = runTest {
        val a = tasks.create("夜猫", 1)!!
        val p = clock("番茄")
        repo.recordWorkSession(
            p,
            dayStart("2026-09-01") + 23 * 3_600_000L + 30 * 60_000L,
            dayStart("2026-09-02") + 30 * 60_000L,
            zone,
            a,
        )
        val s = breakdown("2026-09-01", "2026-09-02")
        assertEquals(listOf(minutes(60), 0L), s.map { it.millis })
        assertEquals(listOf(2, 0), s.map { it.count })
        assertEquals(listOf("番茄" to minutes(60)), s.first().clocks.map { it.name to it.millis })
        assertEquals(listOf(2), s.first().clocks.map { it.count })
    }

    /** 未绑定任务行同样带自己的时钟明细 */
    @Test fun unboundRowCarriesItsClocks() = runTest {
        val generic = clock("通用时钟")
        insert(null, noon, 35, generic)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf<Long?>(null), s.map { it.taskId })
        assertEquals(listOf("通用时钟" to minutes(35)), s.first().clocks.map { it.name to it.millis })
    }
}
