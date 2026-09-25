package com.goodnight.ui.report

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.ClockSlice
import com.goodnight.data.TaskSlice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.2 Task 7(收 Task 6 审查):报表「按任务」区块的**几何**。
 *
 * 三条都靠实测 bounds,不看截图:
 * 1. 展开箭头放行尾 —— 标签基线回到 x=0,与趋势/各时钟合计兄弟区块对齐;
 * 2. 箭头在时长/占比之后,不再占行首;
 * 3. 时钟子行的时长/占比与父行落在同一 x(条形位置留空)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TaskBreakdownLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun minutes(m: Long) = m * 60_000L

    /** 90 分钟的任务,两个时钟 60 / 30(父行 2 次 · 100%,子行任务内 67 / 33) */
    private fun taskRow() = TaskSlice(
        taskId = 1L,
        title = "写报告",
        millis = minutes(90),
        count = 2,
        clocks = listOf(
            ClockSlice(10L, "番茄", minutes(60), 1),
            ClockSlice(11L, "深度工作", minutes(30), 1),
        ),
    )

    private fun setSection() {
        rule.setContent { MaterialTheme { TaskBreakdownSection(taskBreakdownUi(listOf(taskRow()))) } }
    }

    private fun left(text: String) =
        rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().left

    private fun bounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun arrow() = rule.onAllNodesWithContentDescription(
        ctx.getString(R.string.report_clock_details),
        useUnmergedTree = true,
    )[0].getUnclippedBoundsInRoot()

    @Test fun taskLabelStartsAtTheLeftEdge() {
        setSection()
        assertTrue("标签基线回归 x=0(实测 left=${left("写报告")})", left("写报告") < 1.dp)
    }

    /** 箭头在**文本**数值之后,不再占行首(合并语义下取到的会是整行,必须看 unmerged 树) */
    @Test fun expandArrowSitsAfterTheNumbers() {
        setSection()
        val label = bounds("写报告")
        val meta = bounds(ctx.getString(R.string.report_task_meta, 2, 100))
        val a = arrow()
        assertTrue("箭头在标签之后(arrow=${a.left} label end=${label.right})", a.left >= label.right)
        assertTrue("箭头在数值之后(arrow=${a.left} meta end=${meta.right})", a.left >= meta.right)
    }

    @Test fun clockNumbersAlignWithTheTaskNumbers() {
        setSection()
        rule.onNodeWithText("写报告").performClick()
        val parentDur = left(ctx.getString(R.string.duration_hm, 1, 30))
        val clockDur = left(ctx.getString(R.string.duration_hm, 1, 0))
        val parentMeta = left(ctx.getString(R.string.report_task_meta, 2, 100))
        val clockMeta = left(ctx.getString(R.string.report_clock_meta, 1, 67))
        assertTrue(
            "子行时长与父行同列(parent=$parentDur clock=$clockDur)",
            kotlin.math.abs(parentDur.value - clockDur.value) < 1f,
        )
        assertTrue(
            "子行占比与父行同列(parent=$parentMeta clock=$clockMeta)",
            kotlin.math.abs(parentMeta.value - clockMeta.value) < 1f,
        )
    }
}
