package com.goodnight.ui.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normalizer 行为测试:直线/二次曲线的 cubic 化、S/T 反射、弧切分与退化、
 * 数值禁 NaN/Infinity、IconPaths 全部常量的闭合与连续性。
 */
class NormalizerTest {

    private fun sub(d: String): SubPath = normalize(parsePathData(d)).single()

    @Test
    fun straightLinesBecomeCubicsAtThirdPoints() {
        val c = sub("M0,0 L30,0").cubics.single()
        assertEquals(10f, c.c1x, 1e-4f)
        assertEquals(0f, c.c1y, 1e-4f)
        assertEquals(20f, c.c2x, 1e-4f)
        assertEquals(0f, c.c2y, 1e-4f)
        assertEquals(30f, c.x1, 1e-4f)
    }

    @Test
    fun quadraticIsLiftedToCubic() {
        val c = sub("M0,0 Q10,20 20,0").cubics.single()
        assertEquals(6.6667f, c.c1x, 1e-3f)
        assertEquals(13.3333f, c.c1y, 1e-3f)
        assertEquals(13.3333f, c.c2x, 1e-3f)
        assertEquals(13.3333f, c.c2y, 1e-3f)
        assertEquals(20f, c.x1, 1e-4f)
    }

    @Test
    fun smoothCubicReflectsPreviousControl() {
        val s = sub("M0,0 C10,10 20,10 30,0 S50,-10 60,0")
        assertEquals(2, s.cubics.size)
        // 反射:2*(30,0) - (20,10) = (40,-10)
        assertEquals(40f, s.cubics[1].c1x, 1e-3f)
        assertEquals(-10f, s.cubics[1].c1y, 1e-3f)
        assertEquals(60f, s.cubics[1].x1, 1e-3f)
    }

    @Test
    fun smoothCubicWithoutPreviousCurveUsesCurrentPoint() {
        val c = sub("M0,0 S10,0 20,0").cubics.single()
        assertEquals(0f, c.c1x, 1e-4f)
        assertEquals(0f, c.c1y, 1e-4f)
    }

    @Test
    fun afterCloseNewDrawingStartsAtSubpathStart() {
        // "M0,0 L10,0 Z l0,10":Z 后相对 l 相对子路径起点(0,0)转绝对 -> L(0,10)。
        // 归一化后末段 cubic 须从 (0,0) 起笔,而非修复前从 (10,0) 起笔的错位段。
        val s = sub("M0,0 L10,0 Z l0,10")
        assertTrue(s.closed)
        assertEquals(2, s.cubics.size)
        val last = s.cubics.last()
        assertEquals(0f, last.x0, 1e-4f); assertEquals(0f, last.y0, 1e-4f)
        assertEquals(0f, last.x1, 1e-4f); assertEquals(10f, last.y1, 1e-4f)
    }

    @Test
    fun smoothAfterCloseDoesNotReflectPreCloseControl() {
        // Z 复位反射状态:S 不反射 Z 前 C 的 c2(20,0);退回控制点 = 当前点 (0,0)。
        val s = sub("M0,0 C10,0 20,0 20,10 Z S30,20 40,10")
        val after = s.cubics.last()
        assertEquals(0f, after.x0, 1e-4f); assertEquals(0f, after.y0, 1e-4f)
        assertEquals(0f, after.c1x, 1e-4f); assertEquals(0f, after.c1y, 1e-4f)
        assertEquals(40f, after.x1, 1e-4f); assertEquals(10f, after.y1, 1e-4f)
    }

    @Test
    fun smoothQuadraticReflectsPreviousControl() {
        val s = sub("M0,0 Q10,20 20,0 T40,0")
        assertEquals(2, s.cubics.size)
        // T 控制点 = 2*(20,0) - (10,20) = (30,-20),再按 2/3 提升
        assertEquals(26.6667f, s.cubics[1].c1x, 1e-3f)
        assertEquals(-13.3333f, s.cubics[1].c1y, 1e-3f)
        assertEquals(40f, s.cubics[1].x1, 1e-3f)
    }

    @Test
    fun arcNormalizedToCubics() {
        val s = sub("M0,0 A10,10 0 0 1 20,0") // 180 度
        assertTrue(s.cubics.size >= 2)
        assertEquals(20f, s.cubics.last().x1, 1e-3f)
        assertEquals(0f, s.cubics.last().y1, 1e-3f)
    }

