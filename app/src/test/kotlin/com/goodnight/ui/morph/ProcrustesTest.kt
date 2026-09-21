package com.goodnight.ui.morph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Procrustes 闭式对齐行为:旋转/缩放/平移的精确恢复、最小旋转 tie-break
 * (直线反向只允许 180 类歧义)、非相似形残差语义、退化输入(空/单点/零长/
 * 塌缩)退化为纯平移或恒等且绝不产生 NaN/Infinity。
 */
class ProcrustesTest {

    private fun rotate(a: FloatArray, deg: Float, cx: Float = 12f, cy: Float = 12f): FloatArray {
        val t = deg * PI.toFloat() / 180f
        val (co, si) = kotlin.math.cos(t) to kotlin.math.sin(t)
        val out = FloatArray(a.size)
        for (i in a.indices step 2) {
            val dx = a[i] - cx; val dy = a[i + 1] - cy
            out[i] = cx + dx * co - dy * si; out[i + 1] = cy + dx * si + dy * co
        }
        return out
    }

    /** p -> s·R(deg)·p + (tx,ty)(Double 生成,避免 Float 累积误差)。 */
    private fun scaled(pts: FloatArray, deg: Float, s: Float, tx: Float, ty: Float): FloatArray {
        val t = deg * PI.toFloat() / 180f
        val co = cos(t.toDouble()); val si = sin(t.toDouble())
        val ss = s.toDouble()
        val out = FloatArray(pts.size)
        for (i in pts.indices step 2) {
            val x = pts[i].toDouble(); val y = pts[i + 1].toDouble()
            out[i] = (ss * (co * x - si * y) + tx.toDouble()).toFloat()
            out[i + 1] = (ss * (si * x + co * y) + ty.toDouble()).toFloat()
        }
        return out
    }

    private fun finite(t: SimTransform): Boolean =
        t.theta.isFinite() && t.scale.isFinite() && t.tx.isFinite() && t.ty.isFinite()

    @Test fun rotationDetectedClosedForm() {
        // 上箭头 -> 右箭头(90°),θ 应自动涌现为 ~90°(或 -270° 等价),残差 ~0
        val up = floatArrayOf(12f, 3f, 12f, 9f, 21f, 9f, 12f, 21f) // 简化 V 形示意
        val right = rotate(up, 90f)
        val t = similarity(up, right)
        val norm = (t.theta * 180f / PI.toFloat() + 360f) % 360f
        assertTrue("theta=${norm}", norm > 89f && norm < 91f || norm > 269f && norm < 271f)
        assertEquals(1f, t.scale, 0.001f)
        assertTrue(residual(up, apply(t, up)) < 0.01f)
    }

    @Test fun scaleAndTranslationRecovered() {
        val a = floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val b = floatArrayOf(2f, 2f, 10f, 2f, 10f, 10f, 2f, 10f) // 2x 放大 + (2,2)
        val t = similarity(a, b)
        assertEquals(2f, t.scale, 0.001f)
        assertEquals(2f, t.tx, 0.01f); assertEquals(2f, t.ty, 0.01f)
        assertTrue(residual(a, apply(t, a)) < 0.01f)
    }

    @Test fun minimalRotationTieBreak() {
        // 直线 180° 对称:固定索引对应下映射即 180°;断言只排除 135° 类歧义
        val a = floatArrayOf(0f, 0f, 24f, 0f)
        val b = floatArrayOf(24f, 0f, 0f, 0f) // 同线反向
        val t = similarity(a, b)
        val deg = Math.toDegrees(t.theta.toDouble())
        assertTrue("|deg|=${deg}", Math.abs(deg) < 5.0 || Math.abs(Math.abs(deg) - 180.0) < 5.0)
        // 具体方向由实现决定;测试只需不选 135° 类歧义值
    }

    @Test fun translationOnlyIsRecovered() {
        val a = floatArrayOf(1f, 2f, 5f, 2f, 5f, 6f, 1f, 6f)
        val b = FloatArray(a.size) { i -> a[i] + if (i % 2 == 0) 7f else -3f }
        val t = similarity(a, b)
        assertEquals(0f, t.theta, 1e-5f)
        assertEquals(1f, t.scale, 1e-4f)
        assertEquals(7f, t.tx, 1e-3f); assertEquals(-3f, t.ty, 1e-3f)
        assertTrue(residual(a, b) < 1e-4f)
    }

    @Test fun identityIsRecovered() {
        val a = floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val t = similarity(a, a.copyOf())
        assertEquals(0f, t.theta, 1e-6f)
        assertEquals(1f, t.scale, 1e-5f)
        assertEquals(0f, t.tx, 1e-5f); assertEquals(0f, t.ty, 1e-5f)
    }

