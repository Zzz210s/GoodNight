package com.goodnight.ui.home

import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.mergeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1 Task 7 收尾:[taskSpansOf] 的退化输入 —— 被最小跨度规则丢弃的行、同日重叠/重复 startAt 行。
 * 纯函数直接测(不建库),钉住两条不变式:①0 长 span 永不出现;②细分区间恒好铺满合并段之和。
 */
class DayDetailModelTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val titles = mapOf(1L to "写周报", 2L to "读论文")

    private fun row(startMin: Long, endMin: Long, task: Long?) = FocusSessionEntity(
        profileId = 1, startAt = t0 + startMin * min, endAt = t0 + endMin * min, taskId = task,
    )

    /** 被最小跨度规则丢弃的行:1 分钟孤段(任务 2)+ 30 分钟段(任务 1) */
    @Test fun droppedShortRowProducesNoSpanAndNoPollution() {
        val rows = listOf(row(0, 1, 2L), row(30, 60, 1L))
        val spans = taskSpansOf(rows, titles)
        assertEquals("丢弃行不得产出 span,也不得把下一段时长挂到自己名下", 1, spans.size)
        assertEquals(DaySpan(t0 + 30 * min, t0 + 60 * min, 1L, "写周报"), spans[0])
        assertTrue("不得出现 0 长 span", spans.all { it.end > it.start })
        assertEquals(
            "细分时段之和 == 展示合并(合计口径)",
            mergeSessions(rows.map { it.startAt to it.endAt }).sumOf { it.second - it.first },
            spans.sumOf { it.end - it.start },
        )
    }

    /** 同日同配置出现重复 startAt 行(不同任务):不得产出 0 长 span */
    @Test fun duplicateStartRowsProduceNoZeroLengthSpan() {
        val rows = listOf(row(0, 30, 1L), row(0, 20, 2L))
        val spans = taskSpansOf(rows, titles)
        assertTrue("0 长 span 会让卡片出现空时段", spans.all { it.end > it.start })
        assertEquals(1, spans.size)
        assertEquals(2L, spans[0].taskId)
        assertEquals(t0 to t0 + 30 * min, spans[0].start to spans[0].end)
    }
}
