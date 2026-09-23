package com.goodnight.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1 Task 6(评审修复):拖动落点换算的单测。上一轮 review 指出该数学无测试,而行高从写死
 * 64dp 改为按内容自适应后,换算基准由「常量」变成「实测值」—— 这里锁住不变式:
 * 步进取自实测行高(而非基准常量),位移四舍五入到整行,落点被夹在列表内。
 *
 * 旧实现(`(dragY / (TaskRowHeight + TaskRowSpacing).toPx()).roundToInt()`)在行高变高时
 * 会多跨一行:见 [measuredRowHeightPreventsStepDrift] 的第二个断言。
 */
class TaskDragMathTest {
    private val baseStep = 76f // 基准:64dp 行高 + 12dp 间距(1x 密度)
    private val bigStep = 120f // 大字号实测:108dp 行高 + 12dp 间距

    /** 首帧测量前(行高 0)退化到基准步进,不能变成 0(否则落点恒不动作) */
    @Test fun stepFallsBackBeforeFirstMeasure() {
        assertEquals(baseStep, dragStepPx(0f, 12f, baseStep), 0.001f)
        assertEquals(120f, dragStepPx(108f, 12f, baseStep), 0.001f)
    }

    /** 行高变高后落点不漂移:同样的位移,按实测步进只跨 1 行,按基准常量会跨 2 行 */
    @Test fun measuredRowHeightPreventsStepDrift() {
        val step = dragStepPx(108f, 12f, baseStep)
        assertEquals(1, dragTargetIndex(index = 0, count = 5, dragY = 130f, stepPx = step))
        assertEquals(2, dragTargetIndex(index = 0, count = 5, dragY = 130f, stepPx = baseStep))
    }

    /** 不足半行不动作(原位回弹);超过半行跨一行,方向对称 */
    @Test fun displacementRoundsToNearestRow() {
        assertEquals(0, dragSteps(30f, baseStep))
        assertEquals(1, dragSteps(40f, baseStep))
        assertEquals(-1, dragSteps(-40f, baseStep))
        assertEquals(0, dragSteps(-30f, baseStep))
        val count = 4
        assertEquals(1, dragTargetIndex(1, count, 30f, baseStep))
        assertEquals(0, dragTargetIndex(1, count, -40f, baseStep))
    }

    /** 落点夹在 [0, count-1]:向上拖过头不出界、向下拖过头不出界 */
    @Test fun targetIndexIsClampedIntoList() {
        assertEquals(0, dragTargetIndex(index = 0, count = 3, dragY = -1_000f, stepPx = baseStep))
        assertEquals(2, dragTargetIndex(index = 2, count = 3, dragY = 1_000f, stepPx = baseStep))
        assertEquals(2, dragTargetIndex(index = 1, count = 5, dragY = 100f, stepPx = baseStep))
    }

    /** 退化输入不抛也不乱动:步进 0/负、空列表 */
    @Test fun degenerateInputsAreNoOps() {
        assertEquals(0, dragSteps(100f, 0f))
        assertEquals(0, dragSteps(100f, -5f))
        assertEquals(0, dragTargetIndex(0, count = 0, dragY = 100f, stepPx = baseStep))
        assertEquals(0, dragTargetIndex(0, count = 0, dragY = 100f, stepPx = 0f))
    }
}
