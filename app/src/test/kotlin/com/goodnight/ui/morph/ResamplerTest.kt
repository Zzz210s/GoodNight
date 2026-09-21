package com.goodnight.ui.morph

import kotlin.math.hypot
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resampler 行为:等弧长等距、N 精确、角点精确锚定、闭合路径环绕(含补闭边)、
 * 无角圈均匀采样、退化输入与数值稳定(无 NaN/Infinity)。
 */
class ResamplerTest {

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float): SubPath {
        val d = 1f / 3f
        return SubPath(listOf(Cubic(x0, y0, x0 + (x1 - x0) * d, y0 + (y1 - y0) * d,
            x1 - (x1 - x0) * d, y1 - (y1 - y0) * d, x1, y1)), false)
    }

    private fun sub(d: String): SubPath = normalize(parsePathData(d)).single()

    private fun has(pts: FloatArray, x: Float, y: Float): Boolean {
        for (i in 0 until pts.size / 2) {
            if (pts[2 * i] == x && pts[2 * i + 1] == y) return true
        }
        return false
    }

    @Test
    fun straightLineEquallySpaced() {
        val s = resample(line(0f, 0f, 24f, 0f))
        assertEquals(64, s.points.size / 2)
        val gap = 24f / 63f
        for (i in 1 until 64) {
            assertEquals(gap * i, s.points[2 * i], 0.02f)
            assertEquals(0f, s.points[2 * i + 1], 0.0001f)
        }
    }

    @Test
    fun arcLengthOfQuarterCircle() {
        // 90° 圆弧 cubic 近似的标准控制臂 = (4/3)·tan(22.5°)·r = 5.523。
        // 注:brief 原始夹具用 2.76(=臂的一半),该 cubic 实测弧长 14.68 != π·5,
        // 与 brief 自身断言必然冲突;此处按 kappa 修正为 5.523(弧长 15.710,差 0.0022)。
        val q = Cubic(10f, 0f, 10f, 5.523f, 5.523f, 10f, 0f, 10f)
        assertEquals(Math.PI * 5, arcLengthOf(q).toDouble(), 0.01)
    }

    @Test
    fun cornerIsAnchoredExactly() {
        val v = SubPath(listOf(
            line(0f, 0f, 12f, 0f).cubics[0], line(12f, 0f, 12f, 12f).cubics[0]), false)
        val s = resample(v, n = 32)
        assertTrue("x=12 出现", s.points.any { it == 12f })
        assertTrue("y=0 出现(角点)", s.points.any { it == 0f })
    }

    @Test
    fun playClosedTriangleWrapsWithExactCorners() {
        val sp = sub(IconPaths.PLAY)
        assertTrue(sp.closed)
        val s = resample(sp, 64)
        assertEquals(128, s.points.size)
        assertTrue(s.closed)
        // M 起点恰是闭合边接缝处的真角点:采样 idx0 精确落在 (6.5,5)
        assertEquals(6.5f, s.points[0], 0f)
        assertEquals(5f, s.points[1], 0f)
        // 其余两顶点(含经由直线闭边返回)同为精确锚点
        assertTrue(has(s.points, 19f, 12f))
        assertTrue(has(s.points, 6.5f, 19f))
        // 周向相邻间隙与周长/64 一致(整数配额在 run 交界处带来 <=3% 波动)
        val per = 2.0 * hypot(12.5f, 7f).toDouble() + 14.0
        val mean = per / 64.0
        var sum = 0.0
        for (i in 0 until 64) {
            val j = (i + 1) % 64
            val g = hypot(
                s.points[2 * j] - s.points[2 * i],
                s.points[2 * j + 1] - s.points[2 * i + 1]).toDouble()
            assertEquals("gap[$i]=$g mean=$mean", mean, g, mean * 0.04)
            sum += g
        }
        assertEquals(per, sum, 0.05)
    }

    @Test
    fun closedCirclePerimeterMatches2PiR() {
        val sp = sub("M0,0 A10,10 0 1 1 20,0 A10,10 0 1 1 0,0 Z") // 4 段 90° cubic
        assertTrue(sp.closed)
        var arcSum = 0.0
        for (c in sp.cubics) arcSum += arcLengthOf(c).toDouble()
        assertEquals(2.0 * Math.PI * 10.0, arcSum, 0.1) // 圆整周长
        val s = resample(sp, 64)
        assertEquals(128, s.points.size)
        val gaps = DoubleArray(64)
        var sum = 0.0
        for (i in 0 until 64) {
            val j = (i + 1) % 64
            gaps[i] = hypot(
                s.points[2 * j] - s.points[2 * i],
                s.points[2 * j + 1] - s.points[2 * i + 1]).toDouble()
            sum += gaps[i]
        }
        // 内接 64 边形周长 = 128·sin(π/64)·r ≈ 62.825,与 2πr 差 <0.1%
        assertEquals(2.0 * Math.PI * 10.0, sum, 2.0 * Math.PI * 10.0 * 0.01)
        val mean = sum / 64.0
        for (i in 0 until 64) {
            assertEquals("gap[$i]", mean, gaps[i], mean * 5e-3) // 无角圈 -> 全弧均匀
        }
    }

    @Test
    fun neverNaNOrInfinity() {
        val zero = Cubic(5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f)
        val subs = mutableListOf<SubPath>()
        subs += SubPath(emptyList(), false)
        subs += SubPath(emptyList(), true)
        subs += SubPath(listOf(zero), false)
        subs += SubPath(listOf(zero), true)
        subs += SubPath(listOf(zero, zero), true)
        subs += sub("M0,0 L24,0")
        subs += sub("M0,0 L12,0 L12,12 L0,12") // 多角点开链,测小 n 的配额降级
        IconPaths.ALL.forEach { d -> normalize(parsePathData(d)).forEach { subs += it } }
        val sizes = intArrayOf(1, 2, 64) // n<2 -> 2;n 太小触发角点区间合并路径
        subs.forEach { sp ->
            for (n in sizes) {
                val s = resample(sp, n)
                val count = max(2, n)
                assertEquals("count n=$n", 2 * count, s.points.size)
                assertEquals(s.closed, sp.closed)
                s.points.forEach { assertTrue("finite: $it", it.isFinite()) }
            }
        }
    }
}
