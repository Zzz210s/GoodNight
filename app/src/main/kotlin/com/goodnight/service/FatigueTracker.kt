package com.goodnight.service

import com.goodnight.di.AppGraph
import com.goodnight.diag.DiagLog
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.FatiguePolicy
import com.goodnight.timer.Phase
import kotlinx.coroutines.flow.first

/**
 * 疲劳提醒判定(v1.14.0):**同一任务**连续工作(忽略中间短休息)累计达到
 * [FatiguePolicy.THRESHOLD_MS](90 分钟,超日节律一个周期)时给出提醒。
 *
 * - 数据来源 = `focus_session`(工作段,已落库、跨进程存活)+ 当前运行阶段**尚未落库**的已工作毫秒,
 *   所以进程被杀/重启后累计不丢;
 * - 换任务(profile)即重新计数,并清掉提醒冷却;
 * - 这里只做判定(引擎锁内、含一次 DB 读),投递(通知/振动)由 [ServiceNotifier] 在锁外执行。
 */
class FatigueTracker(private val graph: AppGraph) {
    private var lastRemindAtMs: Long? = null
    private var lastProfileId: Long? = null

    /** @return 需要提醒时的连续工作时长(ms);不需要则 null */
    suspend fun dueMs(): Long? {
        val snap = graph.engine.snapshot.value ?: return null
        if (snap.status != EngineStatus.RUNNING || snap.phase != Phase.WORK) return null
        if (!graph.settingsRepo.fatigueReminder.first()) return null
        if (lastProfileId != null && lastProfileId != snap.profileId) lastRemindAtMs = null
        lastProfileId = snap.profileId
        val nowWall = System.currentTimeMillis()
        val nowElapsed = graph.time.elapsedRealtime()
        val inFlight = snap.accruedWork(nowElapsed)
        // 当前工作段的墙钟起点(引擎维护);缺省时用"现在 - 已工作"近似
        val startWall = snap.sessionStartWall ?: (nowWall - inFlight)
        val recent = graph.db.focusSessionDao().recentForProfile(snap.profileId, RECENT_LIMIT)
        val accumulated = FatiguePolicy.continuousWorkMs(
            recent.map { it.startAt to it.endAt },
            currentStartWall = startWall,
            currentWorkMs = inFlight,
        )
        if (!FatiguePolicy.shouldRemind(accumulated, nowWall, lastRemindAtMs)) return null
        lastRemindAtMs = nowWall
        DiagLog.add("Fatigue", "疲劳提醒判定 连续=${accumulated / 60_000}分钟 任务=${snap.profileId}")
        return accumulated
    }

    private companion object {
        /** 只取最近这么多段:足够跨过 30 分钟空档判断连续,又不会拖慢每个节拍 */
        const val RECENT_LIMIT = 40
    }
}
