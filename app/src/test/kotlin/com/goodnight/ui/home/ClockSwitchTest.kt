package com.goodnight.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.di.AppGraph
import com.goodnight.service.ACTION_SWITCH_CLOCK
import com.goodnight.service.EXTRA_COUNT_UP
import com.goodnight.service.EXTRA_PROFILE_ID
import com.goodnight.service.EXTRA_REST_MILLIS
import com.goodnight.service.EXTRA_TASK_ID
import com.goodnight.service.EXTRA_WORK_MILLIS
import com.goodnight.service.NO_TASK_ID
import com.goodnight.ui.tasks.PendingClockSwitch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.2 Task 4:计时中换时钟的**确认流程** —— 先确认,未确认不发任何命令;确认后发一条换时钟命令。
 *
 * 命令一律用 intent 断言(`shadowOf(app).nextStartedService`):服务 -> 协调器 -> 引擎,不直接调引擎。
 * 显示口径与选择器作用域的用例见 [ClockSwitchDisplayTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ClockSwitchTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var g: AppGraph

    /** 用例自报名字,给每张图一个**唯一的 store 文件名** */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        // 不跑 bootstrap():restore 同步置 ready,测试里没有「异步恢复覆盖快照」的竞态。
        // DataStore 单例按文件名缓存,同类多个用例共用文件名会撞 multiple DataStores active,故逐用例一份
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "clock_switch_${testName.methodName}")
        runBlocking { g.engine.restore(null) }
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = HomeViewModel(g)

    @Test fun pickingAnotherClockWhileRunningAsksBeforeAnyCommand() = runTest {
        val focus = g.clock("专注")
        val deep = g.clock("深度")
        val a = g.task("写周报")
        val model = vm()
        g.engine.restore(runningSnap(focus.id, taskId = a))

        model.onPickClock(deep)

        assertEquals(
            "待确认:将要开始的时钟 + 新会话沿用的任务绑定",
            PendingClockSwitch(deep, a), model.pendingClockSwitch.value,
        )
        assertNull("未确认前不得发出任何命令", shadowOf(app).nextStartedService)
    }

    @Test fun confirmingSendsOneSwitchCommandWithNewClockAndBoundTask() = runTest {
        val focus = g.clock("专注")
        val deep = g.clock("深度", mode = 1 /* COUNTUP */)
        val a = g.task("写周报")
        val model = vm()
        g.engine.restore(runningSnap(focus.id, taskId = a))
        model.onPickClock(deep)

        model.onConfirmClockSwitch()

        val cmd = shadowOf(app).nextStartedService
        assertEquals(ACTION_SWITCH_CLOCK, cmd.action)
        assertEquals("时钟换成新的", deep.id, cmd.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals("任务沿用当前绑定", a, cmd.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        assertEquals(25 * 60_000L, cmd.getLongExtra(EXTRA_WORK_MILLIS, -1L))
        assertEquals(5 * 60_000L, cmd.getLongExtra(EXTRA_REST_MILLIS, -1L))
        assertTrue("时钟模式照抄(正计时)", cmd.getBooleanExtra(EXTRA_COUNT_UP, false))
        assertNull("确认后不再有待确认请求", model.pendingClockSwitch.value)
        assertNull("一条命令内完成「终止 + 开始」,不得再补第二条", shadowOf(app).nextStartedService)
        assertEquals(
            "换时钟后同步写回首页高亮(指针 1)",
            deep.id, g.settingsRepo.activeProfileId.first { it == deep.id },
        )
    }

    @Test fun dismissingSendsNothingAndKeepsRunningClock() = runTest {
        val focus = g.clock("专注")
        val deep = g.clock("深度")
        val model = vm()
        g.engine.restore(runningSnap(focus.id))
        model.onPickClock(deep)

        model.onDismissClockSwitch()

        assertNull(model.pendingClockSwitch.value)
        assertNull("取消 = 零命令(不终止、不开始)", shadowOf(app).nextStartedService)
        assertEquals("运行时钟未变", focus.id, g.engine.snapshot.value!!.profileId)
    }

    @Test fun pickingTheRunningClockNeedsNoConfirmation() = runTest {
        val focus = g.clock("专注")
        val model = vm()
        g.engine.restore(runningSnap(focus.id))

        model.onPickClock(focus)

        assertNull("点自己正在跑的时钟:不弹确认", model.pendingClockSwitch.value)
        assertNull("也不发命令", shadowOf(app).nextStartedService)
    }

    /** 空闲点时钟 = 只改选(与顶栏面板选中同语义),不起画、不发命令;并镜像写回首页高亮(指针 1) */
    @Test fun pickingClockWhileIdleOnlySelectsIt() = runTest {
        val focus = g.clock("专注")
        val deep = g.clock("深度")
        g.settingsRepo.setActiveProfile(focus.id)
        val model = vm()

        model.onPickClock(deep)

        assertNull("空闲点时钟不发命令", shadowOf(app).nextStartedService)
        assertEquals("选中镜像写回首页高亮", deep.id, g.settingsRepo.activeProfileId.first { it == deep.id })
        assertNull("空闲点时钟不起画", g.engine.snapshot.value)
    }

    /** 确认文案(zh;en 对照在 EnResourcesTest) */
    @Test fun confirmCopyIsChinese() {
        assertEquals("换时钟", ctx.getString(R.string.clock_switch_title))
        assertEquals("终止当前并开始新的?", ctx.getString(R.string.clock_switch_confirm))
        assertEquals("通用时钟", ctx.getString(R.string.clock_group_generic))
    }
}
