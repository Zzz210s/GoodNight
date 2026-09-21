package com.goodnight.service

import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineEvent
import com.goodnight.service.EventPolicy

/** v1.3 #6:事件携带的工作段窗口/归属读取(仅结算类事件含字段,其余为空) */
private fun EngineEvent.settleMillis(): Long = when (this) {
    is EngineEvent.PhaseFinished -> settleMillis
    is EngineEvent.PhaseRestarted -> settleMillis
    is EngineEvent.Reset -> settleMillis
    else -> 0
}

private fun EngineEvent.sessionStart(): Long? = when (this) {
    is EngineEvent.PhaseFinished -> sessionStartWall
    is EngineEvent.PhaseRestarted -> sessionStartWall
    is EngineEvent.Reset -> sessionStartWall
    else -> null
}

private fun EngineEvent.sessionEnd(): Long? = when (this) {
    is EngineEvent.PhaseFinished -> sessionEndWall
    is EngineEvent.PhaseRestarted -> sessionEndWall
    is EngineEvent.Reset -> sessionEndWall
    else -> null
}

private fun EngineEvent.profileIdOf(): Long? = when (this) {
    is EngineEvent.PhaseFinished -> profileId
    is EngineEvent.PhaseRestarted -> profileId
    is EngineEvent.Reset -> profileId
    else -> null
}

/**
 * 引擎事件 -> 服务反应(v1.3 拆分):settle 落账 / 段记录 / 闹钟武装 / 检查点 / 提醒。
 * 调用方必须已持有引擎锁(内部不加锁;落库与 EventPolicy 决策需与 ticker 串行)。
 */
internal class EventApplier(
    private val graph: AppGraph,
    private val ledger: TickLedger,
    private val notifier: ServiceNotifier,
) {
    /**
     * 单个事件反应。异常由调用方兜底(F4:记日志保活收集器);本函数只抛不吞。
     * @return 是否 Reset 事件(排空感知:调用方据此完成 STOP 拆除握手)
     */
    suspend fun apply(ev: EngineEvent): Boolean {
        // v1.3 #6:工作段收尾(自动完成/终止/跳过/切换/重启)落一段专注 —— 窗口由引擎在
        // 事件内携带(同次转换赋值,无跨线程竞态);settle>0 才记,防空段
        // v1.6 误触规则:整段 <1 分钟视为误触——不计入累计、不存储、不入库
        var ignoreMisTouch = false
        run {
            val ss = ev.sessionStart()
            val se = ev.sessionEnd()
            val pid = ev.profileIdOf()
            // v1.12.1:门控看**窗口时长**(不再看结算增量 —— 检查点已不再单独累加合计,
            // 否则一次 60s 检查点后 settle==0 会让整段不入账,正是"终止后无时间段"的根因)
            if (ss != null && se != null && se > ss && pid != null) {
                if (se - ss < MIN_MIS_TOUCH_MS) ignoreMisTouch = true
                else graph.totalsRepo.recordWorkSessionSplit(pid, ss, se, ev.pauseWindows())
            }
            // v1.10.8:自动备份改由"数据变动心跳"统一触发(见 GoodNightApp.watchDataChanges),
            // 这里不再单独触发;合计也不再单独累加 —— 由 recomputeDay 从段落派生。
        }
        for (fx in EventPolicy.decide(ev, graph.engine.snapshot.value)) {
            when (fx) {
                // Settle 不再单独累加合计:recordWorkSessionSplit 已按段落重算当日合计(单一数据源)
                is EventEffect.Settle -> Unit
                is EventEffect.Arm -> graph.alarmScheduler.arm(graph.engine.snapshot.value)
                EventEffect.CancelAlarm -> graph.alarmScheduler.cancel()
                EventEffect.ForceCheckpoint -> ledger.flush(graph.engine.snapshot.value, graph.time.elapsedRealtime(), force = true)
                is EventEffect.Remind -> notifier.remind(fx.workFinished)
            }
        }
        return ev is EngineEvent.Reset
    }
}

/** 误触阈值:整段小于该值视为误触(v1.6) */
private const val MIN_MIS_TOUCH_MS = 60_000L

/** v1.8.3:事件携带的暂停窗口(结算类事件) */
private fun EngineEvent.pauseWindows(): List<LongArray> = when (this) {
    is EngineEvent.PhaseFinished -> pauseWindows
    is EngineEvent.PhaseRestarted -> pauseWindows
    is EngineEvent.Reset -> pauseWindows
    else -> emptyList()
}
