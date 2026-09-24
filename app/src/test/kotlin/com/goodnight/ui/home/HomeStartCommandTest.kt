package com.goodnight.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import com.goodnight.service.ACTION_START
import com.goodnight.service.EXTRA_PROFILE_ID
import com.goodnight.service.EXTRA_REST_MILLIS
import com.goodnight.service.EXTRA_TASK_ID
import com.goodnight.service.EXTRA_WORK_MILLIS
import com.goodnight.service.NO_TASK_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 评审 Important 回归:首页大「开始」键必须带上**选中时钟的归属任务**。
 *
 * 换时钟/任务卡片启动都会把选中时钟镜像写回 `activeProfileId`,于是 `activeProfileId` 可能指向一个
 * **任务专属**时钟。旧实现按 `activeProfileId` 启动却不传 `EXTRA_TASK_ID`,「任务卡片启动 -> 终止 ->
 * 首页开始」一键就产出「时钟属于任务 A、账记在未绑定任务」的会话 —— 设计只允许**通用**时钟走未绑定。
 *
 * 断言一律走 intent extra(VM -> TimerCommands -> 服务),不直接调引擎。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStartCommandTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var g: AppGraph

    /** 用例自报名字:DataStore 单例按文件名缓存,逐用例一份 */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "home_start_${testName.methodName}")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    @Test fun homeStartBindsTheTaskOwnedClock() = runTest {
        val a = g.task("写周报")
        val own = g.clock("A 专属", taskId = a)
        g.settingsRepo.setActiveProfile(own.id)

        HomeViewModel(g).startSelectedClock()

        val started = shadowOf(app).nextStartedService
        assertEquals(ACTION_START, started.action)
        assertEquals("时钟 = 选中的那个", own.id, started.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals("专属时钟:账记在它所属任务上", a, started.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        assertEquals(25 * 60_000L, started.getLongExtra(EXTRA_WORK_MILLIS, -1L))
        assertEquals(5 * 60_000L, started.getLongExtra(EXTRA_REST_MILLIS, -1L))
    }

    @Test fun homeStartLeavesGenericClockUnbound() = runTest {
        val shared = g.clock("通用 25/5")
        g.settingsRepo.setActiveProfile(shared.id)

        HomeViewModel(g).startSelectedClock()

        val started = shadowOf(app).nextStartedService
        assertEquals(shared.id, started.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals("通用时钟才允许未绑定", NO_TASK_ID, started.getLongExtra(EXTRA_TASK_ID, -1L))
    }

    /** 一键错归属的原始链路:专属时钟起画 -> 终止(引擎回空闲、首页高亮不变)-> 首页开始 */
    @Test fun homeStartAfterChipStartAndStopStillBindsTheTask() = runTest {
        val a = g.task("写周报")
        val own = g.clock("A 专属", taskId = a)
        g.settingsRepo.setActiveProfile(own.id)
        g.engine.restore(runningSnap(own.id, taskId = a))
        g.engine.reset() // 终止:快照清空,回到空闲

        HomeViewModel(g).startSelectedClock()

        val started = shadowOf(app).nextStartedService
        assertEquals(own.id, started.getLongExtra(EXTRA_PROFILE_ID, -1L))
        assertEquals("终止后首页开始不得丢掉任务归属", a, started.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
    }

    /** 空库(没有可选时钟):一条命令都不发 */
    @Test fun homeStartSendsNothingWhenNoClockExists() = runTest {
        HomeViewModel(g).startSelectedClock()

        assertNull(shadowOf(app).nextStartedService)
    }
}
