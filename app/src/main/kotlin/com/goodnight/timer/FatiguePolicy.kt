package com.goodnight.timer

/**
 * 疲劳提醒策略(v1.14.0,纯函数,可 JVM 直测)。
 *
 * **理论依据**
 * - 超日节律(ultradian rhythm / BRAC,Kleitman):注意力以约 **90 分钟**为一个周期波动,
 *   一个完整周期之后需要 15-20 分钟才能真正恢复;
 * - 持续注意(vigilance decrement)研究同样显示:60-90 分钟连续任务后警觉性下降、错误率上升。
 * 因此把"同一任务的连续工作时长"达到 90 分钟作为提醒阈值,并建议 15-20 分钟长休息。
 *
 * **"连续"的口径**:同一任务(应用内 = 同一配置 profile)的工作段累计,**忽略中间短休息**;
 * 只有离开达到 [STREAK_RESET_GAP_MS](30 分钟,大于常见 15 分钟短休息)才把连续计数清零。
 */
object FatiguePolicy {
    /** 连续工作阈值:90 分钟(超日节律一个完整周期) */
    const val THRESHOLD_MS: Long = 90 * 60_000L

    /** 空档超过该值视为"真正休息过",连续计数清零(30 分钟 > 常见 15 分钟短休息) */
    const val STREAK_RESET_GAP_MS: Long = 30 * 60_000L

    /** 提醒冷却:连续工作仍在继续时,至少间隔该时长才再提醒一次 */
    const val REMIND_COOLDOWN_MS: Long = 30 * 60_000L

    /**
     * 连续工作时长 = 当前阶段已工作毫秒 + 往前回溯的历史工作段(遇到 > [gapResetMs] 的空档即停)。
     *
     * @param sessions 同一任务的历史工作段(墙钟 [startAt, endAt]),顺序不限
     * @param currentStartWall 当前工作阶段的墙钟起点(引擎快照 `sessionStartWall`);它到上一段结束之间的
     *   空档就是"这次休息了多久",超过阈值说明真正休息过 → 只算当前阶段
     * @param currentWorkMs 当前阶段已工作毫秒(已扣暂停)
     */
    fun continuousWorkMs(
        sessions: List<Pair<Long, Long>>,
        currentStartWall: Long,
        currentWorkMs: Long,
        gapResetMs: Long = STREAK_RESET_GAP_MS,
    ): Long {
        val valid = sessions.filter { it.second > it.first }.sortedBy { it.first }
        var total = currentWorkMs.coerceAtLeast(0L)
        var windowStart = currentStartWall
        for (i in valid.indices.reversed()) {
            val (s, e) = valid[i]
            if (windowStart - e > gapResetMs) break
            // 无重叠:整段并入;有重叠(异常数据):只并入窗口起点之前的部分,不重复计数
            total += if (e <= windowStart) e - s else (windowStart - s).coerceAtLeast(0L)
            windowStart = minOf(windowStart, s)
        }
        return total
    }

    /** 是否该提醒:达到阈值,且距上次提醒已过冷却(从未提醒过则立即提醒) */
    fun shouldRemind(continuousWorkMs: Long, nowMs: Long, lastRemindMs: Long?): Boolean {
        if (continuousWorkMs < THRESHOLD_MS) return false
        if (lastRemindMs == null) return true
        return nowMs - lastRemindMs >= REMIND_COOLDOWN_MS
    }
}
