package com.goodnight.ui.tasks

import com.goodnight.data.MERGE_GAP_MS
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot

/**
 * v2.1 Task 6(评审修复):当前工作段里**归属 [taskId] 的在途工作毫秒**。
 *
 * 段落只在结算时落库(focus_session),运行中删任务时本段工作时间还没进库,但删除后它同样会
 * 以未绑定保留 —— 删除确认文案的「已记录的 N 分钟」必须算上它,否则数字在最该安抚的场景里是 0。
 *
 * 口径与结算落段严格一致(见 [com.goodnight.data.buildSessionRows] 与
 * [com.goodnight.data.DailyTotalRepository.recordWorkSessionSplit]):
 * 1. 只算 WORK 段,且当前绑定就是 [taskId];休息段/未绑定/别的任务都是 0(休息不落时间账);
 * 2. 归属:本段窗口按任务切点切分,子窗口归属取「起点之前或等于起点的最后一个切点」的新任务
 *    (切点编码见 [RuntimeSnapshot.taskCuts]);无切点时整段归属当前绑定 ——
 *    即首次绑定作用于整段的「案 B」语义,与 [com.goodnight.timer.isHeadTaskBinding] 一致;
 * 3. 暂停:**严格超过** [MERGE_GAP_MS] 的暂停不计工作 —— 入库层用 `>= 3 分钟` 切段后,
 *    [com.goodnight.data.mergeSessions] 又把间隔 `<= 3 分钟` 的相邻段并回,净效果正是
 *    「恰好 3 分钟的暂停仍算工作」,用 `>=` 会在边界上少数 3 分钟;
 * 4. 进行中的暂停:工作只算到暂停起点 [RuntimeSnapshot.pauseStartWall]。
 *
 * 未做(与落段规则一致的近似):合并后不足 [com.goodnight.data.MIN_SPAN_MS] 的极短子段不剔除 ——
 * 差异 < 1 分钟,四舍五入到整分后与落段口径无可见差别。
 */
internal fun inFlightMillisFor(s: RuntimeSnapshot, taskId: Long, nowWall: Long): Long {
    if (s.phase != Phase.WORK || s.taskId != taskId) return 0L
    val start = s.sessionStartWall ?: nowWall
    val end = if (s.status == EngineStatus.PAUSED) (s.pauseStartWall ?: nowWall) else nowWall
    if (end <= start) return 0L

    val cuts = s.taskCutPoints().sortedBy { it.first }
    val bounds = (cuts.map { it.first } + listOf(start, end))
        .filter { it in start..end }.distinct().sorted()
    // 进行中的暂停也并入(与 resume 时并入 pauseGaps 的口径一致);严格超过阈值的暂停才不计工作
    val pauses = (s.pauseWindows() + listOfNotNull(s.pauseStartWall?.let { longArrayOf(it, nowWall) }))
        .filter { it[1] - it[0] > MERGE_GAP_MS }

    var total = 0L
    bounds.zipWithNext().forEach { (a, b) ->
        val owner = cuts.lastOrNull { it.first <= a }?.third ?: s.taskId
        if (owner == taskId) total += (b - a - pausedWithin(a, b, pauses)).coerceAtLeast(0L)
    }
    return total
}

/** 窗口 [from, to] 内被暂停覆盖的毫秒(逐个空档裁剪后求和) */
private fun pausedWithin(from: Long, to: Long, pauses: List<LongArray>): Long =
    pauses.sumOf { p -> (minOf(to, p[1]) - maxOf(from, p[0])).coerceAtLeast(0L) }
