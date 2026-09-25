package com.goodnight.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.GoodNightApp
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 4:换时钟在协调器里的语义(设计 §4 拍板 1)——**同一把 mutex 内**先终止当前段
 * (Reset 事件按事件负载结算落库)再按新时钟开始;不新增切点类型。
 *
 * 断言:旧段按**旧时钟 + 旧任务**结算、旧段结束不晚于新段开始(段不重叠)、新快照是新时钟 +
 * 沿用任务、首次绑定不产生段内切点。单独成文件只为守住 [EngineCoordinatorTest] 的 200 行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ClockSwitchCommandTest.TestApp::class)
class ClockSwitchCommandTest {
    class TestApp : GoodNightApp() { override fun onCreate() { /* 跳过真实装配 */ } }

    private lateinit var ctx: Context
    private lateinit var app: GoodNightApp
    private var graph: AppGraph? = null

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        app = ctx as GoodNightApp
    }

    @After fun tearDown() {
        val g = graph ?: return
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun graphFor(store: String): AppGraph {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = store)
        app.graph = g
        graph = g
        g.coordinator.install()
        runBlocking { g.engine.restore(null) }
        runBlocking { g.coordinator.awaitReadyAndSubscribed() }
        return g
    }

    @Test fun switchClockSettlesCurrentSegmentThenStartsNewOne() = runBlocking {
        val g = graphFor("coord_switch_clock")
        val a = g.taskRepo.create("写周报", g.time.now())!!
        g.coordinator.run(
            TimerCommand(ACTION_START, profileId = 1L, workMillis = 600_000L, restMillis = 60_000L, taskId = a),
        )
        // 让本段已走五分钟(> 1 分钟误触阈值、且 > 3 分钟的 MIN_SPAN_MS 丢弃阈值):
        // 起点整体前移,段窗口 [now-300s, now]
        val started = g.engine.snapshot.value!!
        val now = g.time.now()
        g.engine.adoptRestored(
            started.copy(startElapsed = started.startElapsed - 300_000L, sessionStartWall = now - 300_000L),
        )

        g.coordinator.run(
            TimerCommand(
                ACTION_SWITCH_CLOCK, profileId = 2L, workMillis = 45 * 60_000L, restMillis = 15 * 60_000L,
                taskId = a,
            ),
        )

        val s = g.engine.snapshot.value!!
        assertEquals("新时钟", 2L, s.profileId)
        assertEquals(45 * 60_000L, s.workMillis)
        assertEquals("任务沿用当前绑定", a, s.taskId)
        assertEquals(EngineStatus.RUNNING, s.status)
        assertTrue("首次绑定 = 定义整段,不产生段内切点", s.taskCuts.isEmpty())

        // Reset 事件由事件收集器异步派发:轮询等旧段落地
        val from = now - 300_000L
        val old = withTimeout(5_000) {
            while (g.totalsRepo.sessionsBetweenMs(from, now + 60_000L).isEmpty()) delay(20)
            g.totalsRepo.sessionsBetweenMs(from, now + 60_000L).first()
        }
        assertEquals("旧段归旧时钟", 1L, old.profileId)
        assertEquals("旧段归旧任务", a, old.taskId)
        assertTrue(
            "先终止再开始:旧段结束 ${old.endAt} 不得晚于新段开始 ${s.sessionStartWall}",
            s.sessionStartWall != null && old.endAt <= s.sessionStartWall,
        )
        val settled = g.taskRepo.recordedMillis(a)
        assertTrue("旧段按实际时长落库(≈5 分钟,与误触/MIN_SPAN 阈值无关):$settled", settled in 300_000L..305_000L)
    }
}
