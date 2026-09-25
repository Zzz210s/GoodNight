package com.goodnight.service

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.ui.settings.SettingsViewModel
import com.goodnight.ui.settings.commitScopeAndName
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.2 Task 7:通知标题带时钟名(「工作中 · 任务名 · 时钟名」)。
 *
 * 三件事分开钉:纯拼装规则([notifTitle],含「只说有的部分」的缺片段回退)、
 * 发布路径真的把时钟名写进通知、以及**不带名字的重发走缓存**不会退回「工作中」。
 * 断言读的是通知 extras(android.title),与真机 `dumpsys notification` 同一字段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = android.app.Application::class)
class NotifClockTitleTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "notif_clock_title_store")
    }

    @After fun tearDown() {
        runBlocking {
            g.appScope.coroutineContext.job.cancelAndJoin()
            runCatching { g.db.close() }
        }
    }

    private fun nm() = ctx.getSystemService(android.app.NotificationManager::class.java)

    private fun postedTitle(): String? =
        shadowOf(nm()).getNotification(TimerNotifications.ID_NOTIFY)
            ?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    /** 刷新在 appScope(Dispatchers.Default)上派发,非同步:轮询等标题变成 [want](同 ServiceNotifierTest) */
    private fun awaitTitle(want: String, timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (postedTitle() == want) return want
            Thread.sleep(25)
        }
        return postedTitle()
    }

    // ---- 拼装规则(纯函数) ----

    @Test fun titleJoinsOnlyPresentParts() {
        assertEquals("工作中 · 写周报 · 番茄", notifTitle("工作中", "写周报", "番茄"))
        assertEquals("工作中 · 写周报", notifTitle("工作中", "写周报", null))
        assertEquals("工作中 · 番茄", notifTitle("工作中", null, "番茄"))
        assertEquals("工作中", notifTitle("工作中", null, null))
        // 空白片段等同缺失(任务名解析成空串时不能出现「工作中 · 」)
        assertEquals("工作中 · 番茄", notifTitle("工作中", "   ", "番茄"))
        assertEquals("工作中", notifTitle("工作中", "", " "))
    }

    // ---- 发布路径 ----

    @Test fun postCarriesTaskAndClockName() = runBlocking {
        val taskId = g.taskRepo.create("写周报", now = 1L)!!
        val clockId = g.profileRepo.create("番茄", 25, 5, ProfileMode.COUNTDOWN, taskId)!!
        val snap = snapOf().copy(profileId = clockId, taskId = taskId)
        g.engine.restore(snap)
        val notifier = g.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)

        notifier.post(snap, notifier.titleFor(snap), notifier.clockFor(snap))
        assertEquals("工作中 · 写周报 · 番茄", postedTitle())
    }

    /** 未绑任务:只有时钟名(既有口径:只说有的部分) */
    @Test fun postFallsBackToClockNameOnlyWhenTaskUnbound() = runBlocking {
        val clockId = g.profileRepo.create("深度工作", 45, 15, ProfileMode.COUNTDOWN, null)!!
        val snap = snapOf().copy(profileId = clockId, taskId = null)
        g.engine.restore(snap)
        val notifier = g.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)

        notifier.post(snap, notifier.titleFor(snap), notifier.clockFor(snap))
        assertEquals("工作中 · 深度工作", postedTitle())
    }

    /** 时钟名解析不到(空库 / 快照里是已删时钟):保持 v2.1 行为,只有任务名 */
    @Test fun postKeepsTaskOnlyWhenClockUnresolvable() = runBlocking {
        val taskId = g.taskRepo.create("写周报", now = 1L)!!
        val snap = snapOf().copy(profileId = 9_999L, taskId = taskId)
        g.engine.restore(snap)
        val notifier = g.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)

        notifier.post(snap, notifier.titleFor(snap), notifier.clockFor(snap))
        assertEquals("工作中 · 写周报", postedTitle())
    }

    /**
     * 缓存必须能被强制失效:改名后不重查的话钳制重发会把旧名写回通知。
     * 直接测 [NotifTitles](不经异步重发),避免「轮询等 appScope 派发」那一类 flaky。
     */
    @Test fun refreshReReadsRenamedTaskAndClock() = runBlocking {
        val taskId = g.taskRepo.create("写周报", now = 1L)!!
        val clockId = g.profileRepo.create("番茄", 25, 5, ProfileMode.COUNTDOWN, taskId)!!
        val snap = snapOf().copy(profileId = clockId, taskId = taskId)
        val titles = NotifTitles(g.taskRepo, g.profileRepo)

        assertEquals("写周报", titles.resolve(snap).task)
        assertEquals("番茄", titles.resolve(snap).clock)
        g.taskRepo.rename(taskId, "写月报")
        g.profileRepo.rename(clockId, "番茄2")
        // 身份未变:普通 resolve 走缓存(旧名),这正是需要 refresh 的原因
        assertEquals("写周报", titles.resolve(snap).task)
        val fresh = titles.resolve(snap, refresh = true)
        assertEquals("写月报", fresh.task)
        assertEquals("番茄2", fresh.clock)
    }

    /**
     * 绑任务 + **通用**时钟:时钟归属与快照任务无关,标题仍按「任务 · 时钟」拼
     *(通用时钟是 v2.2 的默认形态,这条组合最容易在改动里漏掉)。
     */
    @Test fun postJoinsTaskAndGenericClock() = runBlocking {
        val taskId = g.taskRepo.create("写周报", now = 1L)!!
        val clockId = g.profileRepo.create("睡眠", 45, 15, ProfileMode.COUNTDOWN, null)!!
        val snap = snapOf().copy(profileId = clockId, taskId = taskId)
        g.engine.restore(snap)
        val notifier = g.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)

        notifier.post(snap, notifier.titleFor(snap), notifier.clockFor(snap))
        assertEquals("工作中 · 写周报 · 睡眠", postedTitle())
    }

    /**
     * v2.2 Task 7(复审修复 Important):**暂停中**在管理页改运行中时钟的名字,通知标题要同步更新。
     * 缓存 [NotifTitles] 按 (taskId, profileId) 身份记 —— 改名不动身份,漏了刷新的话每次重发
     *(阶段切换/到期钳制/前台化)都命中缓存分支,通知永远显示旧时钟名。
     * 暂停是可达路径:ProfilesScreen 只在 RUNNING 时置 runningActiveId,暂停中卡片可点。
     */
    @Test fun renamingPausedClockViaSettingsPageRefreshesNotification() = runBlocking {
        val clockId = g.profileRepo.create("番茄", 25, 5, ProfileMode.COUNTDOWN, null)!!
        val snap = snapOf(status = EngineStatus.PAUSED).copy(profileId = clockId)
        g.engine.restore(snap)
        val notifier = g.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)
        notifier.post(snap, notifier.titleFor(snap), notifier.clockFor(snap)) // 首次发布:缓存旧名
        assertEquals("工作中 · 番茄", postedTitle())

        val renamed = SettingsViewModel(g).commitScopeAndName(g.profileRepo.byId(clockId)!!, null, "深度工作")
        assertTrue("改名应当成功", renamed)

        assertEquals("工作中 · 深度工作", awaitTitle("工作中 · 深度工作"))
    }

    /** 不带名字的重发(到期钳制、前台化重发)必须从缓存拿回两段名字,不得退回「工作中」 */
    @Test fun resendWithoutNamesKeepsBothParts() = runBlocking {
        val taskId = g.taskRepo.create("写周报", now = 1L)!!
        val clockId = g.profileRepo.create("番茄", 25, 5, ProfileMode.COUNTDOWN, taskId)!!
        val snap = snapOf().copy(profileId = clockId, taskId = taskId)
        g.engine.restore(snap)
        val notifier = g.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)

        notifier.post(snap, notifier.titleFor(snap), notifier.clockFor(snap))
        nm().cancel(TimerNotifications.ID_NOTIFY)
        notifier.post(snap) // 无名字参数:只可能来自 NotifTitles 缓存
        assertEquals("工作中 · 写周报 · 番茄", postedTitle())
    }
}
