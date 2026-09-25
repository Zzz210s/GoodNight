package com.goodnight.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** values-en 英文对照守卫:en 配置下报表/通知文案解析为英文(需资源不含 zh-only 过滤) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en", application = android.app.Application::class)
class EnResourcesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun reportCopyResolvesEnglish() {
        assertEquals("Reports", ctx.getString(R.string.report_title))
        assertEquals("Weekly", ctx.getString(R.string.tab_week))
        assertEquals("All-time totals", ctx.getString(R.string.total_lifetime))
        assertEquals("This week: 30m", ctx.getString(R.string.report_week_body, "30m"))
        assertEquals("1h 30m", ctx.getString(R.string.duration_hm, 1, 30))
        // v2.1 Task 7:计时页任务 chip 的未绑定文案(zh 对照在 HomeTaskChipTest)
        assertEquals("No task", ctx.getString(R.string.task_unbound))
        // v2.1 Task 8:报表「按任务」区块
        assertEquals("By task", ctx.getString(R.string.report_by_task))
        assertEquals("3 sessions · 45%", ctx.getString(R.string.report_task_meta, 3, 45))
        // v2.2 Task 6/7:时钟子行是「任务内」口径,与父行全窗口口径分开(zh 对照在 TaskBreakdownSectionTest)
        assertEquals("3 sessions · 45% of task", ctx.getString(R.string.report_clock_meta, 3, 45))
        // v2.2 Task 4:计时卡「任务 · 时钟」与换时钟确认(zh 对照在 ClockSwitchTest)
        assertEquals("%1\$s · %2\$s", ctx.getString(R.string.timer_chip_pair))
        assertEquals("Switch clock", ctx.getString(R.string.clock_switch_title))
        assertEquals(
            "Stop the current one and start the new one?",
            ctx.getString(R.string.clock_switch_confirm),
        )
        assertEquals("Shared clocks", ctx.getString(R.string.clock_group_generic))
    }
}
