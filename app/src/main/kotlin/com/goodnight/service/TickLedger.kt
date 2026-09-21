package com.goodnight.service

import com.goodnight.di.AppGraph
import com.goodnight.timer.Checkpointer
import com.goodnight.timer.RuntimeSnapshot
import java.time.LocalDate

/**
 * 检查点落账(v1.3 拆分):60 秒增量 + 阶段切换/启动强落。持 lastFlush 游标状态,
 * 由调用方(引擎锁内)驱动 —— 语义与拆分前 TimerService.flushCheckpoint 完全一致。
 */
internal class TickLedger(private val graph: AppGraph) {
    private var lastFlushDate = ""
    private var lastFlushElapsed = 0L

    /**
     * 60 秒增量落账;force 用于阶段切换/启动时。
     * 调用方必须已持有引擎锁(内部不再加锁,嵌套加锁会死锁)。
     */
    suspend fun flush(snap: RuntimeSnapshot?, nowElapsed: Long, force: Boolean) {
        val s = snap ?: return
        val today = LocalDate.now().toString()
        val f = Checkpointer.compute(s, nowElapsed, today)
        // v1.12.1:检查点**只推进游标**,不再单独累加当日合计 ——
        // 合计的唯一来源是段落(recomputeDay 由段落派生),这样"今日合计 == 各时间段之和"恒成立。
        // 检查点的作用仅剩:进程被杀后重启时,快照里的 ckptAccum 让结算不重复计入。
        if (f.deltaMillis > 0 || (force && s.status == com.goodnight.timer.EngineStatus.RUNNING && s.phase == com.goodnight.timer.Phase.WORK)) {
            graph.engine.onCheckpointFlushed(f.date, f.newAccum)
        }
        lastFlushElapsed = nowElapsed
        lastFlushDate = today
    }
}
