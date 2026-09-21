package com.goodnight.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.di.AppGraph
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 报表通知(v1.1 #5):
 * - 触发时刻纯函数:周日 23:00 / 月末 23:00,已过推下周/下月末;
 * - Receiver:区间合计 > 0 才发通知(文本走 values-en 英文对照),零记录不发;触发后重武装下一周期;
 * - 通知点击直达 extra 由 MainActivity 解析(单测层略,验收目检)。
 * 直调 onReceive(Robolectric 下 goAsync 安全);body 异步,副作用断言轮询等待。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = ReportAlarmReceiverTest.TestApp::class)
class ReportAlarmReceiverTest {
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private var graph: AppGraph? = null

    @Before fun setUp() {
        // 同一 Robolectric JVM 内其它测试类会改默认 Locale,导致本类文案断言跨类不稳 —— 每用例钉住中文
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        ctx = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() {
        val g = graph ?: return
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun graphFor(storeName: String, seed: suspend (AppGraph) -> Unit): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = storeName)
        (ctx as GoodNightApp).graph = g
        graph = g
        runBlocking {
            g.engine.restore(null)
            seed(g)
        }
        return g
    }

    private fun awaitCond(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
    }

    private fun posted(): List<android.app.Notification> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return listOf(
            shadowOf(nm).getNotification(TimerNotifications.ID_NOTIFY),
            shadowOf(nm).getNotification(TimerNotifications.ID_NOTIFY),
        ).filterNotNull()
    }

    @Test fun nextWeeklyTriggerIsUpcomingSunday() {
        // 2026-09-05 是周六;下个周日 23:00 = 2026-09-06 23:00
        val now = LocalDateTime.of(2026, 9, 5, 10, 0)
        assertEquals(LocalDateTime.of(2026, 9, 6, 23, 0), nextWeeklyTrigger(now))
        // 已过周日 23:00 -> 下一周日
        val sundayLate = LocalDateTime.of(2026, 9, 6, 23, 30)
        assertEquals(LocalDateTime.of(2026, 9, 13, 23, 0), nextWeeklyTrigger(sundayLate))
    }

    @Test fun nextMonthlyTriggerIsMonthEnd() {
        // 2026-09(30 天)中旬 -> 9/30 23:00
        val now = LocalDateTime.of(2026, 9, 10, 8, 0)
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 0), nextMonthlyTrigger(now))
        // 已过月末 -> 下月末(10/31 23:00)
        val late = LocalDateTime.of(2026, 9, 30, 23, 30)
        assertEquals(LocalDateTime.of(2026, 10, 31, 23, 0), nextMonthlyTrigger(late))
    }

    @Test fun weekReceiverPostsWhenTotalAboveZero() {
        val today = LocalDate.now()
        graphFor("report_rx_week") { g ->
            g.profileRepo.create("专注", 25, 5)
            g.totalsRepo.addWork(today.toString(), 1L, 30 * 60_000L)
        }
        ReportAlarmReceiver().onReceive(ctx, Intent(ReportAlarmActions.WEEK))
        // 断言取"带正文的那条"(同一 ID 上可能有其它通知残留/覆盖),避免跨类顺序导致的误判
        awaitCond { posted().any { it.extras?.getString(android.app.Notification.EXTRA_TEXT) != null } }
        val n = posted().first { it.extras?.getString(android.app.Notification.EXTRA_TEXT) != null }
        val txt = n.extras?.getString(android.app.Notification.EXTRA_TEXT)
        assertNotNull(txt)
        // Robolectric 默认 en-US:断言走 values-en(英文对照)
        assertTrue(txt!!.startsWith("本周累计 "))
        assertTrue(txt.contains("分钟"))
    }

    @Test fun monthReceiverUsesMonthLabel() {
        val today = LocalDate.now()
        graphFor("report_rx_month") { g ->
            g.profileRepo.create("专注", 25, 5)
            g.totalsRepo.addWork(today.toString(), 1L, 5 * 60_000L)
        }
        ReportAlarmReceiver().onReceive(ctx, Intent(ReportAlarmActions.MONTH))
        awaitCond { posted().isNotEmpty() }
        val txt = posted().first().extras?.getString(android.app.Notification.EXTRA_TEXT)
        assertTrue(txt!!.startsWith("本月累计 "))
    }

    @Test fun noRecordNoNotification() {
        graphFor("report_rx_empty") { g -> g.profileRepo.create("专注", 25, 5) }
        ReportAlarmReceiver().onReceive(ctx, Intent(ReportAlarmActions.WEEK))
        // 静置让异步 body 完成,再断言无任何报表通知
        Thread.sleep(400)
        assertNull(shadowOf(ctx.getSystemService(NotificationManager::class.java))
            .getNotification(TimerNotifications.ID_NOTIFY))
        assertNull(shadowOf(ctx.getSystemService(NotificationManager::class.java))
            .getNotification(TimerNotifications.ID_NOTIFY))
    }
}
