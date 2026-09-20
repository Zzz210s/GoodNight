package com.embertimer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.embertimer.timer.EngineStatus

/**
 * 到期闹钟接收器(v1.12.0 重写)。
 *
 * 关键变化:**先在进程内直接推进引擎**(不依赖前台服务启动)——
 * 闹钟送达会把被冻结/被杀的进程拉起,`EngineCoordinator` 在 AppGraph 建立时就已订阅引擎事件,
 * 所以此处 `advanceIfExpired()` 能完整走完"结算落库 → 提醒 → 通知换阶段 → 重武装下一段"。
 * 之后再尽力拉起前台服务(维持常驻通知/后续 ticker);起不动也无所谓,推进已经完成。
 *
 * primary 与 safety 两个闹钟都会走到这里,`advanceIfExpired()` 幂等(仅"运行中且已到期"才动作)。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        goAsyncWithGraph(context) { g ->
            // 事件订阅握手:必须在推进前完成(replay=0,无订阅者时事件会丢 → 结算/重武装丢失)
            g.coordinator.awaitReadyAndSubscribed()
            com.embertimer.diag.DiagLog.add("Alarm", "到期闹钟送达")
            val advanced = g.coordinator.advanceIfExpired(source = "到期闹钟")
            val snap = g.engine.snapshot.value
            // 仍未到期(闹钟早到/误触发):补武装一次,避免后续到点无人唤醒
            if (!advanced && snap != null && snap.status == EngineStatus.RUNNING && !snap.countUp) {
                g.alarmScheduler.arm(snap)
            }
            // 仅"运行中"才拉起前台服务:暂停态由 UI 恢复,空闲/暂停都不起(不计时不常驻)
            if (snap != null && snap.status == EngineStatus.RUNNING) {
                val started = ServiceLauncher.ensureServiceRunning(context)
                // 起不了前台服务也无妨:推进已完成,闹钟已由事件反应重武装(下一阶段)
                if (!started) g.alarmScheduler.arm(snap)
            }
        }
    }
}