    @Test fun halfTurnRecovered() {
        // 非对称四边形:180° 旋转应被唯一恢复(无反射参与)
        val a = floatArrayOf(2f, 2f, 8f, 3f, 9f, 9f, 3f, 8f)
        val b = rotate(a, 180f)
        val t = similarity(a, b)
        val deg = Math.toDegrees(t.theta.toDouble())
        assertTrue("deg=$deg", Math.abs(Math.abs(deg) - 180.0) < 0.5)
        assertEquals(1f, t.scale, 0.001f)
        assertTrue(residual(a, apply(t, a)) < 0.01f)
    }

    @Test fun scaleAndRotationCombined() {
        val a = floatArrayOf(0f, 0f, 10f, 0f, 14f, 8f, 6f, 14f, 0f, 8f) // 非对称五边形
        val b = scaled(a, 37f, 1.6f, 3f, -5f)
        val t = similarity(a, b)
        val norm = (Math.toDegrees(t.theta.toDouble()) + 360.0) % 360.0
        assertTrue("norm=$norm", Math.abs(norm - 37.0) < 0.5)
        assertEquals(1.6f, t.scale, 0.002f)
        assertEquals(3f, t.tx, 0.05f); assertEquals(-5f, t.ty, 0.05f)
        assertTrue(residual(a, b) < 0.01f)
    }

    @Test fun nonSimilarPairHasMeaningfulResidual() {
        // 正方形 vs 直线(宽高比差异不可由相似变换消解):归一化 RMS 应显著 > 0
        val sq = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val line = floatArrayOf(0f, 5f, 20f / 3f, 5f, 40f / 3f, 5f, 20f, 5f)
        val r = residual(sq, line)
        assertTrue("residual=$r 应显著大于 0", r > 0.1f)
        assertTrue("residual=$r 应为归一化有界量", r < 1.5f)
        // 同形(可对齐)残差应远小于不可对齐对,证明残差有判别力
        assertTrue(residual(sq, scaled(sq, 12f, 0.8f, 4f, 4f)) < r)
    }

    @Test fun singlePointIsPureTranslation() {
        val t = similarity(floatArrayOf(3f, 4f), floatArrayOf(9f, -1f))
        assertEquals(0f, t.theta, 0f)
        assertEquals(1f, t.scale, 0f)
        assertEquals(6f, t.tx, 0f); assertEquals(-5f, t.ty, 0f)
        assertTrue(residual(floatArrayOf(3f, 4f), floatArrayOf(9f, -1f)) < 1e-6f)
    }

    @Test fun collapsedCloudDegradesToPureTranslation() {
        val a = floatArrayOf(7f, 7f, 7f, 7f, 7f, 7f) // 三同点:零方差源
        val b = floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f)
        val t = similarity(a, b)
        assertEquals(0f, t.theta, 0f)
        assertEquals(1f, t.scale, 0f)
        // centroid_b=(8/3,4/3) - centroid_a=(7,7)
        assertEquals(-13f / 3f, t.tx, 1e-4f)
        assertEquals(-17f / 3f, t.ty, 1e-4f)
        val moved = apply(t, a)
        assertTrue(moved.all { it.isFinite() })
    }

    @Test fun neverNaNOrInfinity() {
        val cases = listOf(
            floatArrayOf(),
            floatArrayOf(5f, 5f), // 单点
            floatArrayOf(3f, 3f, 3f, 3f), // 零长线段
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 24f, 0f),
            floatArrayOf(24f, 0f, 0f, 0f), // 反向线
            floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f), // 全共线
            floatArrayOf(12f, 3f, 12f, 9f, 21f, 9f, 12f, 21f),
            floatArrayOf(1f, 2f, 3f), // 奇长:取 min 对齐后仍有限
            floatArrayOf(12f, 12f, 13f, 12f, 13f, 13f, 12f, 13f),
        )
        for (x in cases) for (y in cases) {
            val t = similarity(x, y)
            assertTrue("s ${x.size}/${y.size}: $t", finite(t))
            // apply 契约要求 2N 偶长点云;奇长仅走 similarity/residual(内部取整)
            if (x.size % 2 == 0) {
                val ap = apply(t, x)
                assertEquals(x.size, ap.size)
                ap.forEach { assertTrue("apply", it.isFinite()) }
            }
            val r = residual(x, y)
            assertTrue("residual=$r", r.isFinite())
        }
    }
}
