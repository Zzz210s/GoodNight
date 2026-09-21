package com.goodnight.ui.morph

/**
 * 极坐标插值(spec D2.5):每 (from, to) 对,运动分解为 similarity + residual。
 * S = similarity(from, to);t 时刻取 S_t = (theta·t, 1+(sigma-1)·t, tx·t, ty·t)
 * (similarity 部分在其自然空间线性插值,t=0 为恒等),
 * position(t) = S_t(from) + t·(to - S_1(from))(residual 部分线性)。
 *
 * 端点精确:t=0 逐位复现 from(t=0 时 S_t 恒等、residual 项为 0,浮点下逐位
 * 相等),t=1 给出 to(float 误差级);中段自然旋转/缩放,不走弦——90 度旋转
 * 的直线在 t=0.5 仍保持全长。复制产生的对(分裂)各自独立插值,静止时重叠
 * 副本同描边不可见。Block transport(全局同余时的整体搬运混合)属 Task 6
 * 引擎职责(用 MorphPlan.pairSims 判定全局 residual<5e-3 后叠加),本文件
 * 不实现——作用域边界。
 */

/** 计划中每对在 t 处的点云位置(t∈[0,1];任意有限 t 输出亦有限)。 */
fun interpolate(p: MorphPlan, t: Float): List<FloatArray> {
    val n = minOf(p.fromPts.size, p.toPts.size)
    val out = ArrayList<FloatArray>(n)
    for (i in 0 until n) {
        val f = p.fromPts[i]
        val g = p.toPts[i]
        val s = p.pairSims.getOrNull(i) ?: similarity(f, g)
        out.add(interpPair(f, g, s, t))
    }
    return out
}

/** 单对插值:position(t) = S_t(from) + t·(to - S_1(from))。 */
internal fun interpPair(f: FloatArray, g: FloatArray, s: SimTransform, t: Float): FloatArray {
    val st = SimTransform(s.theta * t, 1f + (s.scale - 1f) * t, s.tx * t, s.ty * t)
    val baseT = apply(st, f)
    val base1 = apply(s, f)
    val m = minOf(f.size, g.size)
    val out = FloatArray(m)
    for (i in 0 until m) out[i] = baseT[i] + t * (g[i] - base1[i])
    return out
}
