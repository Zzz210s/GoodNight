package com.goodnight.service

import android.util.Log
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineEvent
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.ReconcileAction
import com.goodnight.timer.Reconciler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 命令载荷(与 Intent 解耦,可纯 JVM 测试) */
data class TimerCommand(
    val action: String,
    val profileId: Long = -1L,
    val workMillis: Long = 0L,
    val restMillis: Long = 0L,
    val countUp: Boolean = false,
    /** v2.1 Task 7:SET_TASK 的任务 id(null = 不绑定) */
    val taskId: Long? = null,
)

/**
 * v1.12.0 **计时模块协调器** —— 引擎的唯一驱动者,进程级(随 AppGraph 建立)。
 *
 * 为什么要有它:此前引擎驱动绑在**前台服务**上(事件订阅、命令、对账都在 TimerService),
 * 于是"闹钟响了但起不了前台服务"时推进就丢了 —— 表现为通知倒计时进入负秒而阶段不切。
 * 现在事件订阅与推进都在图里,任何进程启动(哪怕是**只有广播唤起的进程**)都能推进:
 *
 *   闹钟送达 → AlarmReceiver → coordinator.advanceIfExpired()  ← 不依赖前台服务
 *
 * 服务只保留前台化/ticker/生命周期;所有引擎驱动经同一把 [mutex] 串行。
 */
class EngineCoordinator(private val graph: AppGraph) {
    private val scope = graph.appScope
    private val context = graph.appContext
    val notifier = ServiceNotifier(context, graph, scope)
    internal val ledger = TickLedger(graph)
    private val applier = EventApplier(graph, ledger, notifier)

    /** 引擎驱动串行化(命令/事件/到期推进/ticker 共用) */
    val mutex = Mutex()

    /** v1.14.0 疲劳提醒:锁内判定、锁外投递 */
    private val fatigue = FatigueTracker(graph)

    private val eventsSubscribed = CompletableDeferred<Unit>()

    /** STOP 的 Reset 事件排空信号(拆除握手用) */
    @Volatile var stopDrained: CompletableDeferred<Unit>? = null

    /** 服务是否挂载(决定通知是 startForeground 还是普通 notify) */
    @Volatile var serviceAttached = false

    /** 服务拆除回调(空闲自停/停止后收尾由服务注入) */
    @Volatile var onTeardown: (() -> Unit)? = null

    /** 图建立时安装:事件订阅 + 无服务时的通知发布 */
    fun install() {
        scope.launch {
            graph.engine.awaitReady()
            launch {
                graph.engine.events
                    .onSubscription { eventsSubscribed.complete(Unit) }
                    .collect { dispatch(it) }
            }
            launch {
                graph.engine.snapshot.collect { if (!serviceAttached) notifier.post(it, notifier.titleFor(it)) }
            }
        }
    }

    /** 事件反应(锁内,异常不杀收集器) */
    private suspend fun dispatch(ev: EngineEvent) {
        mutex.withLock {
            runCatching {
                com.goodnight.diag.DiagLog.add("Eng", "事件 ${ev::class.simpleName}")
                val resetSeen = applier.apply(ev)
                if (resetSeen) stopDrained?.complete(Unit)
            }.onFailure { Log.w(TAG, "event handler failed for $ev", it) }
        }
    }

    /** 握手等待:5s 兜底(超时降级为无订阅者,事件将静默丢弃) */
    suspend fun awaitReadyAndSubscribed() {
        if (withTimeoutOrNull(5_000) { graph.engine.awaitReady(); eventsSubscribed.await() } == null) {
            Log.w(TAG, "events subscriber handshake timed out; engine events will be dropped")
        }
    }

    /**
     * **到期推进**(闹钟接收器/服务/UI 共用)。仅"运行中 + 倒计时 + 已到期"才动作;
     * 返回是否真的推进了。幂等:主闹钟与安全网闹钟先后送达不会重复推进。
     * @param source 触发来源(闹钟/服务/UI),用于诊断日志定位负计时窗口
     */
    suspend fun advanceIfExpired(source: String = "unknown"): Boolean = mutex.withLock {
        val s = graph.engine.snapshot.value
        if (s == null || s.status != EngineStatus.RUNNING || s.countUp) return@withLock false
        val now = graph.time.elapsedRealtime()
        if (s.endElapsed > now) return@withLock false
        // 迟到量 = 通知栏 Chronometer 越过 00:00 往负数走的时长(系统绘制,应用无法制止)
        val late = now - s.endElapsed
        graph.engine.onExpired()
        val after = graph.engine.snapshot.value
        com.goodnight.diag.DiagLog.add(
            "Eng",
            "到期推进($source) 迟到=${late}ms ${com.goodnight.diag.DiagLog.env()} " +
                "→ 相位=${after?.phase} 循环${after?.cycleCount}",
        )
        true
    }

