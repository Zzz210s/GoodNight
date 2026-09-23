package com.goodnight.ui.home

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.timer.DurationFormat
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.1 Task 7 修复轮 3:顶部横带 = chip(左)+ [相位图标][循环徽标](右)。
 *
 * 两组不变式,都用**测量/放置后的真实 bounds** 断言(Robolectric + `createComposeRule()`,
 * 不靠肉眼截图):
 * 1. chip 与居中的大数字分属两行 —— 重叠在结构上不可能发生,与屏宽/字号/数字位数无关;
 * 2. **徽标右沿恒等于行右沿** —— 徽标组自己等分剩余空间并在组内 End 对齐,外行
 *    `Arrangement.SpaceBetween` 把 chip 的自然宽不足半行时的余量放进两者之间。
 *    旧写法(等分 Spacer + 默认 Start)会把余量堆到行尾,徽标左沿 = `chipW + 半行`,
 *    随任务名长度与屏宽漂移(修复轮 2 的假绿:当时只测了「chip 恰好等于份额」的长标题)。
 *
 * 屏宽设成 360dp 等效(常见机型);宽屏用例单独用 `w800dp` 覆盖"平板份额更大"的情形。
 * 行宽必须用 `TIMER_TOP_ROW_TAG` 的实测值(328dp),不是整屏宽(360dp)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实字体度量(legacy 模式下每字符只算 1px)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TimerCardLayoutTest {
    @get:Rule val rule = createComposeRule()

    /** 长标题(21 汉字,远超半行份额) */
    private val longTitle = "读论文与写摘要笔记与整理参考文献清单并归档"

    /** 短标题:chip 自然宽远小于半行份额 —— 修复轮 2 漏测的正是这一档 */
    private val shortTitle = "写周报"

    /** 8 字符大数字:`DurationFormat.ms` 是 `%02d` 累计分钟、无上限(10000 分钟 -> "10000:00") */
    private val bigMillis = 10_000L * 60_000L

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun setCard(taskTitle: String?, mode: Int = ProfileMode.COUNTDOWN, snap: RuntimeSnapshot? = null) {
        val profile = ProfileEntity(
            id = 1, name = "P", workMinutes = 12_000, restMinutes = 5, mode = mode, createdAt = 0,
        )
        rule.setContent {
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

    private fun rowBounds() = rule.onNodeWithTag(TIMER_TOP_ROW_TAG).getUnclippedBoundsInRoot()

    /** 相位徽标(本轮断言里的"右端元素",徽标右沿即横带最右沿) */
    private fun phaseBounds(labelRes: Int = R.string.state_idle) =
        rule.onNodeWithContentDescription(ctx.getString(labelRes), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    /** 循环徽标(contentDescription 由 string 资源生成,不写死文案) */
    private fun cycleBounds(): DpRect =
        rule.onNodeWithContentDescription(ctx.getString(R.string.cycle_n, 0), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    private fun assertRightEdgeFlush(case: String): Pair<Float, Float> {
        val row = rowBounds()
        val cycle = cycleBounds()
        println("$case row=$row cycleBadge=$cycle")
        assertEquals(
            "$case:循环徽标右沿 ${cycle.right} 必须等于行右沿 ${row.right}(旧写法余量堆行尾 -> 偏左)",
            row.right.value, cycle.right.value, 1f,
        )
        return row.right.value to cycle.right.value
    }

    /** 旧行为(把 chip 当 Box 覆盖层挂 TopStart)会让这条断言变红:chip 底沿会落到数字顶沿之下 */
    @Test fun longTitleChipSitsAboveTheBigNumber() {
        setCard(longTitle)
        val number = DurationFormat.ms(bigMillis)
        assertEquals("10000:00", number) // 8 字符,不是 6
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val big = rule.onNodeWithText(number, useUnmergedTree = true).getUnclippedBoundsInRoot()
        println("W1 chip=$chip bigNumber=$big rootW=${rule.onRoot().getUnclippedBoundsInRoot().width}")
        assertTrue(
            "chip 底沿 ${chip.bottom} 必须在大数字顶沿 ${big.top} 之上(两行,结构上不可能重叠)",
            chip.bottom <= big.top,
        )
    }

    /** chip 与右侧相位/循环徽标同排且互不重叠(旧布局是同一个 Box 的两个覆盖层,重叠风险高) */
    @Test fun chipAndPhaseBadgeDoNotOverlap() {
        setCard(longTitle)
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = phaseBounds()
        assertTrue("chip 右沿 ${chip.right} 必须停在相位徽标左沿 ${badge.left} 之前", chip.right <= badge.left)
    }

    /** chip 最多占半行(weight 等分):用**行**的真实宽度(328dp),不是整屏宽(360dp) */
    @Test fun chipTakesAtMostHalfOfTheRow() {
        setCard(longTitle)
        val row = rowBounds()
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val half = row.left.value + row.width.value / 2f
        println("W1b row=$row half=$half chip=$chip")
        assertTrue("chip 右沿 ${chip.right} 不应越过行中线 ${half}dp", chip.right.value <= half + 1f)
        assertTrue("chip 左沿 ${chip.left} 应在行内", chip.left >= row.left)
    }

    /** ① 短标题:余量最大,旧写法偏左最多(≈150dp),必须仍贴右沿 */
    @Test fun shortTitleKeepsCycleBadgeAtRowRightEdge() {
        setCard(shortTitle)
        assertRightEdgeFlush("短标题")
    }

    /** ② 长标题(21 汉字):chip 恰好吃满份额 —— 修复轮 2 只有这一档,故 Critical 假绿 */
    @Test fun longTitleKeepsCycleBadgeAtRowRightEdge() {
        setCard(longTitle)
        assertRightEdgeFlush("长标题")
    }

    /** ③ 宽屏(等效 800dp,平板份额 ≈368dp):长标题也不再吃满份额,旧写法会落到卡片中间 */
    @Test
    @Config(qualifiers = "zh-w800dp-h1280dp-mdpi")
    fun wideScreenKeepsCycleBadgeAtRowRightEdge() {
        setCard(longTitle)
        assertRightEdgeFlush("宽屏+长标题")
    }

    /** ③ b 宽屏 + 短标题(份额最大、chip 最窄的极端组合) */
    @Test
    @Config(qualifiers = "zh-w800dp-h1280dp-mdpi")
    fun wideScreenShortTitleKeepsCycleBadgeAtRowRightEdge() {
        setCard(shortTitle)
        assertRightEdgeFlush("宽屏+短标题")
    }

    /** COUNTUP 路径(建议项:旧布局测试只覆盖倒计时):无到期/循环概念 -> 循环徽标不渲染、
     *  skip 键隐藏,而相位徽标仍贴行右沿。snap.countUp 与 profile.mode 两条判定路径同时走。 */
    @Test fun countUpHidesCycleBadgeAndSkipKeyKeepingPhaseAtRowRightEdge() {
        val snap = RuntimeSnapshot(
            profileId = 1, workMillis = 60_000, restMillis = 30_000, phase = Phase.WORK,
            status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 0,
            endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
            savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0, countUp = true,
        )
        setCard(longTitle, mode = ProfileMode.COUNTUP, snap = snap)
        rule.onNodeWithContentDescription(ctx.getString(R.string.cycle_n, 0), useUnmergedTree = true)
            .assertDoesNotExist()
        val row = rowBounds()
        val phase = phaseBounds(R.string.state_work)
        println("COUNTUP row=$row phase=$phase")
        assertEquals("COUNTUP:相位徽标右沿 ${phase.right} 必须等于行右沿 ${row.right}", row.right.value, phase.right.value, 1f)
        val chip = rule.onNodeWithText(longTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("COUNTUP:chip 右沿 ${chip.right} 应停在相位徽标左沿 ${phase.left} 之前", chip.right <= phase.left)
        // 运行中:暂停键在(证明动作区确实按 snap 渲染了),但跳过键因 COUNTUP 隐藏
        rule.onNodeWithContentDescription(ctx.getString(R.string.act_pause), useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithContentDescription(ctx.getString(R.string.act_skip), useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
