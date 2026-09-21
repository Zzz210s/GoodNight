package com.goodnight.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class NotificationsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private val snap = RuntimeSnapshot(
        profileId = 1, workMillis = 100_000, restMillis = 40_000,
        phase = Phase.WORK, status = EngineStatus.RUNNING, cycleCount = 1,
        startElapsed = 0, endElapsed = 100_000, endWall = 1_000_000,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    @Test fun ensureChannelsCreatesBoth() {
        TimerNotifications.ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(TimerNotifications.CH_TIMER))
        assertNotNull(nm.getNotificationChannel(TimerNotifications.CH_TIMER))
    }

    /** 三态通知契约:title 为阶段;text 承载循环;运行态 chronometer 倒计时 + 进度条;动作图标 + STOP intent */
    @Test fun inProgressLayoutAndActions() {
        TimerNotifications.ensureChannels(ctx)
        val running = TimerNotifications.inProgress(ctx, snap)
        val paused = TimerNotifications.inProgress(ctx, snap.copy(status = EngineStatus.PAUSED))
        // v1.9.1 通知重构:自定义 RemoteViews(图标按钮 终止|开始/暂停|跳过 + 倒计时同排 + 循环图标);不再用系统 action 行
        assertNotNull(running.contentView)
        assertNotNull(paused.contentView)
        assertEquals(0, (running.actions ?: emptyArray()).size)
        assertEquals(0, (paused.actions ?: emptyArray()).size)
        assertEquals("工作中", running.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("工作中", paused.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    }

    @Test fun phaseDoneHasCheckAction() {
        // v1.10.11:提醒通知右侧"对号"确认按钮(点=清除通知,不打开应用)
        val n = TimerNotifications.phaseDone(ctx, workFinished = true)
        assertEquals(1, n.actions?.size)
        assertEquals(com.goodnight.R.drawable.ic_check, n.actions!![0].icon)
        assertNotNull(n.actions!![0].actionIntent)
    }

    @Test fun pastDeadlineCountdownDoesNotUseChronometer() {
        // v1.10.11:已过 00:00 的倒计时通知改为静态文本,不得再挂 Chronometer(否则显示负数)
        val now = android.os.SystemClock.elapsedRealtime()
        val snap = RuntimeSnapshot(
            profileId = 1, workMillis = 60_000, restMillis = 60_000,
            phase = Phase.REST, status = EngineStatus.RUNNING, cycleCount = 0,
            startElapsed = now - 65_000, endElapsed = now - 5_000, endWall = now,
            timeSpentPaused = 0L, lastPauseTime = 0L, timeAtPause = 0L,
            savedAtWall = now, savedAtElapsed = now, ckptDate = null, ckptAccum = 0L,
        )
        val cv = TimerNotifications.inProgress(ctx, snap).contentView ?: error("应为自定义布局")
        // RemoteViews 的 actions 非公开 API:反射取 mActions 里的 methodName
        val f = android.widget.RemoteViews::class.java.getDeclaredField("mActions")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val acts = f.get(cv) as List<Any>
        val methods = acts.mapNotNull { a -> fieldValue(a, "methodName") as? String }
        // Chronometer 在 RemoteViews 里由 setBase/setStarted/setFormat 组成
        org.junit.Assert.assertFalse(
            "过期倒计时不应挂 Chronometer, 实际动作: " + methods,
            methods.any { it == "setBase" || it == "setStarted" },
        )
        org.junit.Assert.assertTrue(
            "应写入静态 00:00 文本, 实际动作: " + methods,
            methods.any { it == "setText" || it == "setCharSequence" },
        )
    }


    @Test fun phaseDoneIsAutoCancel() {
        TimerNotifications.ensureChannels(ctx)
        val n = TimerNotifications.phaseDone(ctx, workFinished = true)
        assertEquals(true, (n.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0)
    }

    // ---- v1.10 #47:通知栏小图标与应用图标同步(火焰),不再用系统闹钟图标 ----

    @Test fun smallIconMatchesAppIconInAllStates() {
        TimerNotifications.ensureChannels(ctx)
        val expected = com.goodnight.R.drawable.ic_notif_flame
        assertEquals(expected, TimerNotifIdle.idle(ctx, null).smallIcon?.resId)
        assertEquals(expected, TimerNotifications.inProgress(ctx, snap).smallIcon?.resId)
        assertEquals(expected, TimerNotifications.phaseDone(ctx, true).smallIcon?.resId)
        assertEquals(expected, TimerNotifications.minimal(ctx).smallIcon?.resId)
    }

    // ---- v1.10 #48:空闲通知 = 自定义布局(月亮 + 时钟名 + 右侧启动图标按钮),无 action 行 ----

    @Test fun idleUsesCustomLayoutWithIconButtonAndNoActionRow() {
        TimerNotifications.ensureChannels(ctx)
        val profile = com.goodnight.data.db.ProfileEntity(
            id = 7, name = "番茄", workMinutes = 25, restMinutes = 5, createdAt = 0,
        )
        val n = TimerNotifIdle.idle(ctx, profile)
        assertNotNull(n.contentView)
        n.contentView.apply(ctx, android.widget.FrameLayout(ctx)) // 真机崩溃点回归:不支持属性会抛
        assertEquals(0, (n.actions ?: emptyArray()).size)
        assertEquals("番茄", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    }

    /** 占位通知契约:走 CH_PROGRESS 且 ongoing(前台服务通知不可滑动清除) */
    @Test fun minimalPlaceholderIsOngoing() {
        TimerNotifications.ensureChannels(ctx)
        val n = TimerNotifications.minimal(ctx)
        assertEquals(TimerNotifications.CH_TIMER, n.channelId)
        assertEquals(true, (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
    }

    // ---- Task 7 / #10:正计时通知 —— 无循环/跳过/到期;运行态正向 chronometer 承载已走时长 ----

    @Test fun countUpRunningUsesForwardChronometer() {
        TimerNotifications.ensureChannels(ctx)
        val n = TimerNotifications.inProgress(ctx, snap.copy(countUp = true))
        assertNotNull(n.contentView)
        assertEquals(0, (n.actions ?: emptyArray()).size)
        assertEquals("工作中", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    }

    @Test fun countUpPausedFreezesElapsedInText() {
        TimerNotifications.ensureChannels(ctx)
        val paused = snap.copy(countUp = true, status = EngineStatus.PAUSED, timeAtPause = 45_000)
        val n = TimerNotifications.inProgress(ctx, paused)
        assertNotNull(n.contentView)
        assertEquals(0, (n.actions ?: emptyArray()).size)
        assertEquals("工作中", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    }

    @Test fun remoteViewsInflatesWithoutCrash() {
        TimerNotifications.ensureChannels(ctx)
        val n = TimerNotifications.inProgress(ctx, snap)
        assertNotNull(n.contentView)
        // 实际应用 RemoteViews 到容器:若布局含不受支持属性(如 tint/?attr)会抛异常(之前真机崩溃点)
        n.contentView.apply(ctx, android.widget.FrameLayout(ctx))
    }

    // ---- v1.9.4:Chronometer base 必须基于 elapsedRealtime(墙钟 endWall 会错/空) ----

    @Test fun clockSpecCountdownUsesElapsedEnd() {
        val spec = buildClockSpec(snap) // countUp=false
        assertEquals(100_000L, spec.base) // endElapsed(elapsed时间轴),而非 endWall
        assertEquals(true, spec.countDown)
    }

    @Test fun clockSpecCountUpUsesStartPlusPaused() {
        val spec = buildClockSpec(snap.copy(countUp = true, startElapsed = 5_000, timeSpentPaused = 2_000))
        assertEquals(7_000L, spec.base) // startElapsed + timeSpentPaused
        assertEquals(false, spec.countDown)
    }

    /**
     * v1.10.5 守卫:通知**不得**自行注入 app 图标(largeIcon / 布局内图标)。
     * 通知头部图标由系统绘制;再注入一份会变成"两个图标并列"(真机实测的观感缺陷)。
     * 这条断言防止后续改动又把重复图标加回来。
     */
    @Test fun notificationsDoNotInjectTheirOwnAppIcon() {
        TimerNotifications.ensureChannels(ctx)
        val profile = com.goodnight.data.db.ProfileEntity(
            id = 7, name = "番茄", workMinutes = 25, restMinutes = 5, createdAt = 0,
        )
        val running = TimerNotifications.inProgress(ctx, snap)
        val idle = TimerNotifIdle.idle(ctx, profile)
        assertNull(running.extras.getParcelable(NotificationCompat.EXTRA_LARGE_ICON))
        assertNull(idle.extras.getParcelable(NotificationCompat.EXTRA_LARGE_ICON))
    }

}
