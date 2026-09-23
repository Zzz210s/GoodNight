package com.goodnight.ui.tasks

import kotlin.math.roundToInt

/**
 * v2.1 Task 6(评审修复):拖动落点换算。抽成纯函数(无 Compose 依赖)以便单测 —— 上一轮
 * review 指出这段数学无测试,而它正是「行高自适应」改动的风险点。
 *
 * 行高由写死 64dp 改为按内容自适应([androidx.compose.foundation.layout.heightIn])后,
 * 落点不能再按 [TaskRowHeight] 换算:大字号/字体缩放会改变真实行高,按死高换算会随位移累积漂移
 * (拖 2 行只走了 1 行,或反之)。故步进一律由调用方按**实测行高 + 列表间距**传入。
 */

/** 单行步进(px):实测行高 > 0 时 = 实测行高 + 间距;首帧测量前(0)退化到基准步进 [fallbackStepPx] */
internal fun dragStepPx(rowHeightPx: Float, spacingPx: Float, fallbackStepPx: Float): Float =
    if (rowHeightPx > 0f) rowHeightPx + spacingPx else fallbackStepPx

/**
 * 位移跨过的行数:位移除以步进四舍五入(不足半行为 0 = 原位回弹)。
 * [stepPx] 非正(首帧前未测到且无基准)时不做换算,避免除零得 Infinity。
 */
internal fun dragSteps(dragY: Float, stepPx: Float): Int =
    if (stepPx <= 0f) 0 else (dragY / stepPx).roundToInt()

/** 松手落点下标 = 原下标 + 跨过行数,夹到 `[0, count - 1]`;空列表返回 0(调用方不下发移动) */
internal fun dragTargetIndex(index: Int, count: Int, dragY: Float, stepPx: Float): Int {
    if (count <= 0) return 0
    return (index + dragSteps(dragY, stepPx)).coerceIn(0, count - 1)
}
