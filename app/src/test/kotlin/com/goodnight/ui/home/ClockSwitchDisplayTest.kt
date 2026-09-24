package com.goodnight.ui.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * v2.2 Task 4:计时卡**显示的时钟**与选择器**作用域**(指针 1)。
 *
 * 1. 有会话取运行快照的时钟(真值),空闲取将要用哪个时钟;
 * 2. 选择器里的时钟 = 当前绑定任务的作用域时钟(该任务专属 + 通用),别的任务的时钟不进这一层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ClockSwitchDisplayTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "clock_switch_disp_${testName.methodName}")
        runBlocking { g.engine.restore(null) }
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = HomeViewModel(g)

    /**
     * `ui` 是 5 条流的 combine:DataStore 分支从别的线程发射,而 `stateIn(viewModelScope = Main)`
     * 的恢复要过主 looper —— Robolectric 主 looper 默认暂停,所以手动泵几下再读 `.value`。
     */
    private fun pumpUntil(cond: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    @Test fun displayedClockFollowsRunningSnapshotThenIdleSelection() = runTest {
        val focus = g.clock("专注")
        val deep = g.clock("深度")
        g.settingsRepo.setActiveProfile(focus.id)
        val model = vm()
        // WhileSubscribed(5s):没有订阅者时上游根本不收集,`.value` 会一直停在初始值 ——
        // 必须先挂一个收集者(Unconfined 立即订阅,才能起 WhileSubscribed 的上游)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.ui.collect {} }
        assertTrue("空闲:显示将要用哪个时钟", pumpUntil { model.ui.value.clockId == focus.id })

        g.engine.restore(runningSnap(deep.id))

        assertTrue(
            "运行:显示运行快照的时钟(而非首页高亮)",
            pumpUntil { model.ui.value.clockId == deep.id },
        )
        assertEquals("首页高亮可以仍是旧选中,显示不受它影响", focus.id, model.ui.value.activeProfileId)
        job.cancel()
    }

    /** 选择器里的时钟 = 当前绑定任务的作用域时钟(专属 + 通用);别的任务的时钟不进这一层 */
    @Test fun availableClocksAreScopedToTheBoundTask() = runTest {
        val a = g.task("写周报")
        val b = g.task("读论文")
        val ownA = g.clock("A 专属", taskId = a)
        val ownB = g.clock("B 专属", taskId = b)
        val shared = g.clock("通用 25/5")
        val model = vm()

        g.engine.restore(runningSnap(shared.id, taskId = a))
        val bound = model.availableClocks.first { it.specific.isNotEmpty() }
        assertEquals(listOf(ownA), bound.specific)
        assertEquals(listOf(shared), bound.generic)
        assertTrue("别的任务的专属时钟不进选择器", bound.all.none { it.id == ownB.id })

        g.engine.restore(null)
        val idle = model.availableClocks.first { it.specific.isEmpty() && it.generic.isNotEmpty() }
        assertEquals("空闲(未绑任务):只有通用时钟", listOf(shared), idle.generic)
    }
}
