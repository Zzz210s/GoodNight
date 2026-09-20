package com.embertimer.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.embertimer.timer.RuntimeSnapshot
import com.embertimer.timer.TimeProvider

/**
 * 到期闹钟(v1.12.3 主闹钟改为 `setAlarmClock`)。
 *
 * 为什么改:真机实测(荣耀 REA-AN00,Android 15)屏幕关闭后 OEM 会冻结应用,
 * `setExactAndAllowWhileIdle` 的主闹钟被推迟 43.7s 才送达(系统日志显示 CPU 醒着、只有本应用被冻结),
 * 阶段推进跟着晚,通知栏系统 Chronometer 就一直显示负数。
 * `setAlarmClock` 被系统当作**用户可见闹钟**(锁屏/状态栏可见),不受 OEM 冻结与省电策略推迟。
 * 代价:计时期间状态栏会出现闹钟图标。
 *
 * 冗余仍在:
 *  - primary(elapsed = 到期时刻) 用 `setAlarmClock`(墙钟触发;墙钟被改时靠安全网兜底)
 *  - safety (elapsed = 到期 +45s) 仍用 `setExactAndAllowWhileIdle`(elapsed 轴,不受墙钟变动影响)
 *
 * 取消:两个一起取消(改阶段/暂停/终止/正计时都不留残余闹钟)。
 */
open class AlarmScheduler(private val context: Context, private val time: TimeProvider) {
    private val am = context.getSystemService(AlarmManager::class.java)

    /** 按计划武装(幂等:同名 PendingIntent 覆盖既有武装) */
    fun arm(plan: AlarmPlan) {
        armClockAt(plan.primaryElapsed)
        armAt(plan.safetyElapsed, REQ_SAFETY)
    }

    /** 便捷入口:直接从快照推导计划并武装(计划为空=取消) */
    fun arm(snap: RuntimeSnapshot?) {
        val plan = alarmPlanFor(snap, time.elapsedRealtime())
        if (plan == null) cancel() else arm(plan)
    }

    /**
     * 主闹钟 = `setAlarmClock`(用户可见,抗 OEM 冻结)。失败(极少数 ROM 限制)时
     * 回退到 elapsed 轴的精确闹钟,保证至少不丢到点推进。
     */
    private fun armClockAt(elapsed: Long) {
        val remaining = elapsed - time.elapsedRealtime()
        if (remaining <= 0) return
        val pi = pendingIntent(REQ_PRIMARY)
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(time.now() + remaining, showIntent()), pi)
            com.embertimer.diag.DiagLog.add("Alarm", "武装主闹钟(setAlarmClock 用户可见) 于+${remaining}ms")
        }.onFailure {
            Log.w(TAG, "setAlarmClock rejected; falling back to exact elapsed alarm", it)
            armAt(elapsed, REQ_PRIMARY)
        }
    }

    private fun armAt(elapsed: Long, requestCode: Int) {
        if (elapsed <= time.elapsedRealtime()) return
        val pi = pendingIntent(requestCode)
        val role = if (requestCode == REQ_PRIMARY) "主" else "安全网"
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
            com.embertimer.diag.DiagLog.add("Alarm", "武装${role}闹钟(非精确,未授权) 于+${elapsed - time.elapsedRealtime()}ms")
            return
        }
        try {
            scheduleExactAlarm(elapsed, pi)
            com.embertimer.diag.DiagLog.add("Alarm", "武装${role}闹钟(精确) 于+${elapsed - time.elapsedRealtime()}ms")
        } catch (e: SecurityException) {
            // 授权在 canScheduleExactAlarms 与 setExact* 之间被撤销(TOCTOU):降级 inexact,
            // 不捕获会让异常沿调用链上抛(接收器路径未捕获即拉崩进程)
            Log.w(TAG, "exact alarm denied (SecurityException); falling back to inexact", e)
            com.embertimer.diag.DiagLog.add("Alarm", "武装${role}闹钟(精确被拒→非精确) 于+${elapsed - time.elapsedRealtime()}ms")
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
        }
    }

    fun cancel() {
        com.embertimer.diag.DiagLog.add("Alarm", "取消到期闹钟(主+安全网)")
        listOf(REQ_PRIMARY, REQ_SAFETY).forEach { req ->
            val pi = pendingIntent(req)
            am.cancel(pi)
            pi.cancel()
        }
    }

    /** 可覆写的最小接缝(测试注入 SecurityException 验证降级不崩不丢) */
    internal open fun scheduleExactAlarm(elapsed: Long, pi: PendingIntent) {
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, pi)
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_EXPIRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** setAlarmClock 的"点击闹钟图标后打开谁"—— 直接开主界面 */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context, REQ_SHOW,
        Intent(context, com.embertimer.MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "AlarmScheduler"
        internal const val ACTION_EXPIRY = "com.embertimer.action.EXPIRY"
        private const val REQ_PRIMARY = 0x1001
        private const val REQ_SAFETY = 0x1002
        private const val REQ_SHOW = 0x1003
    }
}
