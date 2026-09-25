package com.goodnight.data

import com.goodnight.data.db.TaskProfileTotalRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.2 Task 6:时钟明细的**纯聚合**(无 Android 依赖,秒级):细行 → 每个任务键下的时钟明细,
 * 以及任务内占比。带真库的用例见 ClockBreakdownTest。
 */
class ClockSliceTest {
    private fun minutes(m: Long) = m * 60_000L

    /** 组内按时钟再聚合(同一时钟多行相加),时长降序、同时长按 profileId 升序 */
    @Test fun clockSlicesGroupByTaskThenByClock() {
        val rows = listOf(
            TaskProfileTotalRow(1L, 10L, minutes(30), 2),
            TaskProfileTotalRow(1L, 11L, minutes(90), 1),
            TaskProfileTotalRow(1L, 10L, minutes(30), 1),
            TaskProfileTotalRow(null, 12L, minutes(15), 1),
        )
        val byTask = clockSlicesByTask(rows, mapOf(1L to "A"), mapOf(10L to "番茄", 11L to "深度工作"))
        assertEquals(listOf<Long?>(1L, null), byTask.keys.toList())
        assertEquals(listOf("深度工作", "番茄"), byTask[1L]!!.map { it.name })
        assertEquals(listOf(minutes(90), minutes(60)), byTask[1L]!!.map { it.millis })
        assertEquals(listOf(1, 3), byTask[1L]!!.map { it.count })
        assertEquals(listOf(minutes(15)), byTask[null]!!.map { it.millis })
    }

    /** 悬挂 taskId(任务已删)的时钟明细并入「未绑定」,同一时钟重新相加 */
    @Test fun danglingTaskClocksMergeIntoUnbound() {
        val rows = listOf(
            TaskProfileTotalRow(999L, 10L, minutes(15), 1),
            TaskProfileTotalRow(null, 10L, minutes(5), 1),
        )
        val byTask = clockSlicesByTask(rows, emptyMap(), mapOf(10L to "番茄"))
        assertEquals(listOf<Long?>(null), byTask.keys.toList())
        assertEquals(listOf(minutes(20)), byTask[null]!!.map { it.millis })
        assertEquals(listOf(2), byTask[null]!!.map { it.count })
    }

    /** 时钟行被真删(旧版裸删遗留):名字解析不到但时长/次数保留,界面回退「未知时钟」 */
    @Test fun clockWithoutProfileRowKeepsMillisAndNullName() {
        val byTask = clockSlicesByTask(
            listOf(TaskProfileTotalRow(1L, 777L, minutes(20), 1)),
            mapOf(1L to "A"),
            emptyMap(),
        )
        assertEquals(listOf<Long?>(777L), byTask[1L]!!.map { it.profileId })
        assertEquals(listOf<String?>(null), byTask[1L]!!.map { it.name })
        assertEquals(listOf(minutes(20)), byTask[1L]!!.map { it.millis })
    }

    /** 任务内占比:沿用最大余数法,分母 = 该任务各时钟之和,合计恰为 100 */
    @Test fun taskClockPercentsUseLargestRemainderAndSumTo100() {
        val clocks = listOf(
            ClockSlice(1, "A", 30_000, 1),
            ClockSlice(2, "B", 20_000, 1),
            ClockSlice(3, "C", 10_000, 1),
        )
        assertEquals(listOf(50, 33, 17), taskClockPercents(clocks))
        assertEquals(100, taskClockPercents(clocks).sum())
        assertEquals(listOf(100), taskClockPercents(listOf(ClockSlice(1, "独苗", 5_000, 1))))
        assertEquals(emptyList<Int>(), taskClockPercents(emptyList()))
    }

    /** 单一任务的多个时钟:占比按该任务内合计 100(与报表其它行级占比无关) */
    @Test fun clockPercentsAreScopedToTheirTask() {
        val clocks = listOf(ClockSlice(1, "A", 45_000, 1), ClockSlice(2, "B", 15_000, 1))
        assertEquals(listOf(75, 25), taskClockPercents(clocks))
    }
}
