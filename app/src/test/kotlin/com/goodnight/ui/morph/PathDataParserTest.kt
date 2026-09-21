package com.goodnight.ui.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PathDataParser 行为测试:绝对/相对解析、H/V 转 L、隐式重复、子路径拆分、
 * 打包弧标志、科学计数法、IconPaths 全部常量可解析、畸形输入抛异常。
 */
class PathDataParserTest {

    @Test
    fun absoluteAndRelativeCommands() {
        val p = parsePathData("M10,10 L20,10 l0,10 h5 v5")
        assertEquals(1, p.size)
        // 期望链:M(10,10) L(20,10) L(20,20) L(25,20) L(25,25)
        assertEquals(5, p[0].size)
        // M 位于链首(下标 0);其 x 为 10
        assertEquals(10f, p[0][0].args[0], 0f)
        assertEquals(listOf('M', 'L', 'L', 'L', 'L'), p[0].map { it.verb })
        assertEquals(20f, p[0][1].args[0], 0f) // 首条 L(20,10) 的 x
        assertEquals(20f, p[0][3].args[1], 0f) // 第 4 条 L(25,20) 的 y
        assertEquals(25f, p[0][4].args[0], 0f) // v5 -> L(25,25),x 不变
    }

    @Test
    fun shorthandSReflectsPreviousControl() {
        // C 后 S:首控制点 = 前段 c2 关于当前点的反射
        val p = parsePathData("M0,0 C10,10 20,10 30,0 S50,-10 60,0")
        val sub = normalize(p)[0]
        assertEquals(2, sub.cubics.size)
        assertEquals(40f, sub.cubics[1].c1x, 0.01f) // 2*30-20 = 40
        assertEquals(-10f, sub.cubics[1].c1y, 0.01f) // 2*0-10 = -10
    }

    @Test
    fun arcNormalizedToCubics() {
        val p = parsePathData("M0,0 A10,10 0 0 1 20,0")
        val sub = normalize(p)[0]
        assertTrue(sub.cubics.size >= 2) // 180 度弧至少两片
        val last = sub.cubics.last()
        assertEquals(20f, last.x1, 0.01f)
        assertEquals(0f, last.y1, 0.01f)
    }

    @Test
    fun subpathsSplitOnNewMoveto() {
        val p = parsePathData(IconPaths.PAUSE)
        assertEquals(2, p.size)
        assertEquals(9f, p[0][0].args[0], 1e-4f)
        assertEquals(15f, p[1][0].args[0], 1e-4f)
    }

    @Test
    fun relativeMovetoThenImplicitLineto() {
        // m 相对旧当前点;其后的裸坐标对 = 相对隐式 lineto
        val p = parsePathData("m10,10 5,0 5,5")
        assertEquals(listOf('M', 'L', 'L'), p[0].map { it.verb })
        assertEquals(10f, p[0][0].args[0], 1e-4f)
        assertEquals(15f, p[0][1].args[0], 1e-4f)
        assertEquals(10f, p[0][1].args[1], 1e-4f)
        assertEquals(20f, p[0][2].args[0], 1e-4f)
        assertEquals(15f, p[0][2].args[1], 1e-4f)
    }

    @Test
    fun scientificNotationAndNegativeSeparator() {
        val p = parsePathData("M1e1,2E1 L1e-1,5e1 L10-5")
        assertEquals(10f, p[0][0].args[0], 1e-3f)
        assertEquals(20f, p[0][0].args[1], 1e-3f)
        assertEquals(0.1f, p[0][1].args[0], 1e-4f)
        assertEquals(50f, p[0][1].args[1], 1e-3f)
        assertEquals(-5f, p[0][2].args[1], 1e-4f) // 负号充当分隔符
    }

    @Test
    fun packedArcFlagsWithoutSeparators() {
        // 两个标志位可紧贴:large=0,sweep=1 与终点 x=10 连写为 "0110,0"
        val packed = parsePathData("M0,0 A10,10 0 0110,0")[0][1]
        val spaced = parsePathData("M0,0 A10,10 0 0 1 10,0")[0][1]
        assertEquals('A', packed.verb)
        for (k in packed.args.indices) {
            assertEquals(spaced.args[k], packed.args[k], 1e-6f)
        }
    }

    @Test
    fun allIconsParseToAbsoluteChains() {
        IconPaths.ALL.forEach { d ->
            val p = parsePathData(d)
            assertTrue(p.isNotEmpty())
            p.forEach { chain ->
                assertEquals("链首应为 M: $d", 'M', chain.first().verb)
                chain.forEach { c ->
                    assertTrue("应无 H/V/相对残留: $d", c.verb in "MLCSQTAZ")
                }
            }
        }
    }

    @Test
    fun repeatIconHasFourSubpaths() {
        assertEquals(4, parsePathData(IconPaths.REPEAT).size)
    }

    @Test
    fun malformedThrows() {
        val bad = listOf(
            "M0,0 Lx",             // 非法数字
            "L5,5",                // 缺首 M
            "M0,0 L1",             // 坐标不完整
            "M0,0 C1,1 2,2",       // 三次曲线参数不足
            "M0,0 A10,10 0 0",     // 弧参数不足
            "M0,0 A10,10 0 0 1",   // 弧缺终点
            "M0,0 L1e",            // 非法指数
            "M0,0 x",              // 未知命令
            "M 0 0 1e-",           // 数字被截断
        )
        for (s in bad) {
            assertThrows(IllegalArgumentException::class.java) { parsePathData(s) }
        }
    }
}
