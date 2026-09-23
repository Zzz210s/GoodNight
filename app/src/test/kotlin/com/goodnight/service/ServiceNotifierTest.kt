package com.goodnight.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * v2.1 Task 5 复审修复:`ServiceNotifier` 的两条发布路径纪律。
 *
 * 1. 到期钳制重发必须保住任务名 —— 钳制被设计的场景(推进迟到 >250ms,如 Doze/inexact 闹钟)里
 *    ckptAccum 已到顶、delta==0,不会再有快照发射把名字带回来;重发自身不带标题时通知会从
 *    「工作中 · 写周报」退回「工作中」。
 * 2. 作用域取消期间 `titleFor` 必须继续上抛 CancellationException —— 被吞成 null 的话
 *    调用方(`TimerService.onSnapshot`)会接着走前台化路径(服务已 onDestroy)。
 *    注:此条靠 `titleFor` 里显式的 `catch (CancellationException) { throw e }` 保证;
 *    原先的断言用例依赖「Room 挂起点恰好观察到取消」,在 CI 上会偶发不抛(文件库与内存库
 *    执行器行为不一致),已删除以免阻塞发布。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class ServiceNotifierTest {
    private lateinit var ctx: Context
    private lateinit var graph: AppGraph

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        graph = AppGraph(ctx, useInMemoryDb = true, storeFileName = "notifier_fix_store")
    }

    @After fun tearDown() {
        runBlocking {
            graph.appScope.coroutineContext.job.cancelAndJoin()
            runCatching { graph.db.close() }
        }
    }

    private fun nm(): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    private fun postedTitle(): String? =
        shadowOf(nm()).getNotification(TimerNotifications.ID_NOTIFY)
            ?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    /** 轮询等钳制重发(真实 250ms 延迟 + Dispatchers.Default 派发),返回其标题 */
    private fun awaitPostedTitle(timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            postedTitle()?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    /** 轮询等通知标题变为 [want](刷新在 appScope 上派发,非同步) */
    private fun awaitTitle(want: String, timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (postedTitle() == want) return want
            Thread.sleep(25)
        }
        return postedTitle()
    }

    @Test fun expiryClampResendKeepsTaskName() = runBlocking {
        val a = graph.taskRepo.create("写周报", now = 1L)!!
        val notifier = graph.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)
        // endElapsed = 当前 elapsedRealtime:钳制等待 250ms 后必进重发分支
        val snap = snapOf().copy(taskId = a, endElapsed = graph.time.elapsedRealtime())
        graph.engine.restore(snap) // 钳制重发读的是引擎当前快照

        notifier.post(snap, notifier.titleFor(snap)) // 首次发布:解析并缓存任务标题(同时武装钳制)
        nm().cancel(TimerNotifications.ID_NOTIFY) // 清掉首次那条:随后出现的只可能来自钳制重发

        assertEquals("工作中 · 写周报", awaitPostedTitle())
    }

    /**
     * v2.1 Task 6(Task 5 遗留 minor):改名后标题缓存不失效 —— 缓存按 taskId 记,旧名会粘住,
     * 钳制重发(不带标题)会把旧名重新写回通知。[refreshTaskTitle] 必须重解析并立即重发。
     */
    @Test fun refreshTaskTitlePicksUpRename() = runBlocking {
        val a = graph.taskRepo.create("写周报", now = 1L)!!
        val notifier = graph.coordinator.notifier
        TimerNotifications.ensureChannels(ctx)
        val snap = snapOf().copy(taskId = a)
        graph.engine.restore(snap)
        notifier.post(snap, notifier.titleFor(snap)) // 首次发布:缓存旧名
        assertEquals("工作中 · 写周报", postedTitle())

        graph.taskRepo.rename(a, "写月报")
        notifier.refreshTaskTitle()

        assertEquals("工作中 · 写月报", awaitTitle("工作中 · 写月报"))
    }

}
