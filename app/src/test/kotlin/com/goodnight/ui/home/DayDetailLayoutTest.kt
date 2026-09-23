package com.goodnight.ui.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * 修复轮 3(顺带项):所有间距(卡片左内边距 / 标识列宽 / 标识与时间列的间距 / 两列间距)
 * 一律**从节点实测反推**,不再写 18f/12f/10f —— 将来调间距时断言不会拿旧常量算出
 * "期望 145 实测 86" 这类误导性差值,而是直接报出实测与期望的各自来源。
 *
 * 修复轮 4:只用**超宽名字节点**反推格宽判不出变异 —— 名字超格宽时节点宽 == 格宽,
 * 而那三格在「等分」与旧写法下取值相同。补测**未绑定格**(短内容,自然宽只有份额的
 * 六成左右)的格宽,见 [unboundCellAlsoTakesItsEvenShare]。
 *
 * `onRoot()` 当作行右沿的前提:本用例把 `DayDetailCard` 直接作根内容且不传 modifier,
 * 卡片自身 `fillMaxWidth()` 而根部无内边距,故根节点(= 屏幕)右沿 == 卡片右沿 == 行右沿。
 * 一旦改成渲染带左右内边距的容器(`HomeScreen` 里就是那样),这条等式不再成立,
 * 必须改从卡片自身推导。
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
    private val longName2 = "整理季度回顾材料并同步给项目组同事"

    /** 四段同属上午:长名任务 / 长名任务 2 / 未绑定 / 长名任务 -> 两行两列,行 2 列 1 是唯一的短内容格 */
    private fun detail(): DayDetailUi {
        val titles = mapOf(1L to longName, 2L to longName2)
        fun row(startMin: Long, endMin: Long, task: Long?) = FocusSessionEntity(
            profileId = 1, startAt = base + startMin * min, endAt = base + endMin * min, taskId = task,
        )
        val rows = listOf(row(0, 30, 1L), row(30, 60, 2L), row(60, 90, null), row(90, 120, 1L))
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

    /** 同名节点按组合顺序取(行 1 列 1、行 2 列 2) */
    private fun nameNodes(title: String) = rule.onAllNodesWithText(title, useUnmergedTree = true)

    /** 实测反推的几何:不含任何写死的 18/12/10;detail 只用于打印失败现场 */
    private class Layout(val rootRight: Float, val spanGap: Float, val share: Float, val detail: String) {
        override fun toString() = "$detail spanGap=$spanGap share=$share rootRight=$rootRight"
    }

    private fun layout(): Layout {
        val rootRight = rule.onRoot().getUnclippedBoundsInRoot().right.value // 前提见类 KDoc
        val period = bounds("上午")
        val time1 = bounds("09:00 ~ 09:30")
        val c1 = nameNodes(longName)[0].getUnclippedBoundsInRoot()
        val c2 = nameNodes(longName2)[0].getUnclippedBoundsInRoot()
        val leftPad = period.left.value // 卡片左内边距 = 首个内容节点(标识文本)左沿
        val periodColW = period.width.value // 标识列宽(该 Text 自带 width 修饰)
        val periodGap = time1.left.value - period.right.value // 标识列 -> 时间列
        val spanGap = c2.left.value - c1.right.value // 两列之间(两格都被名字占满,节点边缘即格边缘)
        val share = (rootRight - leftPad - periodColW - periodGap - spanGap) / 2f
        return Layout(
            rootRight, spanGap, share,
            "leftPad=$leftPad periodColW=$periodColW periodGap=$periodGap",
        )
    }

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

    /** 两列等分:每个格宽 == 剩余主轴上宽的一半,与名字长短无关(三处超宽名字节点都查) */
    @Test fun columnsSplitTheRowEvenly() {
        render()
        val m = layout()
        val c1 = nameNodes(longName)[0].getUnclippedBoundsInRoot()
        val c2 = nameNodes(longName2)[0].getUnclippedBoundsInRoot()
        val c3 = nameNodes(longName)[1].getUnclippedBoundsInRoot()
        println("W4b cellW=${c1.width.value}/${c2.width.value}/${c3.width.value} $m")
        listOf("行1列1" to c1, "行1列2" to c2, "行2列2" to c3).forEach { (label, node) ->
            assertEquals(
                "$label 格宽 ${node.width.value} 应等分剩余宽(实测反推份额 ${m.share},${m.detail})",
                m.share, node.width.value, 1f,
            )
        }
        assertEquals("最后一格右沿 ${c2.right} 应贴行右沿 ${m.rootRight}", m.rootRight, c2.right.value, 1f)
        assertTrue("第二列起点 ${c2.left} 应在第一列 ${c1.left} 右侧", c2.left.value > c1.left.value)
    }

    /**
     * 行 2 列 1 是未绑定段:格内只有一行时间文本,自然宽 ≈ 份额的六成 —— 旧写法
     * `weight(1f, fill = false) + widthIn(min)` 会把它收缩到内容宽(格宽随内容长短变化),
     * 等分固定宽则仍给它整份。这是本文件里唯一判别得动该变异的用例:
     * 已绑定的三格名字都超格宽(节点宽 == 格宽 == 份额),两种写法取值相同。
     *
     * 格宽由同行两格的**时间文本左沿差**反推(两格的时间文本都左对齐于各自格首,
     * 故左沿差 = 格宽 + 两格间距),不依赖任何名字节点。
     */
    @Test fun unboundCellAlsoTakesItsEvenShare() {
        render()
        val m = layout()
        val r1c1Time = bounds("10:00 ~ 10:30")
        val r1c2Time = bounds("10:30 ~ 11:00")
        val cellW = r1c2Time.left.value - r1c1Time.left.value - m.spanGap
        println("W4c unboundCellW=$cellW timeW=${r1c1Time.width.value} $m")
        assertTrue(
            "前提:未绑定格的时间文本自然宽 ${r1c1Time.width.value} 应明显窄于份额 ${m.share}," +
                "否则这档判别不出旧写法",
            r1c1Time.width.value < m.share - 4f,
        )
        assertEquals("未绑定格宽 $cellW 也应等于等分份额 ${m.share}(${m.detail})", m.share, cellW, 1f)
    }
}
