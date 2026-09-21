package com.goodnight.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** D6:系统 animator 缩放为 0 时(关闭动画)全动效退化 */
class MotionTest {
    @Test fun zeroScaleDisablesAnimations() {
        assertEquals(false, animationsEnabledFor(0f))
    }

    @Test fun nonzeroScaleEnablesAnimations() {
        assertEquals(true, animationsEnabledFor(1f))
        assertEquals(true, animationsEnabledFor(0.5f))
        assertEquals(true, animationsEnabledFor(10f))
    }
}
