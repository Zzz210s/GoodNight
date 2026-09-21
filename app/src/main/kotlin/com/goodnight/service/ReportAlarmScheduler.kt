package com.goodnight.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 周报/月报汇总通知调度(v1.1 #5):周日 23:00 发本周、月末最后一天 23:00 发本月。
 * 一次性 RTC 闹钟(setAndAllowWhileIdle,报表通知无需 exact),触发后由
 * ReportAlarmReceiver 自行重武装下一周期;应用每次冷启/开机(BootReceiver 经
 * Application.onCreate)亦调 ensure() 补武装(闹钟不跨重启)。无 SCHEDULE_EXACT_ALARM
 * 需求,setAndAllowWhileIdle 无权限门槛。
 *
 * 触发时刻计算为纯函数(顶层级,internal),单测直接钉周/月边界与跨月推进。
 */
object ReportAlarmActions {
    const val WEEK = "com.goodnight.action.REPORT_WEEK"
    const val MONTH = "com.goodnight.action.REPORT_MONTH"
    const val EXTRA_REPORT_RANGE = "report_range" // 值 "week"|"month",通知点开直达报表对应 tab
    const val EXTRA_ANCHOR_DATE = "report_anchor_date" // 计划送达日(yyyy-MM-dd),Doze 跨零点送达时仍按计划日汇总
}

/** 下一个周日 23:00(严格晚于 [now];now 恰为周日 23:00 前则取当天) */
internal fun nextWeeklyTrigger(now: LocalDateTime): LocalDateTime {
    var next = now.toLocalDate().atTime(23, 0)
    while (next.dayOfWeek != DayOfWeek.SUNDAY) next = next.plusDays(1)
    return if (next.isAfter(now)) next else next.plusWeeks(1)
}

/** 下一个"当月最后一天 23:00"(严格晚于 [now];已过则推下月月末) */
internal fun nextMonthlyTrigger(now: LocalDateTime): LocalDateTime {
    val monthEnd = now.toLocalDate().with(TemporalAdjusters.lastDayOfMonth()).atTime(23, 0)
    return if (monthEnd.isAfter(now)) monthEnd else {
        now.toLocalDate().plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).atTime(23, 0)
    }
}

class ReportAlarmScheduler(private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)

    /** 幂等补武装两个一次性闹钟(set 同 PendingIntent 即覆盖)。冷启与每周期触发后调用。 */
    fun ensure() {
        val now = LocalDateTime.now()
        arm(ReportAlarmActions.WEEK, nextWeeklyTrigger(now))
        arm(ReportAlarmActions.MONTH, nextMonthlyTrigger(now))
    }

    private fun arm(action: String, at: LocalDateTime) {
        val triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(action, at.toLocalDate())
        // 报表通知无精确性需求:setAndAllowWhileIdle 可入 Doze 维护窗口,无需权限
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    private fun pendingIntent(action: String, anchor: LocalDate): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(), // 两 action 天然不同,requestCode 隔离
            Intent(context, ReportAlarmReceiver::class.java).setAction(action)
                .putExtra(ReportAlarmActions.EXTRA_ANCHOR_DATE, anchor.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 区间内专注总毫秒数(纯函数,通知阈值用;避免直接依赖接收器测试环境) */

