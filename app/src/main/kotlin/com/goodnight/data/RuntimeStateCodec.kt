package com.goodnight.data

import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot

/** RuntimeSnapshot <-> 平面 Map<String,String>,纯函数,便于往返单测 */
object RuntimeStateCodec {
    private const val PROFILE_ID = "rt_profile_id"
    private const val WORK = "rt_work_millis"
    private const val REST = "rt_rest_millis"
    private const val PHASE = "rt_phase"
    private const val STATUS = "rt_status"
    private const val CYCLE = "rt_cycle_count"
    private const val START_EL = "rt_start_elapsed"
    private const val END_EL = "rt_end_elapsed"
    private const val END_WALL = "rt_end_wall"
    private const val PAUSED_TOTAL = "rt_time_spent_paused"
    private const val LAST_PAUSE = "rt_last_pause_time"
    private const val AT_PAUSE = "rt_time_at_pause"
    private const val SAVED_WALL = "rt_saved_at_wall"
    private const val SAVED_EL = "rt_saved_at_elapsed"
    private const val CKPT_DATE = "rt_ckpt_date"
    private const val CKPT_ACCUM = "rt_ckpt_accum"
    private const val COUNT_UP = "rt_count_up"
    private const val SESS_START = "rt_session_start_wall"
    private const val PAUSE_START = "rt_pause_start_wall"
    private const val PAUSE_GAPS = "rt_pause_gaps"
    private const val TASK_ID = "rt_task_id"
    private const val TASK_CUTS = "rt_task_cuts"

    fun toMap(s: RuntimeSnapshot?): Map<String, String> {
        if (s == null) return emptyMap()
        return mapOf(
            PROFILE_ID to s.profileId.toString(), WORK to s.workMillis.toString(),
            REST to s.restMillis.toString(), PHASE to s.phase.name,
            STATUS to s.status.name, CYCLE to s.cycleCount.toString(),
            START_EL to s.startElapsed.toString(), END_EL to s.endElapsed.toString(),
            END_WALL to s.endWall.toString(), PAUSED_TOTAL to s.timeSpentPaused.toString(),
            LAST_PAUSE to s.lastPauseTime.toString(), AT_PAUSE to s.timeAtPause.toString(),
            SAVED_WALL to s.savedAtWall.toString(), SAVED_EL to s.savedAtElapsed.toString(),
            CKPT_ACCUM to s.ckptAccum.toString(),
        ) + (if (s.ckptDate != null) mapOf(CKPT_DATE to s.ckptDate) else emptyMap()) +
            // countUp 仅在 true 时写入:false 省略键,旧会话快照序列化逐字节不变,旧库解析缺省 false
            (if (s.countUp) mapOf(COUNT_UP to "true") else emptyMap()) +
            // v1.12.1 专注窗口:仅在有效时写键,旧快照逐字节不变
            (s.sessionStartWall?.let { mapOf(SESS_START to it.toString()) } ?: emptyMap()) +
            (s.pauseStartWall?.let { mapOf(PAUSE_START to it.toString()) } ?: emptyMap()) +
            (if (s.pauseGaps.isNotEmpty()) mapOf(PAUSE_GAPS to s.pauseGaps) else emptyMap()) +
            // v2.1 当前任务:仅在绑定时写键,未绑定与旧快照逐字节不变
            (s.taskId?.let { mapOf(TASK_ID to it.toString()) } ?: emptyMap()) +
            // v2.1 任务切点:仅在非空时写键(空串即“清空”),旧快照逐字节不变
            (if (s.taskCuts.isNotEmpty()) mapOf(TASK_CUTS to s.taskCuts) else emptyMap())
    }

    fun fromMap(m: Map<String, String>): RuntimeSnapshot? {
        val profileId = m[PROFILE_ID]?.toLongOrNull() ?: return null
        return RuntimeSnapshot(
            profileId = profileId,
            workMillis = m[WORK]?.toLongOrNull() ?: return null,
            restMillis = m[REST]?.toLongOrNull() ?: return null,
            phase = m[PHASE]?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: return null,
            status = m[STATUS]?.let { runCatching { EngineStatus.valueOf(it) }.getOrNull() } ?: return null,
            cycleCount = m[CYCLE]?.toIntOrNull() ?: 0,
            startElapsed = m[START_EL]?.toLongOrNull() ?: 0,
            endElapsed = m[END_EL]?.toLongOrNull() ?: 0,
            endWall = m[END_WALL]?.toLongOrNull() ?: 0,
            timeSpentPaused = m[PAUSED_TOTAL]?.toLongOrNull() ?: 0,
            lastPauseTime = m[LAST_PAUSE]?.toLongOrNull() ?: 0,
            timeAtPause = m[AT_PAUSE]?.toLongOrNull() ?: 0,
            savedAtWall = m[SAVED_WALL]?.toLongOrNull() ?: 0,
            savedAtElapsed = m[SAVED_EL]?.toLongOrNull() ?: 0,
            ckptDate = m[CKPT_DATE],
            ckptAccum = m[CKPT_ACCUM]?.toLongOrNull() ?: 0,
            countUp = m[COUNT_UP]?.toBooleanStrictOrNull() ?: false,
            sessionStartWall = m[SESS_START]?.toLongOrNull(),
            pauseStartWall = m[PAUSE_START]?.toLongOrNull(),
            pauseGaps = m[PAUSE_GAPS] ?: "",
            // 旧状态无此键 -> null(未绑定)
            taskId = m[TASK_ID]?.toLongOrNull(),
            // 旧状态无此键 -> 空串(无切点)
            taskCuts = m[TASK_CUTS] ?: "",
        )
    }
}
