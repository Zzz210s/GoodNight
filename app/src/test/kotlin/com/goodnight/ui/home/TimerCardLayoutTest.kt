package com.goodnight.ui.home

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.width
import com.goodnight.R
import com.goodnight.data.db.ProfileMode
import com.goodnight.timer.DurationFormat
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.1 Task 7 修复轮 2/3:顶部横带 = chip(左)+ [相位图标][循环徽标](右)的**结构**不变式 ——
 * chip 与居中大数字分属两行、chip 不压徽标、chip 最多占半行(用行的实测宽 328dp)。
 * 徽标恒贴行右沿的用例见 [TimerCardBadgeAlignTest],共享夹具见 [TimerCardLayoutHarness]。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实字体度量(legacy 模式下每字符只算 1px)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TimerCardLayoutTest : TimerCardLayoutHarness() {
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
        assertChipWithinHalfOfTheRow("长标题", longTitle)
    }

    /**
     * v2.2 Task 4:横带文案 =「任务 · 时钟」。加了时钟名后 chip 自然宽更长,仍必须一行放下、
     * 不压相位/循环徽标、不越半行(沿用行的实测宽判定,不用整屏宽)。
     */
    @Test fun chipShowsTaskAndClockOnOneLine() {
        val clockName = "专注 25/5"
        setCard(longTitle, clockName = clockName)
        val label = chipLabel(longTitle, clockName)
        val chip = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = phaseBounds()
        val big = rule.onNodeWithText(DurationFormat.ms(bigMillis), useUnmergedTree = true).getUnclippedBoundsInRoot()
        println("T4 label=$label chip=$chip badge=$badge")
        assertTrue("chip 右沿 ${chip.right} 必须停在相位徽标左沿 ${badge.left} 之前", chip.right <= badge.left)
        assertTrue("chip 底沿 ${chip.bottom} 必须在大数字顶沿 ${big.top} 之上", chip.bottom <= big.top)
        assertChipWithinHalfOfTheRow("任务 · 时钟", label)
    }

    /** 只有时钟(未绑任务):文案只有时钟名,不带分隔符 */
    @Test fun chipShowsClockAloneWhenTaskUnbound() {
        setCard(null, clockName = "专注")
        val label = chipLabel(null, "专注")
        assertEquals("只有时钟名,不带分隔符", "专注", label)
        val row = rowBounds()
        val chip = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        println("T4 clock-only row=$row chip=$chip")
        assertTrue("chip 左沿 ${chip.left} 应贴在行左侧(非居中)", chip.left.value <= row.left.value + row.width.value / 4f)
    }

    /** 都没有(空库):回退「未绑定任务」—— 不再回退相位文案(相位徽标已把相位写进
     *  contentDescription,chip 再写一遍会被读屏连读两遍) */
    @Test fun chipFallsBackToUnboundTextWithoutClockAndTask() {
        setCardWithoutClocks()
        rule.onNodeWithText(ctx.getString(R.string.task_unbound), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.state_idle), useUnmergedTree = true).assertDoesNotExist()
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