    /**
     * 进程启动/服务启动/开机对账。
     * 通知发布(含标题 DB 读)放到**锁外**:`titleFor` 是挂起 + 一次 DB 往返,
     * 留在临界区会顶住事件派发/ticker/闹钟推进(与 [ServiceNotifier] 头注释、TickDriver 同纪律)。
     */
    suspend fun reconcile(awaitingStart: Boolean) {
        val postSnap = mutex.withLock {
            when (Reconciler.decide(graph.engine.snapshot.value, graph.time.elapsedRealtime())) {
                ReconcileAction.STOP_SELF -> {
                    if (!awaitingStart) teardown()
                    null
                }
                ReconcileAction.FINISH_EXPIRED -> {
                    graph.engine.onExpired()
                    null
                }
                ReconcileAction.RESUME_ACTIVE, ReconcileAction.SHOW_PAUSED -> {
                    val s = graph.engine.snapshot.value
                    // 活跃态重新武装到期闹钟(服务死后闹钟可能已被系统清理)
                    graph.alarmScheduler.arm(s)
                    s
                }
            }
        }
        if (postSnap != null) notifier.post(postSnap, notifier.titleFor(postSnap))
    }

    /** 执行命令(服务 onStartCommand 与测试共用) */
    suspend fun run(cmd: TimerCommand) = mutex.withLock {
        com.goodnight.diag.DiagLog.add("Eng", "命令 ${cmd.action.substringAfterLast('.')}")
        when (cmd.action) {
            ACTION_START -> {
                // v2.2 Task 3:START 自带任务 id 时在同一把锁内立刻绑定(首次绑定 = 定义整段,
                // 不产生段内切点);分两条命令下发会有顺序竞态,空闲态丢绑定。
                // 但只在**真的会启动**时才绑定:engine.start 非 IDLE 时是 no-op,若照样 setTask
                // 就会给正在运行的那一段静默改归属(段内生成 task 切点)—— 点 chip 与首页/通知的
                // 启动 Intent 竞态到达时,用户看到的运行时钟不是自己点的那个。
                val wasIdle = graph.engine.snapshot.value?.let { it.status == EngineStatus.IDLE } ?: true
                graph.engine.start(cmd.profileId, cmd.workMillis, cmd.restMillis, cmd.countUp)
                if (wasIdle && cmd.taskId != null) graph.engine.setTask(cmd.taskId)
            }
            // v2.2 Task 4:换时钟 = 同一临界区内「终止当前段 + 按新时钟开始」(不新增切点类型)
            ACTION_SWITCH_CLOCK -> applyClockSwitch(graph.engine, cmd)
            ACTION_PAUSE -> graph.engine.pause()
            ACTION_RESUME -> graph.engine.resume()
            ACTION_STOP -> {
                stopDrained = CompletableDeferred()
                graph.engine.reset()
            }
            ACTION_SKIP -> graph.engine.skip()
            ACTION_RESTART_PHASE -> graph.engine.restartPhase(cmd.profileId, cmd.workMillis, cmd.restMillis, cmd.countUp)
            ACTION_SET_TASK -> graph.engine.setTask(cmd.taskId)
        }
    }

    /** 检查点落账(ticker/阶段切换),锁内 */
    suspend fun flushCheckpoint(force: Boolean) = mutex.withLock {
        ledger.flush(graph.engine.snapshot.value, graph.time.elapsedRealtime(), force)
    }

    /**
     * 疲劳提醒判定(调用方持锁:只做 DB 读 + 策略,不做 binder 调用)。
     * @return 需要提醒时的连续工作时长(ms)
     */
    suspend fun fatigueDueMs(): Long? = fatigue.dueMs()

    /** 疲劳提醒投递(锁外:通知/振动是 binder 调用,不占引擎锁) */
    fun deliverFatigue(continuousMs: Long) = notifier.fatigueReminder(continuousMs)

    /** 空闲/停止收尾:交给服务(脱离前台 + 空闲常驻通知 + stopSelf) */
    fun teardown() {
        onTeardown?.invoke()
    }

    private companion object {
        const val TAG = "EngineCoordinator"
    }
}
