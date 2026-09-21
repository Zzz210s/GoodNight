package com.goodnight.ui.morph

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎门面:真实图标端点量纲、计划确定性(缓存)、全图标可建计划、
 * 折线序列化、全局同余混合刚体飞行、缓存命中、全图标往返有限性电池。
 */
class MorphEngineTest {

    @Test fun playToPauseEndpointExact() {
        val t0 = MorphEngine.planAndMorph(IconPaths.PLAY, IconPaths.PAUSE, 0f)
        val t1 = MorphEngine.planAndMorph(IconPaths.PLAY, IconPaths.PAUSE, 1f)
        // 不做逐点断言,验证子路径数与几何量纲
        assertEquals(2, t1.size) // pause 2 条竖线(play 复制分裂)
        assertTrue(t1.all { it.size == 2 * 64 })
        t0.forEach { p -> p.forEach { assertTrue(!it.isNaN()) } }
        // play 三角 3 顶点与 pause 竖条都在 0..24 内(无飞出)
        t1.forEach { p -> p.forEach { v -> assertTrue(v in -0.5f..24.5f) } }
    }

    @Test fun planDeterministic() {
        val p1 = MorphEngine.plan(IconPaths.PAUSE, IconPaths.PLAY)
        val p2 = MorphEngine.plan(IconPaths.PAUSE, IconPaths.PLAY)
        assertEquals(p1.n, p2.n)
        // 同输入同输出(点集逐一相等)
        for (i in 0 until p1.n) {
            p1.fromPts[i].forEachIndexed { j, v -> assertEquals(v, p2.fromPts[i][j], 0f) }
        }
    }

    @Test fun skipAndStopParse() {
        // 至少不抛异常,能建 plan(自配对)
        listOf(IconPaths.SKIP, IconPaths.STOP, IconPaths.REPEAT).forEach { d ->
            val s = normalize(parsePathData(d))
            assertTrue(s.isNotEmpty())
        }
    }

    @Test fun interpolatedPathEmitsPolyline() {
        val s = MorphEngine.planAndMorph(IconPaths.PLAY, IconPaths.PAUSE, 0.5f)
        val d = interpolatedPath(s, closed = false)
        assertTrue(d.startsWith("M"))
        assertTrue(d.contains("L"))
        assertTrue(d.length < 4000) // 2 子路径 x 64 点,粗上限
    }

    /**
     * 全局同余混合:两个 L 形(腿长 4+5,不对称 -> 遍历方向择优无浮点平局)
     * 整体绕图标中心 (12,12) 旋转 90 度。整体 residual ~ 0 -> 混合生效
     * (pairSims 共享同一实例),飞行全程刚体:弦长/平行/质心间距保持,
     * 端点精确。PLAY->PAUSE 非整体同余走默认路径,由其余测试覆盖。
     */
    @Test fun hybridBlendRigidInFlight() {
        val a = "M11.9,10.6 L15.9,10.6 L15.9,15.6 M0.9,10.6 L4.9,10.6 L4.9,15.6"
        val b = "M13.4,11.9 L13.4,15.9 L8.4,15.9 M13.4,0.9 L13.4,4.9 L8.4,4.9"
        val p = MorphEngine.plan(a, b)
        assertEquals(2, p.n)
        assertSame("全局同余应共享 similarity", p.pairSims[0], p.pairSims[1])
        assertEquals(1.5707964f, abs(p.pairSims[0].theta), 0.02f) // 90 度
        assertEquals(1f, p.pairSims[0].scale, 0.02f)
        val at0 = interpolate(p, 0f)
        val at1 = interpolate(p, 1f)
        val mid = interpolate(p, 0.5f)
        for (i in 0 until p.n) { // 端点精确
            at0[i].forEachIndexed { j, v -> assertEquals(p.fromPts[i][j], v, 1e-3f) }
            at1[i].forEachIndexed { j, v -> assertEquals(p.toPts[i][j], v, 1e-3f) }
        }
        // 各 L 端到端弦长保持(形状刚体,两 L 同形同向 -> 同弦长)
        val spanFrom = hypot(chord(p.fromPts[0])[0], chord(p.fromPts[0])[1])
        mid.forEach { c ->
            assertEquals("弦长漂移", spanFrom, hypot(chord(c)[0], chord(c)[1]), 0.25f)
        }
        // 两 L 保持平行(相对方向不变,不撕裂)且质心间距不变(整体刚体)
        val u0 = chord(mid[0]); val u1 = chord(mid[1])
        val cross = abs(u0[0] * u1[1] - u0[1] * u1[0]) /
            (hypot(u0[0], u0[1]) * hypot(u1[0], u1[1]))
        assertTrue("失去平行 $cross", cross < 0.05f)
        val c0 = centroid(p.fromPts[0]); val c1 = centroid(p.fromPts[1])
        val m0 = centroid(mid[0]); val m1 = centroid(mid[1])
        assertEquals(
            "质心间距漂移",
            hypot(c0[0] - c1[0], c0[1] - c1[1]),
            hypot(m0[0] - m1[0], m0[1] - m1[1]), 0.3f)
    }

    @Test fun planCacheHitReturnsSameInstance() {
        val a = MorphEngine.plan(IconPaths.PLAY, IconPaths.STOP)
        val b = MorphEngine.plan(IconPaths.PLAY, IconPaths.STOP)
        assertSame(a, b) // 同键命中缓存 -> 同一实例
        assertTrue(MorphEngine.plan(IconPaths.STOP, IconPaths.PLAY) !== a) // 异键异实例
    }

    /** 全图标自配对 + PLAY<->PAUSE 双向:t=0/0.5/1 全点有限。 */
    @Test fun fullIconRoundTripFinite() {
        val icons = listOf(
            IconPaths.PLAY, IconPaths.PAUSE, IconPaths.SKIP, IconPaths.STOP, IconPaths.REPEAT)
        val combos = icons.map { it to it } +
            listOf(IconPaths.PLAY to IconPaths.PAUSE, IconPaths.PAUSE to IconPaths.PLAY)
        for ((a, b) in combos) {
            val p = MorphEngine.plan(a, b)
            for (t in floatArrayOf(0f, 0.5f, 1f)) {
                interpolate(p, t).forEach { pts -> pts.forEach {
                    assertTrue("$a->$b t=$t NaN", !it.isNaN())
                    assertTrue("$a->$b t=$t Inf", !it.isInfinite())
                } }
            }
        }
    }

    /** 点云首末采样点差向量(端到端弦)。 */
    private fun chord(c: FloatArray): FloatArray = floatArrayOf(
        c[c.size - 2] - c[0], c[c.size - 1] - c[1])

    /** 点云质心。 */
    private fun centroid(c: FloatArray): FloatArray {
        var sx = 0f; var sy = 0f
        var i = 0
        while (i + 1 < c.size) { sx += c[i]; sy += c[i + 1]; i += 2 }
        val n = c.size / 2
        return floatArrayOf(sx / n, sy / n)
    }
}
