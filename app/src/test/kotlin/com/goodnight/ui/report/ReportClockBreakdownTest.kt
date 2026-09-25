package com.goodnight.ui.report

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 6:报表「按任务」区块的**时钟明细**接入 VM。
 *
 * 聚合口径见 data/TaskBreakdownTest;这里钉住三件事:
 * 1. 每个任务行带出各自时钟的时长/次数/占比(占比按任务内合计 100);
 * 2. 通用时钟按**会话的 taskId** 归属任务,未绑任务进「未绑定任务」行;
 * 3. 归档时钟(profile 行保留、列表隐藏)名字仍要解析出来,不能显示成空白。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReportClockBreakdownTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone: ZoneId = ZoneId.systemDefault()

    private suspend fun graph(name: String): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "rcb_$name")
        g.bootstrap()
        return g
    }

    private fun vm(g: AppGraph, date: String) =
        ReportViewModel(g, clock = { LocalDate.parse(date) })

    private fun minutes(m: Long) = m * 60_000L

    private fun at(date: String, hour: Int, min: Int = 0): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli() +
            hour * 3_600_000L + min * 60_000L

    @Test fun eachTaskRowCarriesItsClockDetailWithPercent() = runTest {
        val g = graph("clocks")
        val p = g.profileRepo.create("番茄", 25, 5)!!
        val q = g.profileRepo.create("深度工作", 50, 10)!!
        val a = g.taskRepo.create("写报告", 1)!!
        g.totalsRepo.recordWorkSession(q, at("2026-09-01", 9), at("2026-09-01", 10, 30), zone, a)
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 14), at("2026-09-01", 14, 30), zone, a)
        val v = vm(g, "2026-09-06")
        v.refresh()
        val row = v.ui.value.taskSlices.first { it.taskId == a }
        assertEquals(minutes(120), row.millis)
        assertEquals(listOf("深度工作", "番茄"), row.clocks.map { it.name })
        assertEquals(listOf(minutes(90), minutes(30)), row.clocks.map { it.millis })
        assertEquals(listOf(1, 1), row.clocks.map { it.count })
        assertEquals(listOf(75, 25), row.clocks.map { it.percent })
        assertEquals(100, row.clocks.sumOf { it.percent })
    }

    @Test fun unboundRowCarriesClocksOfTasklessSessions() = runTest {
        val g = graph("unbound")
        val p = g.profileRepo.create("番茄", 25, 5)!!
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 9), at("2026-09-01", 10), zone, null)
        val v = vm(g, "2026-09-06")
        v.refresh()
        val s = v.ui.value.taskSlices
        assertEquals(listOf(null), s.map { it.taskId })
        assertEquals(listOf("番茄"), s.first().clocks.map { it.name })
        assertEquals(listOf(100), s.first().clocks.map { it.percent })
    }

    @Test fun archivedClockStillNamedInClockDetail() = runTest {
        val g = graph("archived")
        val p = g.profileRepo.create("老时钟", 25, 5)!!
        val a = g.taskRepo.create("写报告", 1)!!
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 9), at("2026-09-01", 10), zone, a)
        assertTrue("删时钟(有历史引用)走归档,行保留", g.profileRepo.removeOrArchive(p) == com.goodnight.data.ProfileRemoval.Archived)
        val v = vm(g, "2026-09-06")
        v.refresh()
        val row = v.ui.value.taskSlices.first { it.taskId == a }
        assertEquals(listOf("老时钟"), row.clocks.map { it.name })
        assertEquals(minutes(60), row.clocks.first().millis)
    }

    /** 跨午夜:一条会话切成两段,任务行与时钟行都记 2 次,占比仍按本任务合计 100 */
    @Test fun crossMidnightSessionIsCountedTwiceForTaskAndClock() = runTest {
        val g = graph("cross_midnight")
        val p = g.profileRepo.create("番茄", 25, 5)!!
        val a = g.taskRepo.create("夜猫", 1)!!
        g.totalsRepo.recordWorkSession(
            p,
            at("2026-09-01", 23, 30),
            at("2026-09-02", 0, 30),
            zone,
            a,
        )
        val v = vm(g, "2026-09-06")
        v.refresh()
        val row = v.ui.value.taskSlices.first { it.taskId == a }
        assertEquals(minutes(60), row.millis)
        assertEquals(2, row.count)
        assertEquals(listOf(2), row.clocks.map { it.count })
        assertEquals(listOf(100), row.clocks.map { it.percent })
    }

    /** 时钟累计页签没有按任务区块,也就没有时钟明细(与 2.1 的 taskSlices 口径一致) */
    @Test fun lifetimeTabHasNoClockDetail() = runTest {
        val g = graph("lifetime")
        val p = g.profileRepo.create("番茄", 25, 5)!!
        val a = g.taskRepo.create("写报告", 1)!!
        g.totalsRepo.recordWorkSession(p, at("2026-09-01", 9), at("2026-09-01", 10), zone, a)
        val v = vm(g, "2026-09-06")
        v.setRange(ReportRange.LIFETIME)
        v.refresh()
        assertEquals(emptyList<TaskSliceUi>(), v.ui.value.taskSlices)
    }
}
