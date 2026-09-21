package com.goodnight.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.StateRestorer

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        goAsyncWithGraph(context) { g ->
            g.engine.snapshot.value?.let { snap ->
                val restored = StateRestorer.afterBoot(snap, g.time.now(), g.time.elapsedRealtime())
                g.engine.adoptRestored(restored)
            }
            // 门控:重启前在跑才续前台服务;PAUSED 由 UI 恢复,前台服务的正规前台化由 Task 11 负责
            if (g.engine.snapshot.value?.status == EngineStatus.RUNNING) {
                ServiceLauncher.ensureServiceRunning(context)
            }
        }
    }
}
