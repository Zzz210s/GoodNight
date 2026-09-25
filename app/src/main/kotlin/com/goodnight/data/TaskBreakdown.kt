package com.goodnight.data

import com.goodnight.data.db.TaskProfileTotalRow
import com.goodnight.data.db.TaskTotalRow

/**
 * v2.1 Task 8:报表「按任务」一行。[taskId]/[title] 为 null 表示「未绑定」。
 * 次数 = 窗口内的 `focus_session` 行数(切段后),与每日详情显示口径一致。
 *
 * v2.2 Task 6:[clocks] = 该任务下的时钟明细(报表里默认收起);空列表表示没有时钟级数据,
 * 界面据此不给展开入口。行级 [millis]/[count] 仍与 2.1 完全一致 —— 明细只在行内追加。
 */
data class TaskSlice(
    val taskId: Long?,
    val title: String?,
    val millis: Long,
    val count: Int,
    val clocks: List<ClockSlice> = emptyList(),
)

/**
 * v2.2 Task 6:任务行展开后的一个时钟。[name] 为 null 只可能是 profile 行已被真删的悬挂引用
 * (旧版裸删遗留),界面回退为「未知时钟」;归档时钟的行仍在库里,**名字照常解析**。
 */
data class ClockSlice(val profileId: Long, val name: String?, val millis: Long, val count: Int)

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
 * v2.2 Task 6:「任务 × 时钟」细行 → 每个**有效任务键**下的时钟明细。
 * 有效任务键与 [taskSlices] 同规则:taskId 为 null 或任务已删(titles 查不到)→ null(未绑定),
 * 因此悬挂 taskId 的段落连同它的时钟明细一起并入「未绑定任务」行 —— 合并后同一时钟重新相加。
 * 组内按时长降序、同时长按 profileId 升序(确定性排序,不依赖名字是否解析得到)。
 */
fun clockSlicesByTask(
    rows: List<TaskProfileTotalRow>,
    titles: Map<Long, String>,
    names: Map<Long, String>,
): Map<Long?, List<ClockSlice>> = rows
    .groupBy { r -> r.taskId?.takeIf { titles.containsKey(it) } }
    .mapValues { (_, rs) ->
        rs.groupBy { it.profileId }
            .map { (profileId, list) ->
                ClockSlice(profileId, names[profileId], list.sumOf { it.millis }, list.sumOf { it.count })
            }
            .sortedWith(compareByDescending<ClockSlice> { it.millis }.thenBy { it.profileId })
    }

/**
 * 最大余数法:[millis] 各行占比之和**恰为 100**。分母 = 各行时长之和,全 0(或空)时返回全 0。
 * 余数相同按入参顺序(通常已按时长降序)优先 —— 不用「差额并入末位」,
 * 否则未绑定行的占比会虚高。
 */
fun largestRemainderPercents(millis: List<Long>): List<Int> {
    val total = millis.sumOf { it }
    if (total <= 0L) return millis.map { 0 }
    val scaled = millis.map { it * 100L }
    val out = scaled.map { (it / total).toInt() }.toMutableList()
    var left = 100 - out.sum()
    for (i in scaled.indices.sortedByDescending { scaled[it] % total }) {
        if (left <= 0) break
        out[i]++
        left--
    }
    return out
}

/** 报表「按任务」各行的占比(分母 = 全部任务行之和,含未绑定) */
fun taskPercents(slices: List<TaskSlice>): List<Int> = largestRemainderPercents(slices.map { it.millis })

/** v2.2 Task 6:任务行内各时钟的占比(分母 = **该任务**各时钟时长之和,合计恰为 100) */
fun taskClockPercents(clocks: List<ClockSlice>): List<Int> =
    largestRemainderPercents(clocks.map { it.millis })
