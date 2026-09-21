package com.goodnight.ui.morph

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极坐标插值:端点精确(t=0/1 逐点复现 from/to)、1->2 子路径分裂平滑
 * (真实 IconPaths.PLAY vs PAUSE 经 buildPlanIcon)、旋转不弦线塌缩、
 * 全图标自 morph 与 play->pause 往返的 NaN/Inf 电池。
 */
class InterpolateTest {

    private fun lineSub(y: Float = 0f): SampledPath =
        resample(SubPath(listOf(Cubic(0f, y, 8f, y, 16f, y, 24f, y)), false))

    private fun sample(d: String): List<SampledPath> =
        normalize(parsePathData(d)).map { resample(it) }

    @Test fun endpointExactness() {
        val p = buildPlan(lineSub(0f), lineSub(5f)) // 平移 5 的平行线
        val at0 = interpolate(p, 0f)
        val at1 = interpolate(p, 1f)
        assertEquals(0f, at0[0][0], 0.01f)
        assertEquals(5f, at1[0][1], 0.01f)
        at0[0].forEachIndexed { i, v -> assertEquals(p.fromPts[0][i], v, 1e-3f) }
        at1[0].forEachIndexed { i, v -> assertEquals(p.toPts[0][i], v, 1e-3f) }
    }

    @Test fun oneToTwoSubpathSplitIsSmooth() {
        val p = buildPlanIcon(sample(IconPaths.PLAY), sample(IconPaths.PAUSE))
        assertEquals(2, p.n)
        val mid = interpolate(p, 0.5f)
        assertEquals(2, mid.size)
        mid.forEach { pts -> pts.forEach {
            assertTrue(!it.isNaN()); assertTrue(!it.isInfinite())
        } }
    }

    @Test fun morphPreservesGeometryNotChordCollapse() {
        // 90 度旋转的直线:极坐标插值 t=0.5 处应保持全长(旋转 45 度,跨度 20),
        // 裸 lerp 才会走弦(端点中点距 14.14)。原线全长 20,brief 首末点版量的是
        // 单采样间距(N=64 时恒 ~0.3)不可用,按其注释意图改量端到端跨度。
        // 阈值 16 同时 discriminate 两种插值:极坐标 20.0 过,弦塌缩 14.14 不过。
        val a = resample(SubPath(listOf(Cubic(2f, 12f, 10f, 12f, 16f, 12f, 22f, 12f)), false))
        val b = resample(SubPath(listOf(Cubic(12f, 2f, 12f, 10f, 12f, 16f, 12f, 22f)), false))
        val p = buildPlan(a, b)
        val mid = interpolate(p, 0.5f)[0]
        mid.forEach { assertTrue(!it.isNaN()); assertTrue(!it.isInfinite()) }
        val span = hypot(mid[mid.size - 2] - mid[0], mid[mid.size - 1] - mid[1])
        assertTrue("mid 跨度 $span 塌缩", span > 16f)
    }

    @Test fun nanBatterySelfAndRoundTrip() {
        for (d in IconPaths.ALL) {
            val s = sample(d)
            val p = buildPlanIcon(s, s)
            for (t in floatArrayOf(0f, 0.5f, 1f)) {
                interpolate(p, t).forEach { pts -> pts.forEach {
                    assertTrue("$d t=$t NaN", !it.isNaN())
                    assertTrue("$d t=$t Inf", !it.isInfinite())
                } }
            }
            val at0 = interpolate(p, 0f)
            val at1 = interpolate(p, 1f)
            for (i in 0 until p.n) {
                at0[i].forEachIndexed { j, v -> assertEquals(p.fromPts[i][j], v, 1e-3f) }
                at1[i].forEachIndexed { j, v -> assertEquals(p.toPts[i][j], v, 1e-3f) }
            }
        }
        val play = sample(IconPaths.PLAY)
        val pause = sample(IconPaths.PAUSE)
        val plans = listOf(buildPlanIcon(play, pause), buildPlanIcon(pause, play))
        for (p in plans) for (t in floatArrayOf(0f, 0.5f, 1f)) {
            interpolate(p, t).forEach { pts -> pts.forEach {
                assertTrue(!it.isNaN()); assertTrue(!it.isInfinite())
            } }
        }
    }
}
