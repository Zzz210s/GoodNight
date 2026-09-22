package com.goodnight.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import kotlinx.coroutines.CancellationException
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class ServiceNotifierTest {
    private lateinit var ctx: Context
    private lateinit var graph: AppGraph
    private var fileGraph: AppGraph? = null

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        graph = AppGraph(ctx, useInMemoryDb = true, storeFileName = "notifier_fix_store")
    }

    @After fun tearDown() {
        runBlocking {
            graph.appScope.coroutineContext.job.cancelAndJoin()
            runCatching { graph.db.close() }
            fileGraph?.let { g ->
                g.appScope.coroutineContext.job.cancelAndJoin()
                runCatching { g.db.close() }
            }
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

    @Test fun titleForRethrowsCancellationInsteadOfSwallowingIt() = runBlocking {
        // 必须用**文件库**(默认执行器):测试路径的内存库走直通执行器,Room 把查询跑在调用线程上、
        // 不观察取消 —— 取消异常根本不会出现,断言就失去意义(实测:同一取消协程里
        // delay 报 cancelled、内存库的 DAO 却正常返回)。
        val g = AppGraph(ctx, useInMemoryDb = false, storeFileName = "notifier_cancel_store")
        fileGraph = g
        val a = g.taskRepo.create("写周报", now = 1L)!!
        val caught = CompletableDeferred<Throwable?>()
        CoroutineScope(Job() + Dispatchers.Default).launch {
            coroutineContext.job.cancel() // 等价于服务 onDestroy 的 scope.cancel()
            caught.complete(
                try {
                    g.coordinator.notifier.titleFor(snapOf().copy(taskId = a))
                    null
                } catch (e: CancellationException) {
                    e
                }
            )
        }
        val e = withTimeoutOrNull(5_000) { caught.await() }
        assertTrue("取消异常必须上抛,不能被吞成 null", e is CancellationException)
    }
}
