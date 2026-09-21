package com.goodnight.service

import android.app.Service
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

/** 服务生命周期辅助(v1.12.0):STOP 排空拆除。 */
internal suspend fun TimerService.awaitStopDrainedAndTearDown() {
    val drained = g.coordinator.stopDrained ?: return
    if (withTimeoutOrNull(3_000) { drained.await() } == null) {
        Log.w("TimerService", "stop/Reset-event settle drain timed out (bounded 3s); settle may be lost")
    }
    if (g.engine.snapshot.value == null) {
        // 恢复常驻:脱离前台但保留通知,换为空闲常驻(不撤,无闪断)
        stopForeground(Service.STOP_FOREGROUND_DETACH)
        TimerNotifIdle.showIdle(this)
        stopSelf()
    }
}
