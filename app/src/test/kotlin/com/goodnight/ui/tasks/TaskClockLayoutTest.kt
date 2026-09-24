package com.goodnight.ui.tasks

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.2 Task 3:任务卡片时钟 chip 行的**结构**不变式 —— 用测量后的真实 bounds 断言
 * (Robolectric + `createComposeRule()` + `@GraphicsMode(NATIVE)`,照 2.1 的布局测试写法)。
 *
 * 钉住:
 * 1. 长任务名 + 多时钟时 chip 行仍在标题**下方**(同一 Column,结构上不重叠),且行不折行、不撑宽卡片;
 * 2. 时钟多到超过卡片宽度时是**横向滚动**(内容宽于容器,末个 chip 落在行右沿之外),不是换行/裁掉;
 * 3. 图例在 chip 行之下、卡片之内;通用时钟的边框是淡色(outlineVariant),与专属时钟不同。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实字体度量(legacy 模式每字符只算 1px)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TaskClockLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** 长任务名(21 汉字,远超行宽);标题单行省略,不与下方针 chip 行争空间 */
    private val longTitle = "读论文与写摘要笔记与整理参考文献清单并归档"

    private fun clock(id: Long, name: String, taskId: Long?) = ProfileEntity(
        id = id, name = name, workMinutes = 25, restMinutes = 5, createdAt = id, taskId = taskId,
    )

    /** 3 个专属 + 2 个通用,名字都很长:合计宽度远超 360dp 屏宽 */
    private fun crowded() = TaskClocks(
        specific = listOf(
            clock(1, "论文精读四十五分钟", 7),
            clock(2, "写摘要笔记三十分钟", 7),
            clock(3, "整理参考文献清单", 7),
        ),
        generic = listOf(
            clock(4, "通用深度工作二十五分钟", null),
            clock(5, "通用快速处理五分钟", null),
        ),
    )

    private fun setRow(clocks: TaskClocks) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ActiveTaskRow(
                        task = TaskEntity(id = 7, title = longTitle, createdAt = 0, sortOrder = 1),
                        index = 0,
                        count = 1,
                        clocks = clocks,
                        onMove = { _, _ -> },
                        onToggle = {},
                        onRename = {},
                        onDelete = {},
                        onClockClick = {},
                        onAddClock = {},
                    )
                }
            }
        }
    }

    private fun rowBounds() = rule.onNodeWithTag(TASK_CLOCK_ROW_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test fun longTitleAndManyClocksStayOnSeparateLinesWithoutOverflow() {
        setRow(crowded())
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val title = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val row = rowBounds()
        println("W1 title=$title row=$row rootW=${root.width}")

        assertTrue(
            "chip 行顶沿 ${row.top} 必须在标题底沿 ${title.bottom} 之下(不重叠)",
            row.top >= title.bottom,
        )
        assertTrue("chip 行右沿 ${row.right} 不得越出屏宽 ${root.right}", row.right <= root.right + 0.5.dp)

        // 单行判据用「同处一行」而不是魔术高度:M3 chip 的触摸目标 48dp,折行会让行高翻倍。
        // 首个 chip 与末个「+ 添加时钟」top 相同 = 全部落在同一行。
        val firstChip = rule.onNodeWithText("论文精读四十五分钟", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val add = rule.onNodeWithText(ctx.getString(R.string.task_add_clock), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals("全部 chip 必须同处一行(top 相同)", firstChip.top.value, add.top.value, 0.5f)
        assertTrue("chip 行高 ${row.height} 应只是一个 chip 触摸目标(非零且不折行)", row.height.value in 20f..60f)
        assertTrue(
            "内容宽于容器才需要横向滚动:末个 chip 右沿 ${add.right} 应落在行右沿 ${row.right} 之外",
            add.right > row.right,
        )
    }

    @Test fun legendSitsBelowChipsInsideTheCard() {
        setRow(crowded())
        val row = rowBounds()
        val legend = rule.onNodeWithText(ctx.getString(R.string.task_clock_legend), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        println("W2 row=$row legend=$legend")

        assertTrue("图例顶沿 ${legend.top} 应在 chip 行底沿 ${row.bottom} 之下", legend.top >= row.bottom)
        assertTrue("图例右沿 ${legend.right} 不得越出屏宽", legend.right <= root.right + 0.5.dp)
    }

    /** 无时钟的卡片也保留 chip 行(只有「+ 添加时钟」)与图例,行不折、不溢出 */
    @Test fun emptyClockCardStillFitsTheAddChip() {
        setRow(TaskClocks(emptyList(), emptyList()))
        val row = rowBounds()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        rule.onNodeWithText(ctx.getString(R.string.task_add_clock), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.task_clock_legend), useUnmergedTree = true).assertExists()
        assertTrue("空卡片也只有一行 chip:高 ${row.height}", row.height.value in 20f..60f)
        assertTrue("chip 行右沿 ${row.right} 不得越出屏宽", row.right <= root.right + 0.5.dp)
    }

    /** 通用 = 淡色描边(outlineVariant),专属 = 更实的 outline:图例的说法必须与代码一致 */
    @Test fun genericClocksUseThePaleOutline() {
        val scheme = lightColorScheme()
        assertEquals(scheme.outlineVariant, (clockBorder(generic = true, scheme = scheme).brush as SolidColor).value)
        assertNotEquals(
            "专属与通用必须能看出来不同",
            (clockBorder(generic = false, scheme = scheme).brush as SolidColor).value,
            (clockBorder(generic = true, scheme = scheme).brush as SolidColor).value,
        )
    }
}
