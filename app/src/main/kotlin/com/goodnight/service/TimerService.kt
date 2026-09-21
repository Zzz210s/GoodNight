package com.goodnight.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.goodnight.GoodNightApp
import com.goodnight.di.AppGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台计时服务(v1.12.0 重写为**瘦宿主**):
 * 职责只剩①前台化(把 [EngineCoordinator.notifier] 挂上 startForeground)②ticker 心跳
 * ③生命周期收尾(空闲/停止后脱离前台 + 空闲常驻通知 + stopSelf)。
 *
 * 引擎驱动(命令/到期推进/事件反应/对账)全部下沉到进程级 [EngineCoordinator] ——
 * 这样"闹钟响了但起不了前台服务"时,广播接收器仍能推进阶段,不再出现负秒不切换。
 */
class TimerService : Service() {
    internal lateinit var g: AppGraph
    internal lateinit var coordinator: EngineCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 冷启动竞态门控:等首个 onStartCommand 再开始观察快照(否则 IDLE 兜底会落在
     *  startForegroundService 的 5 秒窗口内被拉杀) */
    private val firstCommandReceived = CompletableDeferred<Unit>()

    /** START 已收到但快照未落地:观察者不应因初始 null 拆除 */
    @Volatile private var awaitingSnapshot = false

    /** STOP 排空中:拆除由命令协程负责,观察者让位 */
    @Volatile private var stopDraining = false

    override fun onCreate() {
        super.onCreate()
        g = (application as GoodNightApp).graph
        coordinator = g.coordinator
        coordinator.serviceAttached = true
        com.goodnight.diag.DiagState.serviceAlive = true
        com.goodnight.diag.DiagLog.add("Svc", "onCreate：服务启动")
        coordinator.notifier.attachForeground { n -> startForegroundCompat(n) }
        coordinator.onTeardown = { tearDownToIdle() }
        scope.launch {
            g.engine.awaitReady()
            launch {
                firstCommandReceived.await()
                g.engine.snapshot.collect { onSnapshot(it) }
            }
            coordinator.awaitReadyAndSubscribed()
            // ticker 首轮即可能触发 onExpired,必须在订阅握手之后启动
            TickDriver(g, coordinator.ledger, coordinator.mutex).loop(scope)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_ACK) {
            // 通知"对号"确认:只清除提醒通知,不改计时状态
            TimerNotifIdle.cancel(this)
            return START_STICKY
        }
        com.goodnight.diag.DiagLog.add("Svc", "onStartCommand action=${action ?: "null(对账)"}")
        if (action == ACTION_START) awaitingSnapshot = true
        firstCommandReceived.complete(Unit)
        // 前台化纪律:异步处理前先同步前台化(无快照时用最小通知)
        startForegroundCompat(TimerNotifications.inProgressOrMinimal(this, g.engine.snapshot.value))

        scope.launch {
            coordinator.awaitReadyAndSubscribed()
            if (action == null) {
                // START_STICKY/ServiceLauncher:对账(过期推进/活跃重武装/空闲自停)
                coordinator.reconcile(awaitingSnapshot)
                return@launch
            }
            try {
                if (action == ACTION_STOP) stopDraining = true
                coordinator.run(intent.toTimerCommand(action))
                if (action == ACTION_STOP) awaitStopDrainedAndTearDown()
            } finally {
                if (action == ACTION_START) awaitingSnapshot = false
                if (action == ACTION_STOP) stopDraining = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        com.goodnight.diag.DiagState.serviceAlive = false
        com.goodnight.diag.DiagLog.add("Svc", "onDestroy：服务结束（通知可能随之消失）")
        if (coordinator.serviceAttached) {
            coordinator.serviceAttached = false
            coordinator.notifier.attachForeground(null)
            coordinator.onTeardown = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** 快照观察:活跃 → 前台化;空闲 → 脱离前台并保留空闲常驻通知后自停 */
    private fun onSnapshot(snap: com.goodnight.timer.RuntimeSnapshot?) {
        runCatching {
            when {
                snap != null -> startForegroundCompat(TimerNotifications.inProgress(this, snap))
                awaitingSnapshot || stopDraining -> Unit
                else -> tearDownToIdle()
            }
        }.onFailure { Log.w(TAG, "snapshot handler failed for $snap", it) }
    }

    private fun tearDownToIdle() {
        com.goodnight.diag.DiagLog.add("Svc", "空闲收尾：脱离前台 + 空闲通知 + stopSelf")
        stopForeground(STOP_FOREGROUND_DETACH)
        TimerNotifIdle.showIdle(this)
        stopSelf()
    }

    private fun startForegroundCompat(n: android.app.Notification) {
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 自 API 34 才有:34 以下传该类型会抛异常
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TimerNotifications.ID_NOTIFY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TimerNotifications.ID_NOTIFY, n)
        }
    }

    private companion object {
        const val TAG = "TimerService"
    }
}

/** Intent -> 命令载荷(纯数据,便于测试) */
internal fun Intent.toTimerCommand(action: String) = TimerCommand(
    action = action,
    profileId = getLongExtra(EXTRA_PROFILE_ID, -1L),
    workMillis = getLongExtra(EXTRA_WORK_MILLIS, 0L),
    restMillis = getLongExtra(EXTRA_REST_MILLIS, 0L),
    countUp = getBooleanExtra(EXTRA_COUNT_UP, false),
)
