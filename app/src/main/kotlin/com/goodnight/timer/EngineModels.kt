package com.goodnight.timer

enum class Phase { WORK, REST }

enum class EngineStatus { IDLE, RUNNING, PAUSED }

/**
 * 计时引擎的完整运行时状态。时间语义:
 * - *Elapsed 字段基于 SystemClock.elapsedRealtime()(单调,重启清零)
 * - *Wall 字段基于 System.currentTimeMillis()(重启换算用)
 * - status == IDLE 时引擎快照为 null,本枚举值仅用于 UI 映射
 */
data class RuntimeSnapshot(
    val profileId: Long,
    val workMillis: Long,
    val restMillis: Long,
    val phase: Phase,
    val status: EngineStatus,
    val cycleCount: Int,
    val startElapsed: Long,
    val endElapsed: Long,
    val endWall: Long,
    val timeSpentPaused: Long,
    val lastPauseTime: Long,
    val timeAtPause: Long,
    val savedAtWall: Long,
    val savedAtElapsed: Long,
    val ckptDate: String?,
    val ckptAccum: Long,
    /** 正计时模式:phase 恒为 WORK 且永不到期(Task 6 / #10);缺省 false = 倒计时路径逐字节不变 */
    val countUp: Boolean = false,
    /**
     * v1.12.1:本阶段(仅 WORK)专注窗口的墙钟起点与暂停空档 —— **随快照持久化**。
     * 此前保存在引擎内存字段里,进程被杀/重启(闹钟唤醒、系统回收)后就丢了,导致
     * "终止后时间段不入账、合计与时间段对不上"。现在任何进程都能用同一窗口落段。
     */
    val sessionStartWall: Long? = null,
    /** 进行中的暂停起点(墙钟);resume 时并入 [pauseGaps] */
    val pauseStartWall: Long? = null,
    /** 已完成空档编码 "start,end;start,end"(紧凑;空串=无) */
    val pauseGaps: String = "",
    /**
     * v2.1:当前绑定的任务(未绑定 = null)。随运行态持久化(键 rt_task_id),杀进程/重启后
     * 仍沿用恢复路径;阶段推进/重启跨周期保留,直到用户改动或整次会话结束(快照清空)。
     */
    val taskId: Long? = null,
    /**
     * v2.1:本工作段内已发生的任务切换切点,编码 "atWall,fromTaskId,toTaskId;..."(空字段 = null;
     * 空串 = 无切点)。比照 [pauseGaps] 随快照持久化(键 rt_task_cuts):切点原本只活在内存事件
     * 缓冲里,而工作段跨重启存活 —— 不持久化则杀进程/重启后,重启前那段工作会被归到重启后的任务。
     * 切点只对本工作段有效,阶段推进(进入新工作段)时清空。
     */
    val taskCuts: String = "",
) {
    /** 切点解析(事件与落段共用):每项 = (切点墙钟, 切换前任务, 切换后任务) */
    fun taskCutPoints(): List<Triple<Long, Long?, Long?>> = taskCuts.split(';').mapNotNull { seg ->
        val parts = seg.split(',')
        if (parts.size != 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        Triple(at, parts[1].toLongOrNull(), parts[2].toLongOrNull())
    }

    /** 空档解析(事件与落段共用) */
    fun pauseWindows(): List<LongArray> = pauseGaps.split(';').mapNotNull { seg ->
        val parts = seg.split(',')
        if (parts.size != 2) return@mapNotNull null
        val a = parts[0].toLongOrNull() ?: return@mapNotNull null
        val b = parts[1].toLongOrNull() ?: return@mapNotNull null
        longArrayOf(a, b)
    }

    val durationMillis: Long get() = if (phase == Phase.WORK) workMillis else restMillis

    /** 剩余毫秒。countUp 无到期概念,恒 0(展示由 accruedWork 换算,不依赖本字段) */
    fun remaining(nowElapsed: Long): Long = when {
        countUp -> 0L
        EngineStatus.RUNNING == status -> (endElapsed - nowElapsed).coerceAtLeast(0)
        EngineStatus.PAUSED == status -> timeAtPause
        else -> 0L
    }

    /**
     * 本阶段(仅 WORK)已流逝的工作毫秒,扣除暂停。
     * countUp 不封顶于 workMillis(正计时可无限累计到 elapsed;暂停后 PAUSED 分支取暂停时刻)。
     * 倒计时维持 0..workMillis 封顶 —— 字节等价回归网依赖此分支。
     */
    fun accruedWork(nowElapsed: Long): Long = when {
        phase == Phase.WORK && status == EngineStatus.RUNNING ->
            accruedRaw(nowElapsed - startElapsed - timeSpentPaused)
        phase == Phase.WORK && status == EngineStatus.PAUSED ->
            accruedRaw(lastPauseTime - startElapsed - timeSpentPaused)
        else -> 0L
    }

    private fun accruedRaw(raw: Long): Long =
        if (countUp) raw.coerceAtLeast(0) else raw.coerceIn(0, workMillis)
}

sealed interface EngineEvent {
    data class PhaseStarted(val phase: Phase, val endElapsed: Long, val endWall: Long) : EngineEvent
    /**
     * settleMillis = 待落库的工作增量(已扣除 checkpoint 游标);
     * profileId = 结算归属的 profile —— 完成推进前(finishAndAdvance 之前)快照的 profileId。
     * finishAndAdvance 目前把同一 profileId 复制给后继,但事件发出与收集器处理之间,排队的
     * RESET/restartPhase 可能已清空或替换快照,事后读快照会错归属或整个丢 settle,
     * 因此事件必须自带(携带方式镜像 Reset/PhaseRestarted)。
     */
    data class PhaseFinished(
        val finished: Phase, val settleMillis: Long, val profileId: Long, val next: Phase, val auto: Boolean,
        /** v1.3 #6:本次工作段墙钟窗口(仅 finished=WORK 时有值);终止/暂停等由对应事件携带 */
        val sessionStartWall: Long? = null, val sessionEndWall: Long? = null,
        /** v1.8.3:本工作段内的暂停窗口 [[start,end]](供 >5 分钟暂停分段展示) */
        val pauseWindows: List<LongArray> = emptyList(),
        /**
         * v2.1:本次收尾段的任务 = 事件发出时刻快照的当前绑定 = 段内**最后一个**子段的任务
         * (自带 —— 事件发出与收集器处理之间快照可能已换值)。**整段的段首任务不能取本字段**:
         * 段内有切点时,段首任务必须取段内最早切点的 `fromTaskId`;段内无切点时本字段才等于段首任务。
         */
        val taskId: Long? = null,
    ) : EngineEvent
    /**
     * settleMillis = 待落库的工作增量(已扣除 checkpoint 游标);
     * profileId = 结算归属的 profile —— 重启前快照的 profileId。restartPhase 可换 profile,
     * 已累计的工作量仍归属旧 profile,携带方式镜像 Reset(事件发出时快照已指向新 profile)。
     */
    data class PhaseRestarted(
        val phase: Phase, val settleMillis: Long, val profileId: Long, val endElapsed: Long, val endWall: Long,
        val sessionStartWall: Long? = null, val sessionEndWall: Long? = null,
        val pauseWindows: List<LongArray> = emptyList(),
        /** v2.1:被重启段的归属任务(restartPhase 不改任务,但快照可能已被后续 setTask 覆盖) */
        val taskId: Long? = null,
    ) : EngineEvent
    data class Paused(val timeAtPause: Long) : EngineEvent
    data class Resumed(val endElapsed: Long, val endWall: Long) : EngineEvent
    /** reset 后快照已清空,事件必须自带 profileId 供 settle 落库 */
    data class Reset(
        val settleMillis: Long, val profileId: Long,
        val sessionStartWall: Long? = null, val sessionEndWall: Long? = null,
        val pauseWindows: List<LongArray> = emptyList(),
        /** v2.1:被终止段的归属任务(reset 后快照已清空,只能随事件携带) */
        val taskId: Long? = null,
    ) : EngineEvent
    /**
     * v2.1 任务切换的段边界:atWall = **该次连续工作停止的时刻**(一次连续工作只属一个任务),
     * fromTaskId = 切换前该段任务,toTaskId = 切换后新段任务(可为 null = 解绑)。
     * 工作段内发出,边界时刻按状态取:WORK + RUNNING 用当前墙钟;WORK + PAUSED 用暂停起点
     * (暂停前那段仍属旧任务,不能算到新任务头上);休息段不落时间账,只改快照不发事件。
     * 事件与快照的 [RuntimeSnapshot.taskCuts] 同步记录同一切点(后者持久化,重启不丢)。
     * 本任务只负责发出;消费(切段落库)在 Task 5 的服务层。
     */
    data class TaskSwitched(val atWall: Long, val fromTaskId: Long?, val toTaskId: Long?) : EngineEvent
}
