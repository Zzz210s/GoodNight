package com.goodnight.ui.morph

import kotlin.math.floor
import kotlin.math.max

/**
 * 弧长等距重采样(N 默认 64)与角点锚定。
 *
 * 每子路径采样 N 个等弧长点;角点(相邻段切线方向突变 >30 度)与开路径两端点
 * 作为精确采样点锚定;其余点按弧长在相邻角点之间整数分配(最大余数法,预算充足
 * 时每区间 >=1,总数精确)。闭合子路径:Z 命令本身不产出闭合 cubic,此处补一段
 * 末点回起点的直线闭边并入弧长与采样;M 起点不人为视为角点(仅真实切线突变为
 * 角点)——采样内蕴于形状,等形不同起点的两个圈产生同一采样集(模索引旋转)。
 * 数值内核(扁段表/Gauss 弧长/切线/反解/角点)见同包 PathMath.kt。
 */
data class SampledPath(val points: FloatArray /* 2*N: x0,y0,x1,y1,... */, val closed: Boolean)

/**
 * 把 intervals 个区间按长度分给各 run:最大余数法,总量精确;预算充足(r<=intervals)
 * 时每 run 保底 1(必要时从最长 run 扣减)。余数量化到 1e9 消 Gauss fp 噪声抖动,
 * tie 取小索引(等长 run 在多次调用间分配稳定)。
 */
private fun apportionIntervals(runLen: DoubleArray, total: Double, intervals: Int): IntArray {
    val r = runLen.size
    val raw = DoubleArray(r) { intervals * runLen[it] / total }
    val counts = IntArray(r)
    var rem = intervals
    for (j in 0 until r) {
        counts[j] = floor(raw[j]).toInt()
        rem -= counts[j]
    }
    if (r <= intervals) {
        for (j in 0 until r) if (counts[j] < 1) { counts[j] = 1; rem-- }
        while (rem < 0) {
            var bi = 0
            for (j in 1 until r) if (counts[j] > counts[bi]) bi = j
            if (counts[bi] <= 1) break
            counts[bi]--; rem++
        }
    }
    if (rem > 0) {
        val order = (0 until r).sortedWith(
            compareByDescending<Int> { j -> Math.round((raw[j] - floor(raw[j])) * 1e9) }
                .thenBy { it })
        for (j in 0 until rem) counts[order[j % r]]++
    }
    return counts
}

/**
 * 把子路径重采样为 N 个等弧长点(2*N Float)。
 * n<2 时按 2 处理;总长 ~0 或空路径时全部点落在首点(无段时取原点)。
 */
fun resample(sp: SubPath, n: Int = 64): SampledPath {
    val nn = max(2, n)
    val out = FloatArray(2 * nn)
    if (sp.cubics.isEmpty()) {
        for (i in 0 until nn) { out[2 * i] = 0f; out[2 * i + 1] = 0f }
        return SampledPath(out, sp.closed)
    }
    val p = flatten(sp.cubics, sp.closed)
    val m = segCount(p)
    val lens = DoubleArray(m)
    var total = 0.0
    for (k in 0 until m) { lens[k] = segArc(p, k); total += lens[k] }
    val sx = sp.cubics[0].x0.toFloat()
    val sy = sp.cubics[0].y0.toFloat()
    if (total < 1e-12) {
        for (i in 0 until nn) { out[2 * i] = sx; out[2 * i + 1] = sy }
        return SampledPath(out, sp.closed)
    }

    // 锚点:闭合 = 仅角点(无角点圈时退回路径首点作唯一参考);开 = 端点 + 角点
    val anchors: List<Int>
    if (sp.closed) {
        val c = detectCorners(p, true)
        anchors = if (c.isEmpty()) listOf(0) else c
    } else {
        val s = sortedSetOf(0, m)
        s.addAll(detectCorners(p, false))
        anchors = s.toList()
    }
    // 相邻锚点之间 = 一个 run(闭合的末 run 环绕回首锚点 + m)
    val runA = ArrayList<Int>()
    val runB = ArrayList<Int>()
    if (sp.closed) {
        for (j in anchors.indices) {
            runA.add(anchors[j])
            runB.add(if (j + 1 < anchors.size) anchors[j + 1] else anchors[0] + m)
        }
    } else {
        for (j in 0 until anchors.size - 1) { runA.add(anchors[j]); runB.add(anchors[j + 1]) }
    }
    val runLen = DoubleArray(runA.size) { r ->
        var s = 0.0
        var k = runA[r]
        while (k < runB[r]) { s += lens[k % m]; k++ }
        s
    }
    val intervals = if (sp.closed) nn else nn - 1
    val counts = apportionIntervals(runLen, total, intervals)

    // 采样:run 起点 = 锚点精确落样,内部按弧长等分(跨段时逐段反解)
    val acc = DoubleArray(2 * nn)
    var w = 0
    for (r in runA.indices) {
        val cnt = counts[r]
        if (cnt == 0) continue
        val a = runA[r]
        val b = runB[r]
        val Lr = runLen[r]
        acc[2 * w] = p[6 * a]; acc[2 * w + 1] = p[6 * a + 1]
        w++
        var seg = a
        var passed = 0.0
        for (j in 1 until cnt) {
            val target = Lr * j / cnt
            while (seg < b - 1 && passed + lens[seg % m] < target) {
                passed += lens[seg % m]; seg++
            }
            val k = seg % m
            val ls = lens[k]
            val t = if (ls > 1e-12) invertArc(p, k, target - passed, ls) else 0.0
            evalBezier(p, k, t, acc, 2 * w)
            w++
        }
    }
    if (!sp.closed) { acc[2 * w] = p[6 * m]; acc[2 * w + 1] = p[6 * m + 1] }
    for (i in 0 until nn) { out[2 * i] = acc[2 * i].toFloat(); out[2 * i + 1] = acc[2 * i + 1].toFloat() }
    return SampledPath(out, sp.closed)
}

/** 单条三次段的弧长(8 点 Gauss-Legendre)。 */
fun arcLengthOf(c: Cubic): Float = segArc(flatten(listOf(c), false), 0).toFloat()

/**
 * 弧长 s(自段起点计,夹取到 [0, arcLengthOf])处的点坐标。
 * 内部把弧长按 |B'| 反解到参数 t(收敛 ~1e-4)再求 Bernstein 点;
 * 该点的切线方向即三次导矢 3(1-t)²(C1-P0)+6(1-t)t(C2-C1)+3t²(P3-C2) 在 t 处取值。
 */
fun lengthAt(c: Cubic, s: Float): Pair<Float, Float> {
    val p = flatten(listOf(c), false)
    val t = invertArc(p, 0, s.toDouble(), segArc(p, 0))
    val q = DoubleArray(2)
    evalBezier(p, 0, t, q, 0)
    return Pair(q[0].toFloat(), q[1].toFloat())
}
