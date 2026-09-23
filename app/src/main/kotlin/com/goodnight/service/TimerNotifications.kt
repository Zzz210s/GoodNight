package com.goodnight.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.goodnight.MainActivity
import com.goodnight.R
import com.goodnight.timer.DurationFormat
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 通知栏:单渠道单 ID,任何时刻最多一条。启动即弹空闲通知,计时后同 ID 替换为计时态。
 * 小图标统一为应用图标同款火焰(v1.10.3);内容行直接放真实 app 图标位图。
 * 安全属性集:布局不含 Space/裸 View/?attr 背景(会 inflate 崩溃),颜色走 XML 主题属性。
 */
object TimerNotifications {
    const val CH_TIMER = "goodnight_timer"
    const val ID_NOTIFY = 1

    /** v1.14.0 疲劳提醒:独立 ID,不覆盖计时通知 */
    const val ID_FATIGUE = 2

    /** 疲劳提醒通知停留时长:够看完文案,又不长期占位 */
    private const val FATIGUE_TIMEOUT_MS = 30_000L

    /** 通知"对号"确认按钮的 PendingIntent requestCode(v1.10.11) */
    private const val ACK_REQ = 0x9A

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, context.getString(R.string.ch_app), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null) // 铃声/震动由 ReminderPlayer 播放
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    /**
     * v2.1:计时通知标题 —— 绑定任务时拼上任务名(「工作中 · 写周报」);
     * 未绑定 / 标题查不到 / 纯空白时维持原相位文案。
     */
    fun workTitle(phaseText: String, taskTitle: String?): String =
        if (taskTitle.isNullOrBlank()) phaseText else "$phaseText · $taskTitle"

    /** 引擎快照未就绪的最小占位通知:onStartCommand 同步前台化先顶上 */
    /** 有快照用计时通知,无快照用最小通知(服务同步前台化用) */
    fun inProgressOrMinimal(context: android.content.Context, snap: com.goodnight.timer.RuntimeSnapshot?): Notification =
        if (snap != null) inProgress(context, snap) else minimal(context)

    fun minimal(context: Context): Notification =
        NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(context.getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(activityIntent(context))
            .build()

    /** 计时态通知(RemoteViews:相位图标 + 倒计时/定格 + 循环 + 图标按钮);[taskTitle] 非空时标题带任务名 */
    fun inProgress(context: Context, snap: RuntimeSnapshot, taskTitle: String? = null): Notification {
        val phaseText = workTitle(
            context.getString(if (snap.phase == Phase.WORK) R.string.state_work else R.string.state_rest),
            taskTitle,
        )
        val paused = snap.status == EngineStatus.PAUSED
        val countUp = snap.countUp
        val rv = RemoteViews(context.packageName, R.layout.notification_actions)

        // 行1:app 图标 + 相位图标 + 循环计数 + 倒计时(同排等宽)
        rv.setImageViewResource(
            R.id.notif_phase,
            when {
                snap == null -> R.drawable.ic_phase_idle
                snap.phase == Phase.WORK -> R.drawable.ic_phase_work
                else -> R.drawable.ic_phase_rest
            },
        )
        rv.setTextViewText(R.id.notif_title, "")
        rv.setViewVisibility(R.id.cycle_cell, if (countUp) android.view.View.GONE else android.view.View.VISIBLE)
        rv.setTextViewText(R.id.notif_cycle_text, if (countUp) "" else snap.cycleCount.toString())
        // Chronometer 的 base 必须基于 elapsedRealtime(墙钟 endWall 会导致倒计时错/空);暂停态定格文本
        if (paused) {
            rv.setTextViewText(R.id.notif_time, DurationFormat.ms(snap.timeAtPause))
            com.goodnight.diag.DiagLog.add("Notif", "计时通知 暂停态 定格=${DurationFormat.ms(snap.timeAtPause)}")
        } else if (!countUp && snap.endElapsed <= android.os.SystemClock.elapsedRealtime()) {
            // v1.10.11:倒计时已过 00:00 —— 系统 Chronometer 会继续往负数走(Doze/进程被冻结时
            // 到点推进来不及),这里改为静态 00:00,任何情况下都不出现负数计时。
            rv.setTextViewText(R.id.notif_time, "00:00")
            com.goodnight.diag.DiagLog.add(
                "Notif",
                "计时通知 已过期静态00:00 剩余=${snap.endElapsed - android.os.SystemClock.elapsedRealtime()}ms 相位=${snap.phase}",
            )
        } else {
            val spec = buildClockSpec(snap)
            rv.setChronometerCountDown(R.id.notif_time, spec.countDown)
            rv.setChronometer(R.id.notif_time, spec.base, null, true)
            com.goodnight.diag.DiagLog.add(
                "Notif",
                "计时通知 Chronometer base=${spec.base} 倒计时=${spec.countDown} 相位=${snap.phase} " +
                    "剩余=${snap.endElapsed - android.os.SystemClock.elapsedRealtime()}ms",
            )
        }

        // 行2 图标按钮:终止 | 开始/暂停 | 跳过(正计时无跳过)
        rv.setImageViewResource(R.id.btn_stop, R.drawable.ic_stop)
        rv.setOnClickPendingIntent(R.id.btn_stop, serviceIntent(context, ACTION_STOP))
        rv.setImageViewResource(R.id.btn_pause, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        rv.setOnClickPendingIntent(R.id.btn_pause, serviceIntent(context, if (paused) ACTION_RESUME else ACTION_PAUSE))
        if (countUp) {
            rv.setViewVisibility(R.id.btn_skip, android.view.View.GONE)
        } else {
            rv.setViewVisibility(R.id.btn_skip, android.view.View.VISIBLE)
            rv.setImageViewResource(R.id.btn_skip, R.drawable.ic_skip_next)
            rv.setOnClickPendingIntent(R.id.btn_skip, serviceIntent(context, ACTION_SKIP))
        }

        return NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(phaseText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(activityIntent(context))
            // 纯 custom content view(DecoratedCustomViewStyle 在部分机型渲染异常);
            // 颜色由布局 XML 主题属性解析,适配深浅通知底。
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .build()
    }

    /**
     * 疲劳提醒通知(v1.14.0):独立 ID + 30 秒后自动消失。
     * 依据超日节律:同一任务连续工作 90 分钟后建议 15-20 分钟长休息。
     */
    fun fatigue(context: Context, continuousMs: Long, autoCancelMs: Long = FATIGUE_TIMEOUT_MS): Notification {
        val dur = com.goodnight.timer.DurationFormat.localizedHm(context, continuousMs)
        val body = context.getString(R.string.fatigue_body, dur)
        val builder = NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(context.getString(R.string.fatigue_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(activityIntent(context))
        if (autoCancelMs > 0) builder.setTimeoutAfter(autoCancelMs)
        return builder.build()
    }

    /**
     * 阶段完成通知。v1.13.0:静音模式专用(振动/响铃模式不再发通知),
     * [autoCancelMs] > 0 时到点自动消失,不留在通知栏。
     */
    fun phaseDone(context: Context, workFinished: Boolean, autoCancelMs: Long = 0L): Notification {
        val title = context.getString(if (workFinished) R.string.done_work_title else R.string.done_rest_title)
        val text = context.getString(if (workFinished) R.string.done_rest_body else R.string.done_work_body)
        val builder = NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(activityIntent(context))
            // v1.10.11:右侧对号按钮 = 确认收到,清除本条通知(不打开应用,不干扰后续通知)
            .addAction(R.drawable.ic_check, " ", TimerNotifIdle.ackPendingIntent(context))
        if (autoCancelMs > 0) builder.setTimeoutAfter(autoCancelMs)
        return builder.build()
    }

    private fun activityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
        context, action.hashCode(), Intent(context, TimerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
