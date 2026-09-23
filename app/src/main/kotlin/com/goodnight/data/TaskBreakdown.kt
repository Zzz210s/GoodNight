package com.goodnight.data

import com.goodnight.data.db.TaskTotalRow

/**
 * v2.1 Task 8:报表「按任务」一行。[taskId]/[title] 为 null 表示「未绑定」。
 * 次数 = 窗口内的 `focus_session` 行数(切段后),与每日详情显示口径一致。
 */
data class TaskSlice(val taskId: Long?, val title: String?, val millis: Long, val count: Int)

/**
 * 把按 taskId 分组的聚合行整理成报表行:绑定的任务按时长降序、同长按任务名升序;
 * 「未绑定」**恒在末位(即使为 0)** —— 它是占比分母的一部分,末位固定便于逐期对照。
 * [titles] 里查不到该 id(任务已删的悬挂引用,正常路径由删除事务置空)时并入未绑定,
 * 不产出无名行。
 */
fun taskSlices(rows: List<TaskTotalRow>, titles: Map<Long, String>): List<TaskSlice> {
    val isUnbound = { r: TaskTotalRow -> r.taskId == null || titles[r.taskId] == null }
    val bound = rows.filterNot(isUnbound)
        .map { TaskSlice(it.taskId, titles[it.taskId], it.millis, it.count) }
        .sortedWith(compareByDescending<TaskSlice> { it.millis }.thenBy { it.title })
    val unbound = rows.filter(isUnbound)
    return bound + TaskSlice(null, null, unbound.sumOf { it.millis }, unbound.sumOf { it.count })
}

/**
 * 整数百分比,最大余数法:各行占比之和**恰为 100**。分母 = 各行时长之和(含未绑定),
 * 全 0 时返回全 0。余数相同按入参顺序(即时长降序)优先 —— 不用「差额并入末位」,
 * 否则未绑定行的占比会虚高。
 */
fun taskPercents(slices: List<TaskSlice>): List<Int> {
    val total = slices.sumOf { it.millis }
    if (total <= 0L) return slices.map { 0 }
    val scaled = slices.map { it.millis * 100L }
    val out = scaled.map { (it / total).toInt() }.toMutableList()
    var left = 100 - out.sum()
    for (i in scaled.indices.sortedByDescending { scaled[it] % total }) {
        if (left <= 0) break
        out[i]++
        left--
    }
    return out
}
