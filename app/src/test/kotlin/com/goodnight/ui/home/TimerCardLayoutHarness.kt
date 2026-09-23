package com.goodnight.ui.home

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule

/**
 * [TimerCard] 顶部横带布局断言的底座:统一 fixture、节点取法与「徽标右沿 == 行右沿」断言。
 * 用例按主题拆两个文件:[TimerCardLayoutTest](结构)/ [TimerCardBadgeAlignTest](徽标对齐);
 * Robolectric 注解与 `@Config` 写在具体类上(不依赖注解继承),本类只放共享部件。
 *
 * 两组不变式都用**测量/放置后的真实 bounds**断言(Robolectric + `createComposeRule()`,
 * 不靠肉眼截图):
 * 1. chip 与居中的大数字分属两行 —— 重叠在结构上不可能发生,与屏宽/字号/数字位数无关;
 * 2. **徽标右沿恒等于行右沿** —— 徽标组自己等分剩余空间并在组内 End 对齐,外行
 *    `Arrangement.SpaceBetween` 把 chip 自然宽不足半行时的余量放进两者之间。
 *    旧写法(等分 Spacer + 默认 Start)会把余量堆到行尾,徽标左沿 = `chipW + 半行`,
 *    随任务名长度、屏宽与字号漂移(修复轮 2 的假绿:当时只测了「chip 恰好等于份额」的长标题)。
 *
 * 行宽必须用 `TIMER_TOP_ROW_TAG` 的实测值(328dp),不是整屏宽(360dp)。
 */
abstract class TimerCardLayoutHarness {
    @get:Rule val rule = createComposeRule()

    /** 长标题(21 汉字,远超半行份额) */
    protected val longTitle = "读论文与写摘要笔记与整理参考文献清单并归档"

    /** 短标题:chip 自然宽远小于半行份额 —— 修复轮 2 漏测的正是这一档 */
    protected val shortTitle = "写周报"

    /** 8 字符大数字:`DurationFormat.ms` 是 `%02d` 累计分钟、无上限(10000 分钟 -> "10000:00") */
    protected val bigMillis = 10_000L * 60_000L

    protected val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** setContent 时从 LocalDensity 读到的 fontScale:确认字号轴确实生效(防用例静默退化) */
    protected var observedFontScale = 1f

    protected fun setCard(taskTitle: String?, mode: Int = ProfileMode.COUNTDOWN, snap: RuntimeSnapshot? = null) {
        val profile = ProfileEntity(
            id = 1, name = "P", workMinutes = 12_000, restMinutes = 5, mode = mode, createdAt = 0,
        )
        rule.setContent {
            observedFontScale = LocalDensity.current.fontScale
            MaterialTheme {
                TimerCard(
                    ui = HomeUiState(ready = true, profiles = listOf(profile), activeProfileId = 1, snap = snap),
                    displayMillis = bigMillis,
                    taskTitle = taskTitle,
                    onTaskChipClick = {},
                    onStart = {}, onPause = {}, onResume = {}, onSkip = {}, onStop = {},
                    onGoSettings = {},
                )
            }
        }
    }

    protected fun rowBounds() = rule.onNodeWithTag(TIMER_TOP_ROW_TAG).getUnclippedBoundsInRoot()

    /** 相位徽标(断言里的"右端元素",徽标右沿即横带最右沿) */
    protected fun phaseBounds(labelRes: Int = R.string.state_idle) =
        rule.onNodeWithContentDescription(ctx.getString(labelRes), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    /** 循环徽标(contentDescription 由 string 资源生成,不写死文案) */
    protected fun cycleBounds(): DpRect =
        rule.onNodeWithContentDescription(ctx.getString(R.string.cycle_n, 0), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    protected fun assertRightEdgeFlush(case: String) {
        val row = rowBounds()
        val cycle = cycleBounds()
        println("$case row=$row cycleBadge=$cycle")
        assertEquals(
            "$case:循环徽标右沿 ${cycle.right} 必须等于行右沿 ${row.right}(旧写法余量堆行尾 -> 偏左)",
            row.right.value, cycle.right.value, 1f,
        )
    }

    /** chip 右沿不得越过行中线:份额按**行**宽算,不是整屏宽 */
    protected fun assertChipWithinHalfOfTheRow(case: String, title: String) {
        val row = rowBounds()
        val chip = rule.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val half = row.left.value + row.width.value / 2f
        println("$case row=$row half=$half chip=$chip")
        assertTrue("$case:chip 右沿 ${chip.right} 不应越过行中线 ${half}dp", chip.right.value <= half + 1f)
        assertTrue("$case:chip 左沿 ${chip.left} 应在行内", chip.left >= row.left)
    }
}
