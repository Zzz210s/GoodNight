package com.goodnight.ui.theme

import androidx.compose.animation.core.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4 运动语言 token 纯值断言:锁初值与相对关系(出快于进、错峰在 sane 区间)。
 * 曲线观感属 Task 8 模拟器目检范畴,不在单测覆盖。
 */
class MotionTokensTest {
    @Test fun springInitialValues() {
        assertEquals(0.6f, MotionTokens.PressSpring.dampingRatio, 0f)
        assertEquals(0.6f, MotionTokens.MorphSpring.dampingRatio, 0f)
        assertEquals(Spring.StiffnessMediumLow, MotionTokens.MorphSpring.stiffness)
        assertEquals(0.55f, MotionTokens.ReflowSpring.dampingRatio, 0f)
    }

    @Test fun textSwapExitIsFasterThanEnter() {
        assertTrue(MotionTokens.TextSwapExit.durationMillis < MotionTokens.TextSwapEnter.durationMillis)
        assertEquals(160, MotionTokens.TextSwapEnter.durationMillis)
        assertEquals(100, MotionTokens.TextSwapExit.durationMillis)
    }

    @Test fun staggerWithinSaneRange() {
        assertTrue(MotionTokens.StaggerMs in 20..80)
        assertEquals(40, MotionTokens.StaggerMs)
    }
}
