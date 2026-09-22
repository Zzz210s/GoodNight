package com.goodnight.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 纯 Kotlin 事件驱动计时状态机。剩余时间恒由 endElapsed 推算,不维护 tick 权威计数。
 * 不变量:每次引擎状态迁移(含 pause/resume/onCheckpointFlushed)都刷新
 * savedAtElapsed/savedAtWall —— StateRestorer 的重启判据依赖它。
 */
class TimerEngine(
    private val time: TimeProvider,
    private val scope: CoroutineScope,
    private val persist: suspend (RuntimeSnapshot?) -> Unit,
    /** 可选的事件丢弃回调(AppGraph 注入 Log.w);引擎包无 Android 依赖,可在纯 JVM 测试运行 */
    private val onEventDropped: ((EngineEvent) -> Unit)? = null,
) {
    private val _snapshot = MutableStateFlow<RuntimeSnapshot?>(null)
    val snapshot: StateFlow<RuntimeSnapshot?> = _snapshot.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val el get() = time.elapsedRealtime()
    private val wall get() = time.now()

    suspend fun awaitReady() { ready.first { it } }

    /** 应用启动时从持久化恢复;之后置 ready */
    suspend fun restore(s: RuntimeSnapshot?) {
        _snapshot.value = s
        _ready.value = true
    }

    /** BOOT 后经 StateRestorer 换算的新快照,直接采纳 */
    fun adoptRestored(s: RuntimeSnapshot) {
        _snapshot.value = s
        save()
    }

    fun start(profileId: Long, workMillis: Long, restMillis: Long, countUp: Boolean = false) {
        val cur = _snapshot.value
        if (cur != null && cur.status != EngineStatus.IDLE) return
        val e = el; val w = wall
        _snapshot.value = RuntimeSnapshot(
            profileId = profileId, workMillis = workMillis, restMillis = restMillis,
            phase = Phase.WORK, status = EngineStatus.RUNNING, cycleCount = 0,
            startElapsed = e, endElapsed = e + workMillis, endWall = w + workMillis,
            timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
            savedAtWall = w, savedAtElapsed = e, ckptDate = null, ckptAccum = 0, countUp = countUp,
            sessionStartWall = w, pauseStartWall = null, pauseGaps = "",
        )
        save()
        emit(EngineEvent.PhaseStarted(Phase.WORK, e + workMillis, w + workMillis))
    }

    fun pause() {
        val cur = _snapshot.value ?: return
        if (cur.status != EngineStatus.RUNNING) return
        val e = el; val w = wall
        // 暂停存“剩余”(倒计时)或“暂停时已走时长”(正计时 accrued)
        val atPause = (if (cur.countUp) (e - cur.startElapsed - cur.timeSpentPaused) else (cur.endElapsed - e)).coerceAtLeast(0)
        _snapshot.value = cur.copy(
            status = EngineStatus.PAUSED, timeAtPause = atPause, lastPauseTime = e,
            savedAtWall = w, savedAtElapsed = e,
            pauseStartWall = if (cur.phase == Phase.WORK) w else null,
        )
        save()
        emit(EngineEvent.Paused(atPause))
    }

    fun resume() {
        val cur = _snapshot.value ?: return
        if (cur.status != EngineStatus.PAUSED) return
        val e = el; val w = wall
        val frozenAccrued = (cur.lastPauseTime - cur.startElapsed - cur.timeSpentPaused).let {
            if (cur.countUp) it.coerceAtLeast(0) else it.coerceIn(0, cur.durationMillis)
        }
        val newEnd = if (cur.countUp) e - frozenAccrued + cur.durationMillis else e + cur.timeAtPause
        val newEndWall = if (cur.countUp) w - frozenAccrued + cur.durationMillis else w + cur.timeAtPause
        // 进行中的暂停并入空档串(窗口随快照持久化)
        val gaps = if (cur.pauseStartWall != null) {
            cur.pauseGaps + (if (cur.pauseGaps.isEmpty()) "" else ";") + "${cur.pauseStartWall},$w"
        } else cur.pauseGaps
        _snapshot.value = cur.copy(
            status = EngineStatus.RUNNING,
            startElapsed = e - frozenAccrued,
            endElapsed = newEnd, endWall = newEndWall,
            timeSpentPaused = 0,
            lastPauseTime = 0, timeAtPause = 0,
            savedAtWall = w, savedAtElapsed = e,
            sessionStartWall = if (cur.phase == Phase.WORK && cur.sessionStartWall == null) w else cur.sessionStartWall,
            pauseStartWall = null,
            pauseGaps = gaps,
        )
        save()
        emit(EngineEvent.Resumed(newEnd, newEndWall))
    }

    fun onCheckpointFlushed(date: String, accum: Long) {
        val cur = _snapshot.value ?: return
        if (cur.phase != Phase.WORK) return
        val e = el; val w = wall
        _snapshot.value = cur.copy(ckptDate = date, ckptAccum = accum, savedAtWall = w, savedAtElapsed = e)
        save()
    }

    /**
     * v2.1:绑定/解除当前任务(id 为 null = 未绑定)。任何状态都更新快照(随运行态持久化,
     * 杀进程/重启后绑定仍在);是否发段边界由纯迁移 [bindTask] 决定 —— 工作段内发
     * TaskSwitched(RUNNING 用当前墙钟,PAUSED 用暂停起点),休息段只改快照。
     * 同值重复选择 / 无快照时 no-op。切点随快照持久化(rt_task_cuts),消费在 Task 5。
     */
    fun setTask(taskId: Long?) {
        val cur = _snapshot.value ?: return
        if (cur.taskId == taskId) return
        val (next, boundary) = cur.bindTask(taskId, wall, el)
        _snapshot.value = next
        save()
        boundary?.let(::emit)
    }

    fun onExpired() {
        val cur = _snapshot.value ?: return
        if (cur.status != EngineStatus.RUNNING) return
        if (cur.countUp) return // 正计时永不到期
        if (el < cur.endElapsed) return
        finishAndAdvance(cur, settleAtElapsed = cur.endElapsed, auto = true)
    }

    fun skip() {
        val cur = _snapshot.value ?: return
        if (cur.countUp) return // 正计时 skip 为 no-op
        when (cur.status) {
            EngineStatus.RUNNING -> finishAndAdvance(cur, settleAtElapsed = el.coerceAtMost(cur.endElapsed), auto = false)
            EngineStatus.PAUSED -> finishAndAdvance(cur, settleAtElapsed = cur.lastPauseTime, auto = false)
            EngineStatus.IDLE -> Unit
        }
    }

    fun reset() {
        val cur = _snapshot.value ?: return
        val settleAt = if (cur.status != EngineStatus.RUNNING) cur.lastPauseTime
            else if (cur.countUp) el else el.coerceAtMost(cur.endElapsed) // 正计时全额结算(可超 workMillis)
        val settle = cur.settleMillis(settleAt)
        val profileId = cur.profileId
        val (sesStart, sesEnd) = cur.workWindow(wall)
        val gaps = if (cur.phase == Phase.WORK) cur.pauseWindows() else emptyList()
        _snapshot.value = null
        save()
        emit(EngineEvent.Reset(settle, profileId, sesStart, sesEnd, gaps, cur.taskId))
    }

    fun restartPhase(profileId: Long, workMillis: Long, restMillis: Long, countUp: Boolean = false) {
        val cur = _snapshot.value ?: return
        if (cur.status != EngineStatus.PAUSED) return
        val dur = if (countUp || cur.phase == Phase.WORK) workMillis else restMillis
        val e = el; val w = wall
        val (sesStart, sesEnd) = cur.workWindow(w)
        val gaps = if (cur.phase == Phase.WORK) cur.pauseWindows() else emptyList()
        val next = if (countUp) Phase.WORK else cur.phase
        _snapshot.value = cur.toAdvancedSnapshot(profileId, workMillis, restMillis, next, EngineStatus.RUNNING, cur.cycleCount, e, dur, countUp, w)
        save()
        emit(EngineEvent.PhaseRestarted(cur.phase, cur.settleMillis(cur.lastPauseTime), cur.profileId, e + dur, w + dur, sesStart, sesEnd, gaps, cur.taskId))
    }

    private fun finishAndAdvance(cur: RuntimeSnapshot, settleAtElapsed: Long, auto: Boolean) {
        val settle = cur.settleMillis(settleAtElapsed)
        val next = if (cur.phase == Phase.WORK) Phase.REST else Phase.WORK
        val cycle = if (next == Phase.WORK) cur.cycleCount + 1 else cur.cycleCount
        val dur = if (next == Phase.WORK) cur.workMillis else cur.restMillis
        val e = el; val w = wall
        val (sesStart, sesEnd) = cur.workWindow(w)
        val gaps = if (cur.phase == Phase.WORK) cur.pauseWindows() else emptyList()
        _snapshot.value = cur.toAdvancedSnapshot(cur.profileId, cur.workMillis, cur.restMillis, next, EngineStatus.RUNNING, cycle, e, dur, cur.countUp, w)
        save()
        emit(EngineEvent.PhaseFinished(cur.phase, settle, cur.profileId, next, auto, sesStart, sesEnd, gaps, cur.taskId))
        emit(EngineEvent.PhaseStarted(next, e + dur, w + dur))
    }

    private fun save() {
        scope.launch { runCatching { persist(_snapshot.value) } }
    }

    private fun emit(ev: EngineEvent) {
        if (!_events.tryEmit(ev)) onEventDropped?.invoke(ev)
    }
}