    @Test
    fun fullCircleUsesFourSlices() {
        val s = sub("M0,0 A10,10 0 1 1 20,0 A10,10 0 1 1 0,0 Z")
        assertTrue(s.closed)
        assertEquals(4, s.cubics.size)
        assertEquals(0f, s.cubics.first().x0, 1e-3f)
        assertEquals(0f, s.cubics.last().x1, 1e-3f)
    }

    @Test
    fun sweepZeroRunsTheOtherWay() {
        // sweep=0 与 sweep=1 的 180 度半弧终点相同,但中点镜像
        val up = sub("M0,0 A10,10 0 0 1 20,0").cubics[0]
        val down = sub("M0,0 A10,10 0 0 0 20,0").cubics[0]
        val midY1 = eval(up)
        val midY2 = eval(down)
        assertTrue("应分居 x 轴两侧", (midY1 > 0f) != (midY2 > 0f))
    }

    /** 求三次曲线 t=0.5 处的 y(取中点验证弧朝向)。 */
    private fun eval(c: Cubic): Float {
        val t = 0.5f
        val u = 1f - t
        return u * u * u * c.y0 + 3f * u * u * t * c.c1y +
            3f * u * t * t * c.c2y + t * t * t * c.y1
    }

    @Test
    fun degenerateArcHandling() {
        // rx=0 -> 直线
        val line = sub("M0,0 A0,5 0 0 1 30,0").cubics.single()
        assertEquals(10f, line.c1x, 1e-3f)
        // 端点重合 -> 省略
        assertTrue(sub("M5,5 A5,5 0 0 1 5,5").cubics.isEmpty())
    }

    @Test
    fun extremeArcsStayFinite() {
        // 半径不足(自动放大)、旋转椭圆、反向 sweep、接近圆的整圆
        val cases = listOf(
            "M0,0 A3,3 0 0 1 20,0",
            "M10,10 A15,10 45 0 1 30,30",
            "M5,5 A5,5 0 0 0 15,15",
            "M12,9 a3,3 0 1 0 0.001,6 a3,3 0 1 0 -0.001,-6",
        )
        cases.forEach { d ->
            val s = sub(d)
            assertTrue(s.cubics.isNotEmpty())
            s.cubics.forEach { c ->
                listOf(c.x0, c.y0, c.c1x, c.c1y, c.c2x, c.c2y, c.x1, c.y1)
                    .forEach { assertTrue("finite 约束: $d", it.isFinite()) }
            }
        }
    }

    @Test
    fun allIconsClosedFlagsAndContinuity() {
        IconPaths.ALL.forEach { d ->
            val subs = normalize(parsePathData(d))
            subs.forEach { sp ->
                sp.cubics.forEach { c ->
                    assertTrue("首尾端点须有限: $d", c.x0.isFinite() && c.x1.isFinite())
                }
                for (k in 1 until sp.cubics.size) {
                    val prev = sp.cubics[k - 1]
                    val cur = sp.cubics[k]
                    assertEquals("子路径断点: $d", prev.x1, cur.x0, 5e-3f)
                    assertEquals("子路径断点: $d", prev.y1, cur.y0, 5e-3f)
                }
            }
        }
    }

    @Test
    fun pauseIsTwoOpenBars() {
        val subs = normalize(parsePathData(IconPaths.PAUSE))
        assertEquals(2, subs.size)
        subs.forEach { assertEquals(false, it.closed) }
        assertEquals(19f, subs[0].cubics.single().y1, 1e-3f)
    }

    @Test
    fun playIsClosedTriangle() {
        val s = normalize(parsePathData(IconPaths.PLAY)).single()
        assertTrue(s.closed)
        assertEquals(2, s.cubics.size)
        assertEquals(6.5f, s.cubics[0].x0, 1e-3f)
        assertEquals(6.5f, s.cubics[1].x1, 1e-3f) // 终点回起点;Z 边由 closed 标记
    }

    @Test
    fun stopRoundedSquareReturnsToStart() {
        val s = sub(IconPaths.STOP)
        assertTrue(s.closed)
        assertEquals(8, s.cubics.size) // 4 直线 + 4 个 90 度弧角
        assertEquals(5f, s.cubics.last().x1, 1e-3f)
        assertEquals(3f, s.cubics.last().y1, 1e-3f)
    }

    @Test
    fun skipSecondSubpathIsClosed() {
        val subs = normalize(parsePathData(IconPaths.SKIP))
        assertEquals(2, subs.size)
        assertEquals(false, subs[0].closed)
        assertEquals(true, subs[1].closed)
    }
}
