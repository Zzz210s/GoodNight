package com.goodnight.ui.report

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.ClockSlice
import com.goodnight.data.TaskSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.2 Task 6:报表「按任务」区块的时钟明细**展开交互**。
 *
 * 钉住:默认收起(展开内容零渲染)、点任务行展开出该任务各时钟的时长/次数/占比、
 * 再点收起;没有时钟明细的行不给展开入口(不留死可点区域)。
 * 文案走资源(zh 限定符),因此断言用 ctx.getString 而不是硬编码字面量。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TaskBreakdownSectionTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun minutes(m: Long) = m * 60_000L

    /** 一行任务:90 分钟,分属两个时钟 60 / 30(任务内占比 67 / 33) */
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

    private fun setSection(slices: List<TaskSlice>) {
        rule.setContent { MaterialTheme { TaskBreakdownSection(taskBreakdownUi(slices)) } }
    }

    private fun detailsNodes() = rule
        .onAllNodesWithContentDescription(ctx.getString(R.string.report_clock_details), useUnmergedTree = true)
        .fetchSemanticsNodes().size

    private fun clockRowNodes() =
        rule.onAllNodesWithTag(REPORT_CLOCK_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun clockDetailIsCollapsedByDefault() {
        setSection(listOf(taskRow()))
        rule.onNodeWithText("写报告").assertExists()
        assertEquals("默认收起:一个时钟子行都不渲染", 0, clockRowNodes())
        rule.onNodeWithText("番茄").assertDoesNotExist()
        rule.onNodeWithText("深度工作").assertDoesNotExist()
        assertEquals("有明细的行才带展开入口", 1, detailsNodes())
    }

    @Test fun clickRevealsClockRowsWithDurationCountAndPercent() {
        setSection(listOf(taskRow()))
        rule.onNodeWithText("写报告").performClick()
        assertEquals(2, clockRowNodes())
        rule.onNodeWithText("番茄").assertExists()
        rule.onNodeWithText("深度工作").assertExists()
        // 60 分钟 / 1 次 / 67%;30 分钟 / 1 次 / 33%(任务内最大余数法,合计 100)
        rule.onNodeWithText(ctx.getString(R.string.duration_hm, 1, 0)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.duration_m, 30)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.report_task_meta, 1, 67)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.report_task_meta, 1, 33)).assertExists()
    }

    @Test fun clickingAgainCollapsesTheClockRows() {
        setSection(listOf(taskRow()))
        rule.onNodeWithText("写报告").performClick()
        rule.onNodeWithText("番茄").assertExists()
        rule.onNodeWithText("写报告").performClick()
        assertEquals(0, clockRowNodes())
        rule.onNodeWithText("番茄").assertDoesNotExist()
    }

    @Test fun unboundRowExpandsWithItsClocks() {
        setSection(
            listOf(
                TaskSlice(
                    taskId = null,
                    title = null,
                    millis = minutes(30),
                    count = 1,
                    clocks = listOf(ClockSlice(12L, "通用时钟", minutes(30), 1)),
                )
            )
        )
        val unbound = ctx.getString(R.string.task_unbound)
        rule.onNodeWithText(unbound).assertExists()
        rule.onNodeWithText("通用时钟").assertDoesNotExist()
        rule.onNodeWithText(unbound).performClick()
        rule.onNodeWithText("通用时钟").assertExists()
        // 单时钟任务:主行与时钟子行的「次数 · 占比」自然相同(1 次 · 100%),两处都应出现
        assertEquals(
            "主行与展开行都显示 1 次 · 100%",
            2,
            rule.onAllNodesWithText(ctx.getString(R.string.report_task_meta, 1, 100)).fetchSemanticsNodes().size,
        )
    }

    /** 时钟行真删后的悬挂引用:界面回退成「未知时钟」,不渲染空白行 */
    @Test fun clockWithoutNameFallsBackToUnknownLabel() {
        setSection(
            listOf(
                TaskSlice(1L, "写报告", minutes(20), 1, listOf(ClockSlice(777L, null, minutes(20), 1)))
            )
        )
        rule.onNodeWithText("写报告").performClick()
        rule.onNodeWithText(ctx.getString(R.string.report_clock_unknown)).assertExists()
    }

    /** 没有时钟明细的行:不给展开入口,点了也不产生任何子行 */
    @Test fun rowWithoutClocksIsNotExpandable() {
        setSection(listOf(TaskSlice(1L, "空任务", minutes(0), 0, emptyList())))
        assertEquals(0, detailsNodes())
        rule.onNodeWithText("空任务").performClick()
        assertEquals(0, clockRowNodes())
    }

    /** 同一屏两行各自独立展开:展开一行不影响另一行 */
    @Test fun expandingOneRowLeavesTheOtherCollapsed() {
        setSection(
            listOf(
                taskRow(),
                TaskSlice(2L, "买菜", minutes(30), 1, listOf(ClockSlice(12L, "通用时钟", minutes(30), 1))),
            )
        )
        rule.onNodeWithText("写报告").performClick()
        rule.onNodeWithText("番茄").assertExists()
        rule.onNodeWithText("深度工作").assertExists()
        rule.onNodeWithText("通用时钟").assertDoesNotExist()
        assertEquals("只展开写报告那一行的两个时钟子行", 2, clockRowNodes())
    }

    /**
     * 视觉(实测 bounds,不靠截图):展开的时钟子行落在任务行**下方**,且名字**向右缩进** ——
     * 层级关系必须能从节点几何上看出来,而不是只靠「节点存在」。
     */
    @Test fun clockRowsSitBelowAndIndentedFromTheirTaskRow() {
        setSection(listOf(taskRow()))
        rule.onNodeWithText("写报告").performClick()
        val task = rule.onNodeWithText("写报告").getUnclippedBoundsInRoot()
        val clock = rule.onNodeWithText("番茄").getUnclippedBoundsInRoot()
        assertTrue("时钟子行在任务行下方(top=${clock.top} >= bottom=${task.bottom})", clock.top >= task.bottom)
        assertTrue(
            "时钟子行相对任务名缩进(clock.left=${clock.left} - task.left=${task.left})",
            clock.left - task.left >= 12.dp,
        )
    }

    /** 只有一个时钟的任务内占比恒为 100(最大余数法的分母就是它自己) */
    @Test fun singleClockTaskShowsFullPercent() {
        setSection(listOf(TaskSlice(1L, "写报告", minutes(60), 1, listOf(ClockSlice(10L, "番茄", minutes(60), 1)))))
        rule.onNodeWithText("写报告").performClick()
        assertEquals(1, clockRowNodes())
        assertEquals(
            2,
            rule.onAllNodesWithText(ctx.getString(R.string.report_task_meta, 1, 100)).fetchSemanticsNodes().size,
        )
    }
}
