package com.goodnight.ui.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.width
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.mergeSessions
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.1 Task 7 修复轮 2(W4):每日详情同一卡片内,两列**等分固定宽** ——
 * 各行第二列起点必须一致(旧行为 `weight(1f, fill = false)` 让格宽随名字长短变化,
 * AVD 实测第二列起点 548px vs 631px)。
 *
 * 断言用真实测量结果:标识列宽取标识节点的实测宽(它有 `Modifier.width(...)`),
 * 第二列起点取时间文本左沿(文本左沿 = 所在格左沿)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实字体度量(legacy 模式下每字符只算 1px)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class DayDetailLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val min = 60_000L
    private val base = LocalDate.of(2026, 9, 23).atTime(9, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val longName = "读论文与写摘要笔记与整理参考文献清单并归档"

    /** 四段同属上午:长名任务 / 未绑定 / 短名任务 / 长名任务 -> 两行两列,两行名字长度不同 */
    private fun detail(): DayDetailUi {
        val titles = mapOf(1L to longName, 2L to "写周报")
        fun row(startMin: Long, endMin: Long, task: Long?) = FocusSessionEntity(
            profileId = 1, startAt = base + startMin * min, endAt = base + endMin * min, taskId = task,
        )
        val rows = listOf(row(0, 30, 1L), row(30, 60, null), row(60, 90, 2L), row(90, 120, 1L))
        val dayRow = DayDetailRow(
            profileName = "P", millis = 120 * min, index = 0,
            sessions = mergeSessions(rows.map { it.startAt to it.endAt }),
            taskSpans = taskSpansOf(rows, titles),
        )
        return DayDetailUi(LocalDate.of(2026, 9, 23), 120 * min, listOf(dayRow))
    }

    private fun render() {
        val d = detail()
        rule.setContent { MaterialTheme { DayDetailCard(d) } }
    }

    private fun bounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    /** 旧行为下这条会红:两行名字长度不同 -> 格宽不同 -> 第二列起点不同(548px vs 631px) */
    @Test fun secondColumnStartsAtTheSameXOnEveryRow() {
        render()
        val r0c2 = bounds("09:30 ~ 10:00")
        val r1c2 = bounds("10:30 ~ 11:00")
        println("W4 row0.col2.left=${r0c2.left} row1.col2.left=${r1c2.left}")
        assertEquals(
            "同一卡片各行第二列起点必须一致(第一行 ${r0c2.left} vs 第二行 ${r1c2.left})",
            r0c2.left.value, r1c2.left.value, 1f,
        )
    }

    /** 两列等分:格宽 == (行宽 - 标识列 - 两个间距) / 2,与名字长短无关(两行都查) */
    @Test fun columnsSplitTheRowEvenly() {
        render()
        val rowW = rule.onRoot().getUnclippedBoundsInRoot().width.value
        val periodColW = bounds("上午").width.value
        val expected = (rowW - 18f - periodColW - 12f - 10f) / 2f // 卡片左内边距/标识列/两个间距
        // 第一行名字超半行、第二行名字很短 —— 旧行为下两行的格宽一个被名字撑满、一个只剩自然宽
        listOf("09:00 ~ 09:30" to "09:30 ~ 10:00", "10:00 ~ 10:30" to "10:30 ~ 11:00")
            .forEach { (left, right) ->
                val c1 = bounds(left).left.value
                val c2 = bounds(right).left.value
                val cellW = c2 - c1 - 10f // 减去 SPAN_GAP_W
                println("W4 cellW=$cellW expected=$expected periodColW=$periodColW rowW=$rowW c1=$c1 c2=$c2")
                assertEquals("格宽应等分剩余宽(实测 $cellW vs 期望 $expected)", expected, cellW, 1f)
                assertTrue("第二列起点 ${c2} 应在第一列 ${c1} 右侧", c2 > c1)
            }
    }
}
