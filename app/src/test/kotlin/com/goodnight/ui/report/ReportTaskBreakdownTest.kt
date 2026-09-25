package com.goodnight.ui.report

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.1 Task 8:报表「按任务」区块接入 VM(周/月有、时钟累计页签无;改名即时刷新)。
 * 聚合口径本身见 data/TaskBreakdownTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReportTaskBreakdownTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone: ZoneId = ZoneId.systemDefault()

    private suspend fun graph(name: String): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "rtb_$name")
        g.bootstrap()
        return g
    }

    private fun vm(g: AppGraph, date: String) =
        ReportViewModel(g, clock = { LocalDate.parse(date) })

    private fun minutes(m: Long) = m * 60_000L

    /** 本地日 00:00 + 当日偏移小时:与 VM 的 systemDefault 窗口口径一致 */
    private fun at(date: String, hour: Int, min: Int = 0): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli() +
            hour * 3_600_000L + min * 60_000L

    private suspend fun seed(g: AppGraph) {
        val p = g.profileRepo.create("番茄", 25, 5)!!
        val a = g.taskRepo.create("写报告", 1)!!
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 9), at("2026-09-01", 10), zone, a)
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 14), at("2026-09-01", 14, 30), zone, null)
        g.taskRepo.create("买菜", 2)!!
    }

    @Test fun weekReportShowsTaskBreakdownWithPercent() = runTest {
        val g = graph("week")
        seed(g)
        val v = vm(g, "2026-09-06")
        v.refresh()
        val s = v.ui.value.taskSlices
        assertEquals(listOf("写报告", null), s.map { it.title })
        assertEquals(listOf(minutes(60), minutes(30)), s.map { it.millis })
        assertEquals(listOf(1, 1), s.map { it.count })
        assertEquals(listOf(67, 33), s.map { it.percent })
        assertEquals(100, s.sumOf { it.percent })
    }

    @Test fun lifetimeTabHasNoTaskBreakdown() = runTest {
        val g = graph("lifetime")
        seed(g)
        val v = vm(g, "2026-09-06")
        v.setRange(ReportRange.LIFETIME)
        v.refresh()
        assertEquals(ReportRange.LIFETIME, v.ui.value.range)
        assertEquals(emptyList<TaskSliceUi>(), v.ui.value.taskSlices)
    }

    @Test fun outOfWindowSessionsAreExcluded() = runTest {
        val g = graph("out_window")
        val p = g.profileRepo.create("番茄", 25, 5)!!
        val a = g.taskRepo.create("旧任务", 1)!!
        g.totalsRepo.recordWorkSession(p, at("2026-08-25", 9), at("2026-08-25", 10), zone, a)
        val v = vm(g, "2026-09-06")
        v.refresh()
        assertEquals(emptyList<TaskSliceUi>(), v.ui.value.taskSlices)
    }

    @Test fun taskRenameRefreshesBreakdownTitle() = runTest {
        val g = graph("rename")
        val p = g.profileRepo.create("番茄", 25, 5)!!
        val a = g.taskRepo.create("旧名", 1)!!
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 9), at("2026-09-01", 10), zone, a)
        val v = vm(g, "2026-09-06")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("旧名", v.ui.value.taskSlices.first().title)
        // 任务页改名(不落新段、不 refresh):task 表失效通知驱动重解析
        g.taskRepo.rename(a, "新名")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("新名", v.ui.value.taskSlices.first().title)
    }
}
