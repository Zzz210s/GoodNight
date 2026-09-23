package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.timer.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 8:报表「按任务」分解(Robolectric 库名唯一,避免沙箱串扰)。
 *
 * 口径:窗口内 `focus_session` 行按段起点归属(与 [DailyTotalRepository.sessionsBetweenMs] 同口径),
 * `taskId == null` 或任务已删/未知 → 归「未绑定」并固定末位;其余按时长降序、同长按任务名升序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TaskBreakdownTest {
    private val dbName = "goodnight-task-breakdown.db"
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

    /** 某本地日 00:00(固定 Asia/Shanghai:跨午夜归属可确定,不随宿主机时区漂移) */
    private fun dayStart(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun window(from: String, to: String): Pair<Long, Long> =
        dayStart(from) to LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun minutes(m: Long) = m * 60_000L

    private val noon: Long get() = dayStart("2026-09-01") + 12 * 3_600_000L

    /** 直接插一段(不走切分),用于纯聚合用例 */
    private suspend fun insert(taskId: Long?, startAt: Long, mins: Long) {
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1, startAt = startAt, endAt = startAt + minutes(mins), taskId = taskId))
        )
    }

    private suspend fun breakdown(from: String, to: String): List<TaskSlice> {
        val (a, b) = window(from, to)
        return repo.taskBreakdown(a, b)
    }

    @Test fun sortsByMillisDescAndPutsUnboundLast() = runTest {
        val a = tasks.create("A", 1)!!
        val b = tasks.create("B", 2)!!
        val c = tasks.create("C", 3)!!
        insert(a, noon, 60)
        insert(b, noon, 30)
        insert(c, noon, 20)
        insert(null, noon, 10)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf(a, b, c, null), s.map { it.taskId })
        assertEquals(listOf("A", "B", "C", null), s.map { it.title })
        assertEquals(listOf(minutes(60), minutes(30), minutes(20), minutes(10)), s.map { it.millis })
        assertEquals(listOf(1, 1, 1, 1), s.map { it.count })
    }

    @Test fun unboundStaysLastEvenWhenEmpty() = runTest {
        val a = tasks.create("A", 1)!!
        insert(a, noon, 60)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf(a, null), s.map { it.taskId })
        assertEquals(listOf(minutes(60), 0L), s.map { it.millis })
        assertEquals(0, s.last().count)
    }

    @Test fun equalMillisSortsByTitleAscending() = runTest {
        val b = tasks.create("B", 1)!!
        val a = tasks.create("A", 2)!!
        insert(b, noon, 30)
        insert(a, noon, 30)
        assertEquals(listOf("A", "B", null), breakdown("2026-09-01", "2026-09-07").map { it.title })
    }

    /** 跨午夜:一次调用切成两段,两段同任务 → 一次聚合里合成一行、次数 = 切段后的行数 */
    @Test fun crossMidnightSegmentsCountAsTwoRows() = runTest {
        val a = tasks.create("夜猫", 1)!!
        repo.recordWorkSession(
            1L,
            dayStart("2026-09-01") + 23 * 3_600_000L + 30 * 60_000L,
            dayStart("2026-09-02") + 30 * 60_000L,
            zone,
            a,
        )
        val s = breakdown("2026-09-01", "2026-09-02")
        assertEquals(listOf(a, null), s.map { it.taskId })
        assertEquals(listOf(minutes(60), 0L), s.map { it.millis })
        assertEquals(listOf(2, 0), s.map { it.count })
    }

    /** 跨午夜行按段起点归属日:同一次记录在两天的单日窗口里各出现半段 */
    @Test fun crossMidnightRowsBelongToSegmentStartDay() = runTest {
        val a = tasks.create("夜猫", 1)!!
        repo.recordWorkSession(
            1L,
            dayStart("2026-09-01") + 23 * 3_600_000L + 30 * 60_000L,
            dayStart("2026-09-02") + 30 * 60_000L,
            zone,
            a,
        )
        val d1 = breakdown("2026-09-01", "2026-09-01")
        assertEquals(listOf(minutes(30), 0L), d1.map { it.millis })
        assertEquals(listOf(1, 0), d1.map { it.count })
        val d2 = breakdown("2026-09-02", "2026-09-02")
        assertEquals(listOf(minutes(30), 0L), d2.map { it.millis })
        assertEquals(listOf(1, 0), d2.map { it.count })
    }

    @Test fun deletedTaskFallsBackToUnbound() = runTest {
        val a = tasks.create("临时", 1)!!
        insert(a, noon, 45)
        tasks.deleteTask(a)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf<Long?>(null), s.map { it.taskId })
        assertEquals(listOf(minutes(45)), s.map { it.millis })
        assertEquals(listOf(1), s.map { it.count })
    }

    /** 兜底:悬挂 taskId(理论上删任务已置空)也归未绑定,不产出无名行 */
    @Test fun unknownTaskIdMergesIntoUnbound() = runTest {
        insert(999L, noon, 15)
        val s = breakdown("2026-09-01", "2026-09-07")
        assertEquals(listOf<Long?>(null), s.map { it.taskId })
        assertEquals(listOf(minutes(15)), s.map { it.millis })
        assertEquals(listOf(1), s.map { it.count })
    }

    @Test fun windowExcludesOtherDaysAndEmptyWindowReturnsEmpty() = runTest {
        val a = tasks.create("A", 1)!!
        insert(a, dayStart("2026-09-05") + 3_600_000L, 30)
        assertTrue(breakdown("2026-09-01", "2026-09-01").isEmpty())
        assertEquals(listOf(minutes(30), 0L), breakdown("2026-09-05", "2026-09-05").map { it.millis })
    }

    @Test fun percentagesSumToExactly100() {
        val tie = listOf(
            TaskSlice(1, "A", minutes(10), 1),
            TaskSlice(2, "B", minutes(10), 1),
            TaskSlice(3, "C", minutes(10), 1),
            TaskSlice(null, null, 0, 0),
        )
        val p = taskPercents(tie)
        assertEquals(100, p.sum())
        assertEquals(listOf(34, 33, 33, 0), p) // 余数相同按原顺序(时长降序)优先
        val skewed = listOf(
            TaskSlice(1, "A", minutes(7), 1),
            TaskSlice(2, "B", minutes(2), 1),
            TaskSlice(null, null, minutes(1), 1),
        )
        val p2 = taskPercents(skewed)
        assertEquals(100, p2.sum())
        assertEquals(listOf(70, 20, 10), p2)
        assertEquals(emptyList<Int>(), taskPercents(emptyList()))
    }
}
