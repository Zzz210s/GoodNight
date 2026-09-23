package com.goodnight.ui.home

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2.1 Task 7 修复轮 3:`**徽标右沿恒等于行右沿**` —— 四档参数(短/长标题、360dp 屏 /
 * 800dp 等效宽屏)+ 一档系统字号。chip 的自然宽不足份额时,旧写法把余量堆到行尾
 * (360dp 短标题偏左 66dp、800dp 等效宽落到卡片中间),且偏左量随字号一起漂。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实字体度量(legacy 模式下每字符只算 1px)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-mdpi", application = Application::class)
class TimerCardBadgeAlignTest : TimerCardLayoutHarness() {
    /** ① 短标题:余量最大,旧写法偏左最多(≈66dp),必须仍贴右沿 */
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

    /**
     * ④ 系统字体放大 1.3(修复轮 3 致死的正是字号轴)+ 短标题:chip 自然宽不足份额时,
     * 旧写法的徽标左沿 = `chipW + 份额`,chip 变宽 -> 徽标随之漂移(实测 290dp vs 1.0 的 278dp)。
     * 先断言配置确实到达组合(否则本用例会静默退化成 1.0 的重复覆盖),再断言徽标仍贴行右沿。
     */
    @Test fun largeFontScaleKeepsCycleBadgeAtRowRightEdge() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            setCard(shortTitle)
            assertEquals("fontScale 应已生效(fontScale=1.0 时本用例是重复覆盖)", 1.3f, observedFontScale, 0.01f)
            assertRightEdgeFlush("fontScale1.3+短标题")
            assertChipWithinHalfOfTheRow("fontScale1.3+短标题", shortTitle)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
