package com.goodnight.ui.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.goodnight.data.db.ProfileEntity
import com.goodnight.timer.DurationFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.1 Task 7 修复轮 2(W1):chip 必须在**内容流内**、与居中的大数字分属两行 ——
 * 长标题不可能压住数字,与屏宽/系统字号/数字位数都无关。
 *
 * 用 Robolectric 跑 Compose 的 `createComposeRule()`,直接读**测量/放置后的 bounds**,
 * 不靠肉眼截图;屏宽设成 360dp 等效(常见机型,旧"96dp 上限"方案在这里会重叠)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实字体度量(legacy 模式下每字符只算 1px)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TimerCardLayoutTest {
    @get:Rule val rule = createComposeRule()

    /** 长标题(远超旧的 96dp 上限) */
    private val longTitle = "读论文与写摘要笔记与整理参考文献清单并归档"

    /** 8 字符大数字:`DurationFormat.ms` 是 `%02d` 累计分钟、无上限(10000 分钟 -> "10000:00") */
    private val bigMillis = 10_000L * 60_000L

    private fun setCard(taskTitle: String?) {
        val profile = ProfileEntity(id = 1, name = "P", workMinutes = 12_000, restMinutes = 5, createdAt = 0)
        rule.setContent {
            MaterialTheme {
                TimerCard(
                    ui = HomeUiState(ready = true, profiles = listOf(profile), activeProfileId = 1),
                    displayMillis = bigMillis,
                    taskTitle = taskTitle,
                    onTaskChipClick = {},
                    onStart = {}, onPause = {}, onResume = {}, onSkip = {}, onStop = {},
                    onGoSettings = {},
                )
            }
        }
    }

    private fun rootWidth() = rule.onRoot().getUnclippedBoundsInRoot().width

    /** 旧行为(把 chip 当 Box 覆盖层挂 TopStart)会让这条断言变红:chip 底沿会落到数字顶沿之下 */
    @Test fun longTitleChipSitsAboveTheBigNumber() {
        setCard(longTitle)
        val number = DurationFormat.ms(bigMillis)
        assertEquals("10000:00", number) // 8 字符,不是 6
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val big = rule.onNodeWithText(number, useUnmergedTree = true).getUnclippedBoundsInRoot()
        println("W1 chip=$chip bigNumber=$big rootW=${rootWidth()}")
        assertTrue(
            "chip 底沿 ${chip.bottom} 必须在大数字顶沿 ${big.top} 之上(两行,结构上不可能重叠)",
            chip.bottom <= big.top,
        )
    }

    /** chip 与右侧相位徽标同排且互不重叠(旧布局是同一个 Box 的两个覆盖层,重叠风险高) */
    @Test fun chipAndPhaseBadgeDoNotOverlap() {
        setCard(longTitle)
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithContentDescription("空闲", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("chip 右沿 ${chip.right} 必须停在相位徽标左沿 ${badge.left} 之前", chip.right <= badge.left)
    }

    /** chip 最多占半行(weight 等分):chip 右沿不越过卡片中线 */
    @Test fun chipTakesAtMostHalfOfTheRow() {
        setCard(longTitle)
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("chip 右沿 ${chip.right} 不应越过卡片中线 ${rootWidth() / 2}", chip.right <= rootWidth() / 2 + 1.dp)
        assertTrue("chip 左沿 ${chip.left} 应在卡片内", chip.left >= 0.dp)
    }
}
