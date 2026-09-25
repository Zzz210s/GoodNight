package com.goodnight.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.goodnight.MainActivity
import com.goodnight.R
import com.goodnight.data.db.ProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v1.10.12:从 [TimerNotifications] 抽出的**空闲常驻通知**与"启动/确认/清除"按钮路径
 * (拆分仅为保持单文件 <=200 行;行为与原实现逐字节一致)。
 */
object TimerNotifIdle {
    /** 通知"对号"确认按钮的 PendingIntent requestCode */
    private const val ACK_REQ = 0x9A

    fun idle(context: Context, profile: com.goodnight.data.db.ProfileEntity?): Notification {
        val rv = RemoteViews(context.packageName, R.layout.notification_idle)
        rv.setImageViewResource(R.id.idle_phase, R.drawable.ic_phase_idle)
        val name = profile?.name ?: context.getString(R.string.unselected_placeholder)
        rv.setTextViewText(R.id.idle_name, name)
        if (profile != null) {
            rv.setViewVisibility(R.id.idle_start, View.VISIBLE)
            rv.setImageViewResource(R.id.idle_start, R.drawable.ic_play)
            rv.setOnClickPendingIntent(R.id.idle_start, startPendingIntent(context, profile))
            rv.setContentDescription(R.id.idle_start, context.getString(R.string.notif_start))
        } else {
            rv.setViewVisibility(R.id.idle_start, View.GONE)
        }
        return NotificationCompat.Builder(context, TimerNotifications.CH_TIMER)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(name)
            .setContentText(" ")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(activityIntent(context))
            .setCustomContentView(rv)
            .build()
    }

    /** 软件运行即显示常驻空闲通知(权限未授予/异常时静默降级);显示当前时钟名与启动按钮 */
    fun showIdle(context: Context) {
        val app = context.applicationContext as com.goodnight.GoodNightApp
        app.graph.appScope.launch {
            // v1.11.0 启动优化:渠道创建是 binder 调用,放到协程里,不占 Application.onCreate 主线程
            TimerNotifications.ensureChannels(context)
            // v2.2 Task 5 复审修复:通知只能挂**活跃**时钟。归档行仍在库里(历史解析要用它的名字),
            // 但已从列表与首页下架;拿它当通知主体 → 通知栏显示已下架时钟且保留可点的「启动」,
            // 按下去就是启动一个已归档时钟(v2.2 前这里是真删、按钮 GONE,故为归档引入的回退)。
            // 选中项已归档时回退到第一个活跃时钟 —— 与 HomeViewModel 的选中口径一致;
            // 一个活跃时钟都没有时 profile = null(idle 把启动按钮置 GONE)。
            val pid = app.graph.settingsRepo.activeProfileId.first()
            val active = app.graph.profileRepo.observeAllActive().first()
            val profile = active.firstOrNull { it.id == pid } ?: active.firstOrNull()
            try {
                context.getSystemService(NotificationManager::class.java)?.notify(TimerNotifications.ID_NOTIFY, idle(context, profile))
            } catch (_: Throwable) {
            }
        }
    }

    internal fun activityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 确认(对号)按钮:通知"休息一下/开始工作"确认,清除该通知 */
    internal fun ackPendingIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        ACK_REQ,
        android.content.Intent(context, com.goodnight.service.TimerService::class.java)
            .setAction(com.goodnight.service.ACTION_ACK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 清除计时/提醒通知(对号确认用) */
    fun cancel(context: Context) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(TimerNotifications.ID_NOTIFY)
    }

    /** 空闲通知“启动”按钮:直接对服务发 ACTION_START(startForegroundService 由用户点击触发合法) */
    internal fun startPendingIntent(context: Context, profile: com.goodnight.data.db.ProfileEntity): PendingIntent = PendingIntent.getService(
        context, profile.id.hashCode(),
        TimerCommands.startIntent(
            context, profile.id,
            profile.workMinutes * 60_000L, profile.restMinutes * 60_000L,
            profile.mode == com.goodnight.data.db.ProfileMode.COUNTUP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

}
