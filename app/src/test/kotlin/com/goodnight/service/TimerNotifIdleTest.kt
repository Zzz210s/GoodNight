package com.goodnight.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.R
import com.goodnight.data.ProfileRemoval
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5 复审修复:空闲常驻通知只能挂**活跃**时钟。
 *
 * 归档行仍在库里(历史解析要用它的名字),但归档语义 = 从列表/首页隐藏;通知若还显示它并保留
 * 可点的「启动」按钮,按下去就是 ACTION_START + 已归档 id —— v2.2 之前这里是真删(行没了 →
 * 按钮 GONE),所以那是归档引入的功能回退。
 *
 * 断言落到**真通知的标题**(= 解析出的时钟名):回退口径与 HomeViewModel 的选中口径一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = TimerNotifIdleTest.TestApp::class)
class TimerNotifIdleTest {
    /** 空 onCreate:绕过 GoodNightApp 真实装配,注入受控 AppGraph */
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var app: GoodNightApp
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        app = ctx as GoodNightApp
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "idle_notif_${testName.methodName}")
        app.graph = g
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private suspend fun clock(name: String) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN)!!)!!

    /** 归档:先造一段历史(有引用才归档),再走仓库的 removeOrArchive */
    private suspend fun archive(id: Long) {
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = id, startAt = 0, endAt = 25 * 60_000L))
        )
        assertEquals(ProfileRemoval.Archived, g.profileRepo.removeOrArchive(id))
    }

    private fun postedTitle(): String? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return shadowOf(nm).getNotification(TimerNotifications.ID_NOTIFY)
            ?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
    }

    /** showIdle 在 appScope(Dispatchers.Default)上派发,轮询等通知落地 */
    private fun awaitTitle(want: String, timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            postedTitle()?.takeIf { it == want }?.let { return it }
            Thread.sleep(25)
        }
        return postedTitle()
    }

    @Test fun idleNotificationSkipsArchivedSelectedClock() = runBlocking {
        val live = clock("在用")
        val dead = clock("旧")
        archive(dead.id)
        g.settingsRepo.setActiveProfile(dead.id)

        TimerNotifIdle.showIdle(ctx)

        assertEquals("选中项已归档 → 回退到第一个活跃时钟", live.name, awaitTitle(live.name))
    }

    @Test fun idleNotificationWithoutActiveClocksShowsPlaceholder() = runBlocking {
        val dead = clock("旧")
        archive(dead.id)
        g.settingsRepo.setActiveProfile(dead.id)

        TimerNotifIdle.showIdle(ctx)

        val placeholder = ctx.getString(R.string.unselected_placeholder)
        assertEquals("一个活跃时钟都没有 → 占位文案(启动按钮 GONE)", placeholder, awaitTitle(placeholder))
    }
}
