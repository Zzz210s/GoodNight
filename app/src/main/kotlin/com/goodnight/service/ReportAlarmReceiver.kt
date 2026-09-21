package com.goodnight.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.goodnight.MainActivity
import com.goodnight.timer.DurationFormat
import com.goodnight.ui.report.ReportRange
import com.goodnight.ui.report.reportWindow
import java.time.LocalDate

/**
 * 周报/月报汇总通知(v1.1 #5):周日晚 23:00(ACTION_REPORT_WEEK)与月末 23:00
 * (ACTION_REPORT_MONTH)由 ReportAlarmScheduler 触发。计算当期窗口合计(复用
 * ReportViewModel 的 reportWindow 纯函数 + rangeBreakdown 查询),合计 > 0 才发
 * (零记录不打扰);点击 contentIntent 直达 MainActivity 报表屏并预选对应 tab。
 * 每次触发后重武装下一周期;进程冷启由 Application.onCreate 的 ensure() 兜底。
 */
class ReportAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val range = when (intent.action) {
            ReportAlarmActions.WEEK -> ReportRange.WEEK
            ReportAlarmActions.MONTH -> ReportRange.MONTH
            else -> return
        }
        goAsyncWithGraph(context) { g ->
            // 用计划送达日锚定汇总窗口(I1):Doze 下 setAndAllowWhileIdle 可能跨零点送达,
            // 若按送达日算会得到"下周期迄今≈0"而被阈值门丢弃,整期汇总永久丢失
            val anchor = intent.getStringExtra(ReportAlarmActions.EXTRA_ANCHOR_DATE)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            val (from, to) = reportWindow(range, anchor)
            val rows = g.totalsRepo.rangeBreakdown(from, to)
            val total = rows.sumOf { it.total }
            if (total > 0) {
                post(context, range, total)
            }
            ReportAlarmScheduler(context).ensure() // 重武装下一周期(无论是否发)
        }
    }

    private fun post(context: Context, range: ReportRange, totalMillis: Long) {
        val week = range == ReportRange.WEEK
        val title = context.getString(
            if (week) com.goodnight.R.string.report_week_title else com.goodnight.R.string.report_month_title,
        )
        val body = context.getString(
            if (week) com.goodnight.R.string.report_week_body else com.goodnight.R.string.report_month_body,
            DurationFormat.localizedHm(context, totalMillis),
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            range.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(
                    ReportAlarmActions.EXTRA_REPORT_RANGE,
                    if (range == ReportRange.WEEK) "week" else "month",
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, TimerNotifications.CH_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(TimerNotifications.ID_NOTIFY, n)
    }
}
