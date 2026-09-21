package com.goodnight.ui.morph

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MorphIcon 状态机的引擎级回归网(评审 R1 加固)。
 *
 * MorphIcon 是 Compose UI,v0.5 无 instrumentation 测试面;本文件只钉住修复
 * 分支实际消费的引擎不变量:打断后从上一目标典型形状重建的计划,t=0 逐点
 * 等于 from 侧点云 —— 即"清除滞留 flight 后直绘 current(典型形)"与
 * "重建飞行的起点"是同一字形,快照跳变仅落在绑定约定的弹簧重启语义内,
 * 不画错图标。不测 Compose LaunchedEffect 效应本身(诚实边界)。
 */
class MorphIconLogicTest {

    /** 打断重规划路径双向可构造:PLAY->PAUSE 飞行中折返即走 plan(PAUSE, PLAY)。 */
    @Test fun interruptRebuildPlansConstructibleBothWays() {
        val forward = MorphEngine.plan(IconPaths.PLAY, IconPaths.PAUSE)
        val backward = MorphEngine.plan(IconPaths.PAUSE, IconPaths.PLAY)
        for (p in listOf(forward, backward)) {
            assertTrue("计划应含至少一对子路径", p.n > 0)
            assertEquals("from/to 点云对数应一致", p.fromPts.size, p.toPts.size)
            (p.fromPts + p.toPts).forEach { c ->
                c.forEach { assertTrue(!it.isNaN()); assertTrue(!it.isInfinite()) }
            }
        }
    }

    /** 重建飞行 t=0 逐点等于 from 侧典型点云(快照回典型形不变量,修复所依赖)。 */
    @Test fun rebuiltFlightStartsAtFromCloud() {
        val plans = listOf(
            MorphEngine.plan(IconPaths.PLAY, IconPaths.PAUSE),
            MorphEngine.plan(IconPaths.PAUSE, IconPaths.PLAY),
        )
        for (p in plans) {
            val at0 = interpolate(p, 0f)
            assertEquals(p.n, at0.size)
            for (i in 0 until p.n) {
                at0[i].forEachIndexed { j, v ->
                    assertEquals("对 $i 点 $j 偏离 from 侧", p.fromPts[i][j], v, 0f)
                }
            }
        }
    }

    /**
     * 冻结中途(t 停在飞行段内)显著偏离 from 典型形:滞留 flight 不清除,
     * 中间形态会被永久绘制 —— 反向钉住 R1 修复(清除滞留 flight)的必要性。
     */
    @Test fun frozenMidFlightIsOffCanonical() {
        val p = MorphEngine.plan(IconPaths.PLAY, IconPaths.PAUSE)
        val at04 = interpolate(p, 0.4f)
        var maxDrift = 0f
        for (i in 0 until p.n) {
            at04[i].forEachIndexed { j, v ->
                maxDrift = maxOf(maxDrift, abs(v - p.fromPts[i][j]))
            }
        }
        assertTrue("t=0.4 应显著偏离 from 形,实际最大漂移 $maxDrift", maxDrift > 0.5f)
    }
}
