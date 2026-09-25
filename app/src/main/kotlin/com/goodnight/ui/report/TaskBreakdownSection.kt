package com.goodnight.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.data.TaskSlice
import com.goodnight.data.taskClockPercents
import com.goodnight.data.taskPercents

/** v2.2 Task 6:任务行内的一个时钟子行(名字解析不到时 [name] 为 null,界面回退「未知时钟」) */
data class ClockSliceUi(
    val profileId: Long,
    val name: String?,
    val millis: Long,
    val count: Int,
    val percent: Int,
)

/** v2.1 Task 8:报表「按任务」区块的一行([title] 为 null = 未绑定);v2.2 Task 6 带时钟明细 */
data class TaskSliceUi(
    val taskId: Long?,
    val title: String?,
    val millis: Long,
    val count: Int,
    val percent: Int,
    /** 默认在界面收起;空 = 没有时钟级数据,该行不给展开入口 */
    val clocks: List<ClockSliceUi> = emptyList(),
)

/** 展开状态用的「未绑定行」键:任务 id 恒为正,负数不会与真实任务撞 */
private const val UNBOUND_KEY = -1L

/** 纯映射:补上整数占比(行口径见 [taskPercents],合计恰为 100;时钟明细按**本任务内**合计 100) */
fun taskBreakdownUi(slices: List<TaskSlice>): List<TaskSliceUi> {
    val percents = taskPercents(slices)
    return slices.mapIndexed { i, s ->
        val clockPercents = taskClockPercents(s.clocks)
        TaskSliceUi(
            taskId = s.taskId,
            title = s.title,
            millis = s.millis,
            count = s.count,
            percent = percents[i],
            clocks = s.clocks.mapIndexed { j, c ->
                ClockSliceUi(c.profileId, c.name, c.millis, c.count, clockPercents[j])
            },
        )
    }
}

/**
 * 「按任务」区块:视觉沿用既有报表条形行(标签 — 比例条 — 时长/次数/占比),
 * 条形以本区最长任务为满格。空列表不渲染(无段时界面无此块)。
 *
 * v2.2 Task 6:有[TaskSliceUi.clocks]的行可点开,行下追加该任务的时钟子行(时长/次数/占比)。
 * 展开状态只活在**界面本地**(键 = taskId,未绑定用 [UNBOUND_KEY]),不进 VM、不参与聚合:
 * 行级合计口径与 2.1 逐字一致,展开与否不影响任何数值。
 */
@Composable
fun TaskBreakdownSection(slices: List<TaskSliceUi>) {
    if (slices.isEmpty()) return
    val max = slices.maxOf { it.millis }.coerceAtLeast(1L)
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.report_by_task), style = MaterialTheme.typography.titleSmall)
        slices.forEach { s ->
            val key = s.taskId ?: UNBOUND_KEY
            val open = expanded[key] == true
            TaskBarRow(
                label = s.title ?: stringResource(R.string.task_unbound),
                millis = s.millis,
                count = s.count,
                percent = s.percent,
                max = max,
                expandable = s.clocks.isNotEmpty(),
                expanded = open,
                onToggle = { expanded[key] = !open },
            )
            if (open) {
                s.clocks.forEach { c ->
                    ClockBarRow(
                        label = c.name ?: stringResource(R.string.report_clock_unknown),
                        millis = c.millis,
                        count = c.count,
                        percent = c.percent,
                    )
                }
            }
        }
    }
}
