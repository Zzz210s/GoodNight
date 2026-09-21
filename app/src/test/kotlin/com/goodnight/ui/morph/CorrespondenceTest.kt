package com.goodnight.ui.morph

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对应行为:闭合轮廓圆形偏移寻优(用不等边三角作证——正方形等高对称形可被
 * similarity 完全吸收,构不成信号)、开路径遍历方向(倒序)检测、surjective
 * 分裂合并(play<->pause 双向)、匹配代价对最近质心的偏好。
 */
class CorrespondenceTest {

    private fun sample(d: String): List<SampledPath> =
        normalize(parsePathData(d)).map { resample(it) }

    /** 与实现同约定的测试侧滚动:out[i] = pts[(i+k) mod N]。 */
    private fun roll(pts: FloatArray, k: Int): FloatArray {
        val n = pts.size / 2
        val out = FloatArray(pts.size)
        for (i in 0 until n) {
            out[2 * i] = pts[2 * ((i + k) % n)]
            out[2 * i + 1] = pts[2 * ((i + k) % n) + 1]
        }
        return out
    }

    /** 点云两两最大距离(全跨度)。 */
    private fun extent(pts: FloatArray): Float {
        var m = 0f
        for (i in pts.indices step 2) for (j in pts.indices step 2) {
            m = maxOf(m, hypot(pts[j] - pts[i], pts[j + 1] - pts[i + 1]))
        }
        return m
    }

    @Test fun circularOffsetAlignsRebasedTriangle() {
        val a = sample("M6,4 L20,10 L5,19 Z")[0].points // 不等边闭合三角
        val b = sample("M20,10 L5,19 L6,4 Z")[0].points // 同形,M 起点换顶点
        val before = residual(a, b)
        assertTrue("无偏移残差 $before 不足构成信号", before > 0.05f)
        val k = bestCircularOffset(a, b)
        val after = residual(a, roll(b, k))
        assertTrue("偏移 k=$k 后残差 $after 未对齐", after < 0.02f)
    }

    @Test fun openPairDetectsReversal() {
        val a = sample("M2,12 L22,12")[0] // 左 -> 右
        val b = sample("M22,12 L2,12")[0] // 右 -> 左(同一线段,反向绘制)
        val p = buildPlan(a, b)
        // to 侧应被倒序对齐:首点回到 x≈2(不倒序则是 x≈22)
        assertEquals(2f, p.toPts[0][0], 0.5f)
        val mid = interpolate(p, 0.5f)[0]
        assertTrue(mid.all { !it.isNaN() && !it.isInfinite() })
        assertTrue("mid 跨度 ${extent(mid)} 退化", extent(mid) > 5f)
    }

    @Test fun surjectiveSplitPlayToPause() {
        val p = buildPlanIcon(sample(IconPaths.PLAY), sample(IconPaths.PAUSE))
        assertEquals(2, p.n)
        assertEquals(9f, p.toPts[0][0], 0.5f)
        assertEquals(15f, p.toPts[1][0], 0.5f)
        assertTrue("from 侧应为复制出的同一三角", p.fromPts[0].contentEquals(p.fromPts[1]))
    }

    @Test fun surjectiveMergePauseToPlay() {
        val p = buildPlanIcon(sample(IconPaths.PAUSE), sample(IconPaths.PLAY))
        assertEquals(2, p.n)
        assertEquals(9f, p.fromPts[0][0], 0.5f)
        assertEquals(15f, p.fromPts[1][0], 0.5f)
        assertTrue("to 侧应为复制出的同一三角", p.toPts[0].contentEquals(p.toPts[1]))
    }

    @Test fun matchingPrefersNearestCentroid() {
        // b 侧故意交换顺序:排列须配 9↔9、15↔15,而非按原顺序错配
        val a = sample("M9,5 L9,19 M15,5 L15,19")
        val b = sample("M15,5 L15,19 M9,5 L9,19")
        val p = buildPlanIcon(a, b)
        assertEquals(2, p.n)
        assertEquals(9f, p.fromPts[0][0], 0.5f)
        assertEquals(9f, p.toPts[0][0], 0.5f)
        assertEquals(15f, p.fromPts[1][0], 0.5f)
        assertEquals(15f, p.toPts[1][0], 0.5f)
    }
}
