package com.goodnight.ui.report

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
// 绕过 GoodNightApp 真实装配,保持测试封闭(同进程同 DataStore 文件多实例会抛异常,见 SettingsStoreTest 头注)
@Config(sdk = [34], application = android.app.Application::class)
class ReportViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun graph(name: String): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "rv_$name")
        g.bootstrap()
        return g
    }

    private fun vm(g: AppGraph, date: String) =
        ReportViewModel(g, clock = { LocalDate.parse(date) })

    private fun rowMillis(vararg minutes: Long) = minutes.map { it * 60_000L }

    @Test fun weeklySundayIncludesWholeIsoWeek() = runTest {
        val g = graph("week_sun")
        val a = g.profileRepo.create("番茄", 25, 5)
        val b = g.profileRepo.create("深度", 50, 10)
        g.totalsRepo.addWork("2026-08-31", a, 60 * 60_000L) // 周一
        g.totalsRepo.addWork("2026-09-05", a, 60 * 60_000L) // 周六
        g.totalsRepo.addWork("2026-09-05", b, 30 * 60_000L)
        g.totalsRepo.addWork("2026-08-30", a, 60 * 60_000L) // 上周日:界外
        g.totalsRepo.addWork("2026-09-07", a, 60 * 60_000L) // 下周一:界外
        val v = vm(g, "2026-09-06") // 周日锚定本周一~今天
        v.refresh()
        val ui = v.ui.value
        assertEquals(listOf("08-31", "09-05"), ui.rows.map { it.label })
        assertEquals(rowMillis(60, 90), ui.rows.map { it.millis }) // 09-05 双配置 60+30
        assertEquals(listOf("番茄", "深度"), ui.profileTotals.map { it.profileName })
        assertEquals(rowMillis(120, 30), ui.profileTotals.map { it.millis })
    }

    @Test fun weeklyMondayStartsAtToday() = runTest {
        val g = graph("week_mon")
        val a = g.profileRepo.create("番茄", 25, 5)
        g.totalsRepo.addWork("2026-08-31", a, 60 * 60_000L)
        g.totalsRepo.addWork("2026-08-30", a, 60 * 60_000L) // 上周日:界外
        val v = vm(g, "2026-08-31")
        v.refresh()
        assertEquals(listOf("08-31"), v.ui.value.rows.map { it.label })
        assertEquals(60 * 60_000L, v.ui.value.rows[0].millis)
    }

    @Test fun monthlyAggregatesByWeekWithTailClippedToToday() = runTest {
        val g = graph("month_bucket")
        val a = g.profileRepo.create("番茄", 25, 5)
        val b = g.profileRepo.create("深度", 50, 10)
        g.totalsRepo.addWork("2026-09-02", a, 60 * 60_000L)
        g.totalsRepo.addWork("2026-09-10", a, 30 * 60_000L)
        g.totalsRepo.addWork("2026-09-25", b, 45 * 60_000L)
        val v = vm(g, "2026-09-25")
        v.setRange(ReportRange.MONTH)
        v.refresh()
        val ui = v.ui.value
        assertEquals(
            listOf("第 1 周(09-01~09-07)", "第 2 周(09-08~09-14)", "第 4 周(09-22~09-25)"),
            ui.rows.map { it.label },
        )
        assertEquals(rowMillis(60, 30, 45), ui.rows.map { it.millis })
        assertEquals(listOf("番茄", "深度"), ui.profileTotals.map { it.profileName })
        assertEquals(rowMillis(90, 45), ui.profileTotals.map { it.millis })
    }

    @Test fun monthlyExcludesPreviousMonth() = runTest {
        val g = graph("month_boundary")
        val a = g.profileRepo.create("番茄", 25, 5)
        g.totalsRepo.addWork("2026-09-01", a, 30 * 60_000L)
        g.totalsRepo.addWork("2026-09-02", a, 15 * 60_000L)
        g.totalsRepo.addWork("2026-08-31", a, 60 * 60_000L) // 上月:界外
        val v = vm(g, "2026-09-02")
        v.setRange(ReportRange.MONTH)
        v.refresh()
        assertEquals(listOf("第 1 周(09-01~09-02)"), v.ui.value.rows.map { it.label })
        assertEquals(45 * 60_000L, v.ui.value.rows[0].millis)
    }

    @Test fun emptyWindowShowsEmptyState() = runTest {
        val g = graph("empty")
        g.profileRepo.create("番茄", 25, 5)
        g.profileRepo.create("深度", 50, 10)
        val v = vm(g, "2026-09-06")
        v.setRange(ReportRange.MONTH)
        v.refresh()
        assertEquals(ReportRange.MONTH, v.ui.value.range)
        assertEquals(0, v.ui.value.rows.size)
        assertEquals(0, v.ui.value.profileTotals.size) // 零时长的配置不列
        v.setRange(ReportRange.WEEK)
        v.refresh()
        assertEquals(ReportRange.WEEK, v.ui.value.range)
        assertEquals(0, v.ui.value.rows.size)
    }

    @Test fun refreshAfterNewRecord() = runTest {
        val g = graph("live")
        val a = g.profileRepo.create("番茄", 25, 5)
        g.totalsRepo.addWork("2026-09-01", a, 30 * 60_000L)
        val v = vm(g, "2026-09-06")
        v.refresh()
        assertEquals(1, v.ui.value.rows.size)
        // 新日期记录落库后再次 refresh:窗口与配置合计都跟上(生产路径由 Room 失效流自动触发)
        g.totalsRepo.addWork("2026-09-02", a, 20 * 60_000L)
        v.refresh()
        assertEquals(listOf("09-01", "09-02"), v.ui.value.rows.map { it.label })
        assertEquals(rowMillis(30, 20), v.ui.value.rows.map { it.millis })
    }

    @Test fun deletedProfileDisappearsFromTotals() = runTest {
        // v1.10.8:删除配置 -> 级联清段落/合计 -> 报表各时钟合计里不再出现该(或"已删除")行
        val g = graph("orphan")
        val a = g.profileRepo.create("番茄", 25, 5)
        g.totalsRepo.addWork("2026-09-01", a, 60 * 60_000L)
        val v = vm(g, "2026-09-06")
        v.refresh()
        assertEquals(listOf("番茄"), v.ui.value.profileTotals.map { it.profileName })
        g.profileRepo.delete(g.profileRepo.byId(a)!!)
        g.totalsRepo.deleteProfileData(a)
        v.refresh()
        assertEquals(emptyList<String>(), v.ui.value.profileTotals.map { it.profileName })
    }

    // ---- 生产自动刷新路径(init 的 combine/auto collect,不经显式 refresh)----
    // Robolectric 下测试线程即主线程,viewModelScope 的 Main.immediate + 直通 executor 使
    // Room 失效通知内联完成;idle() 兜底任何经主 looper 的派发(约定见 review R1 之二)。

    @Test fun autoRefreshShowsNewRecordWithoutExplicitRefresh() = runTest {
        val g = graph("auto_record")
        val a = g.profileRepo.create("番茄", 25, 5)
        val v = vm(g, "2026-09-06")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, v.ui.value.rows.size)
        // 记录落库后无任何 refresh() 调用:dayTotals 失效信号驱动自动重建
        g.totalsRepo.addWork("2026-09-01", a, 30 * 60_000L)
        g.totalsRepo.addWork("2026-09-02", a, 20 * 60_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("09-01", "09-02"), v.ui.value.rows.map { it.label })
        assertEquals(rowMillis(30, 20), v.ui.value.rows.map { it.millis })
        assertEquals(listOf("番茄"), v.ui.value.profileTotals.map { it.profileName })
    }

    @Test fun autoRefreshReResolvesRenamedProfile() = runTest {
        val g = graph("auto_rename")
        val a = g.profileRepo.create("番茄", 25, 5)
        g.totalsRepo.addWork("2026-09-01", a, 30 * 60_000L)
        val v = vm(g, "2026-09-06") // 常驻 VM:先开报表后 footer 已含旧名
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("番茄"), v.ui.value.profileTotals.map { it.profileName })
        // 回设置页改名(只动 profile 表,不落 daily_total),期间无新记录:profiles 并入 combine
        // 后自动重解析名称,再进报表无需等待下一次记录或切范围
        g.profileRepo.rename(a, "改名")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("改名"), v.ui.value.profileTotals.map { it.profileName })
        assertEquals(30 * 60_000L, v.ui.value.profileTotals[0].millis)
    }

    @Test fun autoRefreshDropsDeletedProfileTotals() = runTest {
        val g = graph("auto_orphan")
        val a = g.profileRepo.create("番茄", 25, 5)
        g.totalsRepo.addWork("2026-09-01", a, 30 * 60_000L)
        val v = vm(g, "2026-09-06")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("番茄"), v.ui.value.profileTotals.map { it.profileName })
        // 设置页删除配置(无新记录、无 refresh):该行应自动消失(Room 失效通知驱动)
        g.profileRepo.delete(g.profileRepo.byId(a)!!)
        g.totalsRepo.deleteProfileData(a)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), v.ui.value.profileTotals.map { it.profileName })
    }
}
