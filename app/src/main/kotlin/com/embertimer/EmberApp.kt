package com.embertimer

import android.app.Application
import com.embertimer.di.AppGraph
import com.embertimer.service.TimerNotifications
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** open:测试用空 onCreate 子类注入受控 AppGraph(Robolectric 绕过真实装配) */
open class EmberApp : Application() {
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        com.embertimer.diag.DiagLog.markEnabled(this)
        com.embertimer.diag.DiagLog.add("App", "onCreate（进程启动）")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                com.embertimer.diag.DiagState.appForeground = true
                com.embertimer.diag.DiagLog.add("App", "进入前台")
            }
            override fun onActivityStopped(a: android.app.Activity) {
                com.embertimer.diag.DiagState.appForeground = false
                com.embertimer.diag.DiagLog.add("App", "退到后台")
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
        graph.bootstrapAsync()
        // v1.12.0:协调器随图安装(事件订阅下沉到图;广播唤起的进程也能推进到期)
        graph.coordinator.install()
        // v1.11.0 启动优化:渠道创建与报表闹钟武装都是 binder 调用,移到后台线程,
        // 不阻塞首帧(通知渠道在真正发通知前的路径上也会补建)。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { TimerNotifications.ensureChannels(this@EmberApp) }
            // v1.1 #5:报表通知闹钟(周日/月末 23:00)——每次进程冷启/开机补武装(闹钟不跨重启)
            runCatching { com.embertimer.service.ReportAlarmScheduler(this@EmberApp).ensure() }
            // v1.13.0:备份目录授权自愈 —— 修复"重启后备份目录失效"(选目录时未持久化授权)
            runCatching { com.embertimer.data.BackupPermissions.healOnStartup(this@EmberApp, graph.settingsRepo) }
        }
        // v1.6 误触规则一次性清理:删除历史 <1 分钟段并扣回当日合计(SharedPreferences 标记只跑一次)
        val prefs = getSharedPreferences("ember_meta", MODE_PRIVATE)
        if (!prefs.getBoolean("pruned_mistouch_v16", false)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                kotlinx.coroutines.delay(1_000)
                val ok = runCatching { graph.totalsRepo.pruneMisTouchSessions(60_000L) }.isSuccess
                if (ok) prefs.edit().putBoolean("pruned_mistouch_v16", true).apply()
            }
        }
        // v1.10.8:历史段落按新的"3 分钟连续"规则重算一次当日合计(保证合计 == 每日详情时间段之和)
        if (!prefs.getBoolean("recomputed_v1108", false)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                kotlinx.coroutines.delay(1_200)
                val ok = runCatching { graph.totalsRepo.recomputeAllDays() }.isSuccess
                if (ok) prefs.edit().putBoolean("recomputed_v1108", true).apply()
            }
        }
        // v1.12.1:一次性重算全部日期合计 —— 此前"检查点增量 + 段落"双份累加导致
        // 合计 > 各时间段之和(真机实测 91.2 vs 57.8 分钟);现在合计唯一来源是段落,重算即自洽。
        if (!prefs.getBoolean("recomputed_v1121", false)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                kotlinx.coroutines.delay(1_400)
                val ok = runCatching { graph.totalsRepo.recomputeAllDays() }.isSuccess
                if (ok) prefs.edit().putBoolean("recomputed_v1121", true).apply()
            }
        }
        // v1.11.1:时段规则改为数据层规则(<=3 分钟合并、合并后 <3 分钟删除)—— 一次性清洗历史行并重算合计。
        // 注意:只有**成功**才写标记 —— 启动瞬间 DB 可能被其它协程占用(SQLITE_BUSY),失败必须留待下次重试。
        if (!prefs.getBoolean("normalized_v1111", false)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                kotlinx.coroutines.delay(1_500)
                val ok = runCatching {
                    com.embertimer.data.SessionNormalizer.normalizeAllSessionsOnce(
                        graph.db, graph.db.focusSessionDao(), graph.totalsRepo,
                    )
                }.onFailure { android.util.Log.w("EmberApp", "session normalize failed", it) }.isSuccess
                if (ok) prefs.edit().putBoolean("normalized_v1111", true).apply()
            }
        }
        watchDataChanges()
    }

    /**
     * v1.10.8:自动备份触发 = **任何 App 数据变动**(尤其是计时累计变动)。
     * [AppGraph] 的 totalsRepo.dataTick 是数据表版本心跳,任何插入/更新/删除都会改变它;
     * 静默 [BACKUP_QUIET_MS] 后入队一次备份(WorkManager 同名 OneTime 自动合并,不堆积)。
     */
    private fun watchDataChanges() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching {
                graph.totalsRepo.dataTick()
                    .debounce(BACKUP_QUIET_MS)
                    .collect { com.embertimer.data.AutoBackupScheduler.scheduleNow(this@EmberApp) }
            }
        }
    }

    private companion object {
        /** 变动后静默期:避免计时过程中每个检查点都触发备份 */
        const val BACKUP_QUIET_MS = 20_000L
    }
}
