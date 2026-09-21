package com.goodnight.service

import android.util.Log
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 到期驱动主腿(v1.3 拆分):1s 轮询到期推进 + 60s 检查点落账,引擎锁内执行。
 * #4 加固:单次迭代内任何异常都不得杀死循环(ticker 死亡后到期推进只剩闹钟/对账两腿;
 * 闹钟未授权退化为 inexact 时 Doze 下可被大幅推迟 → “完成卡住”)。捕获记日志后继续
 * 调度(自愈);CancellationException 原样上抛(onDestroy 取消作用域的正常退出路径)。
 */
internal class TickDriver(
    private val graph: AppGraph,
    private val ledger: TickLedger,
    private val mutex: Mutex,
) {
    fun loop(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try {
                // v1.14.0:疲劳提醒在锁内只做判定(DB 读),投递放到锁外(避免 binder 调用拉长持锁)
                val fatigue = mutex.withLock {
                    val snap = graph.engine.snapshot.value
                    val now = graph.time.elapsedRealtime()
                    if (snap != null && snap.status == EngineStatus.RUNNING &&
                        snap.endElapsed <= now
                    ) {
                        // 迟到量 = 系统 Chronometer 已在通知栏显示负数的时长
                        com.goodnight.diag.DiagLog.add(
                            "Tick",
                            "ticker 到期推进 迟到=${now - snap.endElapsed}ms " +
                                "${com.goodnight.diag.DiagLog.env()} 相位=${snap.phase}",
                        )
                        graph.engine.onExpired()
                    }
                    ledger.flush(graph.engine.snapshot.value, graph.time.elapsedRealtime(), force = false)
                    graph.coordinator.fatigueDueMs()
                }
                if (fatigue != null) graph.coordinator.deliverFatigue(fatigue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TimerService", "ticker iteration failed; continuing (self-heal)", e)
            }
            // v1.11.0 自适应节拍(省电):剩余时间还长时 15s 一次即可 —— 精确到点由 EXACT 闹钟负责、
            // 通知栏倒计时由系统 Chronometer 绘制、界面倒计时由 UI 自己的 1s 帧驱动,故服务侧无需 1s 轮询。
            // 临近到点(<=5s)才回到 500ms 高频,保证阶段切换及时。
            delay(nextDelayMs(graph.engine.snapshot.value, graph.time.elapsedRealtime()))
        }
    }
}

/**
 * 下一次轮询间隔:粗节拍省电,但**锚定到到期时刻**——剩余时间不足节拍时按剩余时间等待,
 * 保证到点那一瞬就有一次 tick(否则系统 Chronometer 会越过 00:00 继续往负数走)。
 */
internal fun nextDelayMs(snap: com.goodnight.timer.RuntimeSnapshot?, nowElapsed: Long): Long {
    if (snap == null || snap.status != EngineStatus.RUNNING || snap.countUp) return 15_000L
    val remaining = snap.endElapsed - nowElapsed
    return remaining.coerceIn(500L, 15_000L)
}
