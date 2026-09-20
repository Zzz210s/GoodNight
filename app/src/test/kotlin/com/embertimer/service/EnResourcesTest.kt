package com.embertimer.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.embertimer.R
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
    }
}
