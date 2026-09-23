package com.goodnight.service

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.goodnight.R
import com.goodnight.di.AppGraph
import com.goodnight.diag.DiagLog
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 通知/提醒反应(v1.12.0 重写为 **Context 版**):不再依赖 Service —— 因为到期推进要能
 * 在"只有广播唤起的进程"里完成(不需要前台服务),所以通知发布必须在无服务时也可用。
 * 前台化(startForeground)由服务通过 [attachForeground] 注入;未挂载时只发通知。
 *
 * v1.12.3 增加**到期钳制**:通知栏倒计时是系统 Chronometer 绘制的,base 过后会继续显示负数,
 * 而"到点推进"受 ticker/闹钟调度影响可能晚几十毫秒到几秒。这里在 endElapsed+250ms 主动重发一次
 * 通知:引擎已推进 → 重发新阶段倒计时;尚未推进 → 走"已过期 → 静态 00:00"分支。
 * 进程存活时负数窗口由此消除(进程被 OEM 冻结时无法自救,只能靠精确闹钟把它拉起来)。
 */
class ServiceNotifier(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    /** 服务挂载时的前台化回调(未挂载 = null:仅 notify) */
    @Volatile private var foregroundSink: ((Notification) -> Unit)? = null

    private var clampJob: Job? = null

    /**
     * 最近一次解析到的任务标题(按 taskId 记)。
     * 存在的理由:到期钳制重发等发布路径**不带标题**(`post(cur)`),而钳制被设计的场景
     * (推进迟到 >250ms,如 Doze/inexact 闹钟)里 ckptAccum 已到顶、delta==0,不会再有快照
     * 发射把名字带回来 —— 没有这层兜底,通知标题会从「工作中 · 写周报」退回「工作中」。
     */
    @Volatile private var cachedTitle: Pair<Long, String?>? = null

    fun attachForeground(sink: ((Notification) -> Unit)?) {
        foregroundSink = sink
    }

    /**
     * v2.1:快照绑定任务的标题(通知标题拼接用)。未绑定 / 已删除 / 查询失败一律 null,
     * 通知回退到相位文案 —— 发布路径不能因一次 DB 查询失败而中断。
     * 只兜 [Exception]:作用域取消期间的 [kotlinx.coroutines.CancellationException] 必须继续
     * 上抛,否则服务 onDestroy 后调用方还会接着走前台化路径。
     */
    suspend fun titleFor(snap: RuntimeSnapshot?): String? {
        val id = snap?.taskId ?: return null
        val title = try {
            graph.taskRepo.titleById(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        cachedTitle = id to title
        return title
    }

    /** 显式标题优先;缺省时按快照绑定的任务复用最近一次解析结果(同一 taskId 才复用,防串名) */
    private fun titleOrDefault(snap: RuntimeSnapshot?, taskTitle: String?): String? =
        taskTitle ?: cachedTitle?.takeIf { it.first == snap?.taskId }?.second

    /**
     * v2.1 Task 6:任务改名/删除后重解析标题并重发通知。
     * [cachedTitle] 按 taskId 记,不主动失效的话钳制重发会把旧名写回;而只清缓存又会让
     * 下一次不带标题的重发连任务名一起丢掉 —— 所以这里重解析一次(顺带刷新缓存)并立即重发。
     * 无快照时无事可做(空闲通知本就不带任务名)。
     */
    fun refreshTaskTitle() {
        val snap = graph.engine.snapshot.value ?: return
        scope.launch { post(snap, titleFor(snap)) }
    }

    /** 按快照发布计时/空闲通知;有服务挂载时同时前台化。[taskTitle] 非空时标题带上任务名 */
    fun post(snap: RuntimeSnapshot?, taskTitle: String? = null) {
        val title = titleOrDefault(snap, taskTitle)
        val n = if (snap != null) TimerNotifications.inProgress(context, snap, title)
        else TimerNotifications.minimal(context)
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.notify(TimerNotifications.ID_NOTIFY, n)
        }
        DiagLog.add(
            "Notif",
            "发布通知 有快照=${snap != null} 任务=${title ?: "无"} 前台化=${foregroundSink != null} ${DiagLog.env()}",
        )
        foregroundSink?.invoke(n)
        scheduleExpiryClamp(snap)
    }

    /** 到期钳制:进程存活时保证 00:00 之后不会继续显示负数 */
    private fun scheduleExpiryClamp(snap: RuntimeSnapshot?) {
        clampJob?.cancel()
        if (snap == null || snap.status != EngineStatus.RUNNING || snap.countUp) return
        val wait = snap.endElapsed - graph.time.elapsedRealtime() + 250
        if (wait <= 0) return
        clampJob = scope.launch {
            delay(wait)
            val cur = graph.engine.snapshot.value ?: return@launch
            if (cur.status != EngineStatus.RUNNING || cur.countUp) return@launch
            val late = graph.time.elapsedRealtime() - cur.endElapsed
            if (late < 0) return@launch
            DiagLog.add("Notif", "到期钳制重发 迟到=${late}ms ${DiagLog.env()}")
            post(cur)
        }
    }

    /**
     * 疲劳提醒投递(v1.14.0):**始终发通知**(信息量在文案:连续多久 + 建议休息多久),
     * 振动按系统模式(静音不振动);不响铃 —— 健康提醒不该用铃声打断工作。
     */
    fun fatigueReminder(continuousMs: Long) {
        scope.launch {
            val intensity = graph.settingsRepo.reminderIntensity.first()
            val mode = context.getSystemService(android.media.AudioManager::class.java)?.ringerMode
                ?: android.media.AudioManager.RINGER_MODE_NORMAL
            if (mode != android.media.AudioManager.RINGER_MODE_SILENT) {
                graph.reminderPlayer.play(
                    intensity,
                    ReminderChannels(notify = false, vibrate = true, sound = false, notifyTimeoutMs = 0L),
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@launch
            val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return@launch
            TimerNotifications.ensureChannels(context)
            runCatching {
                nm.notify(TimerNotifications.ID_FATIGUE, TimerNotifications.fatigue(context, continuousMs))
            }
            DiagLog.add(
                "Fatigue",
                "疲劳提醒已发出 连续=${continuousMs / 60_000}分钟 模式=${ringerModeName(mode)}",
            )
        }
    }

    /**
     * 播放提醒并发 heads-up 通知,数秒自停,无需交互。
     * 通知与播放均在锁外协程内执行:ensureChannels/notify 是同步 binder 调用,
     * 在引擎锁临界区内直接调用会拖长持锁时间。
     */
    fun remind(workFinished: Boolean) {
        scope.launch {
            val intensity = graph.settingsRepo.reminderIntensity.first()
            // v1.13.0:按系统铃声模式自动适配(静音=仅自动消失的通知;振动=仅振动;响铃=振动+铃声)
            val mode = context.getSystemService(android.media.AudioManager::class.java)?.ringerMode
                ?: android.media.AudioManager.RINGER_MODE_NORMAL
            val channels = reminderChannelsFor(mode)
            graph.reminderPlayer.play(intensity, channels)
            // 振动/响铃模式不发通知(已有声/振反馈);静音模式才补一条会自动消失的通知
            if (!channels.notify) return@launch
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@launch
            val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return@launch
            TimerNotifications.ensureChannels(context)
            runCatching {
                nm.notify(
                    TimerNotifications.ID_NOTIFY,
                    TimerNotifications.phaseDone(context, workFinished, channels.notifyTimeoutMs),
                )
            }
            DiagLog.add(
                "Remind",
                "阶段完成通知(自动消失) 模式=${ringerModeName(mode)} 工作结束=$workFinished",
            )
        }
    }
}
