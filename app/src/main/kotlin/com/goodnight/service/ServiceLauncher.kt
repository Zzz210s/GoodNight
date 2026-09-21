package com.goodnight.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.goodnight.GoodNightApp
import com.goodnight.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

object ServiceLauncher {
    private const val TAG = "ServiceLauncher"

    /**
     * 后台(广播)起服务:优先 startForegroundService,被拒则退回 startService。
     * 返回:至少一次 start 调用被系统接受并返回(true 不代表服务必然完成启动,
     * 仅表示调用本身未抛异常);false = 两次尝试均被拒(如 Android 12+ 后台
     * FGS 启动限制)。失败必须记日志,不再静默吞掉(F1)。
     */
    fun ensureServiceRunning(context: Context): Boolean {
        val intent = Intent(context, TimerService::class.java)
        return try {
            context.startForegroundService(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService denied, falling back to startService", e)
            try {
                context.startService(intent)
                true
            } catch (e2: Exception) {
                Log.w(TAG, "startService fallback also denied", e2)
                false
            }
        }
    }
}

/**
 * 广播接收器共用骨架:goAsync -> 协程(SupervisorJob + Default) -> 8s awaitReady 超时
 * -> finally pending.finish()。超时/窗口纪律集中在此一处,receiver 只保留门控与领域逻辑。
 */
fun BroadcastReceiver.goAsyncWithGraph(
    context: Context,
    body: suspend (AppGraph) -> Unit,
) {
    val pending = goAsync()
    // 直调 onReceive(测试)时 mPendingResult 为 null:finish 判空,否则 finally 里的 NPE
    // 会以未捕获异常泄漏到全局收集器,砸中其后第一个 runTest(UncaughtExceptionsBeforeTest)
    val app = context.applicationContext as GoodNightApp
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            withTimeout(8_000) { app.graph.engine.awaitReady() }
            body(app.graph)
        } finally {
            pending?.finish()
        }
    }
}
