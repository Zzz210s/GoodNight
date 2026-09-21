package com.goodnight.ui.morph

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * 弧长重采样的数值内核(与 Resampler.kt 同包,internal 供其调用):
 * cubic 扁段表(flatten)、8 点 Gauss-Legendre 弧长、端点切线、弧长反解、
 * 角点边界检测。全部 Double 运算;不依赖 Android。
 */

/** 8 点 Gauss-Legendre 节点/权重([-1,1] 对称,只存正半节点)。 */
internal val GLX = doubleArrayOf(
    0.18343464249564978, 0.525532409916329, 0.7966664774136267, 0.9602898564975363)
internal val GLW = doubleArrayOf(
    0.362683783378362, 0.31370664587788727, 0.22238103445337448, 0.10122853629037626)

/** 角点阈值:相邻段切线夹角 >30 度判为角点。 */
internal const val CORNER_RAD = 0.5235987755982988 // 30°(rad,常量避免对 PI 的求值)

/**
 * 把 Cubic 链压平为段表:首点 2 个 + 每段 6 个数
 * (c1x,c1y,c2x,c2y,x1,y1;段 k 的起点即 p[6k],终点即 p[6k+6])。
 * closed=true 且末点未回首点时追加一段直线闭边(闭合语义,非几何猜测)。
 */
internal fun flatten(cs: List<Cubic>, closed: Boolean): DoubleArray {
    if (cs.isEmpty()) return DoubleArray(2)
    val last = cs[cs.size - 1]
    val dx = cs[0].x0 - last.x1
    val dy = cs[0].y0 - last.y1
    val needClose = closed && dx * dx + dy * dy > 1e-18f
    val p = DoubleArray(2 + 6 * (cs.size + if (needClose) 1 else 0))
    p[0] = cs[0].x0.toDouble()
    p[1] = cs[0].y0.toDouble()
    var w = 2
    for (c in cs) {
        p[w] = c.c1x.toDouble(); p[w + 1] = c.c1y.toDouble()
        p[w + 2] = c.c2x.toDouble(); p[w + 3] = c.c2y.toDouble()
        p[w + 4] = c.x1.toDouble(); p[w + 5] = c.y1.toDouble()
        w += 6
    }
    if (needClose) { // 闭边:直线(控制点位于 1/3 与 2/3),回到首点
        val a = (p[0] - p[w - 2]) / 3.0
        val b = (p[1] - p[w - 1]) / 3.0
        p[w] = p[w - 2] + a; p[w + 1] = p[w - 1] + b
        p[w + 2] = p[w - 2] + 2.0 * a; p[w + 3] = p[w - 1] + 2.0 * b
        p[w + 4] = p[0]; p[w + 5] = p[1]
    }
    return p
}

/** 段数 m(扁表大小 = 2 + 6m)。 */
internal fun segCount(p: DoubleArray) = (p.size - 2) / 6

/** |B'(t)|:三次段 k 的导矢模长。 */
internal fun segSpeed(p: DoubleArray, k: Int, t: Double): Double {
    val i = 6 * k
    val u = 1.0 - t
    val a = 3.0 * u * u
    val b = 6.0 * u * t
    val c = 3.0 * t * t
    val dx = a * (p[i + 2] - p[i]) + b * (p[i + 4] - p[i + 2]) + c * (p[i + 6] - p[i + 4])
    val dy = a * (p[i + 3] - p[i + 1]) + b * (p[i + 5] - p[i + 3]) + c * (p[i + 7] - p[i + 5])
    return hypot(dx, dy)
}

/** ∫₀^t1 |B'| dt:段 k 上 [0,t1] 的弧长(8 点 Gauss-Legendre)。 */
internal fun segArc(p: DoubleArray, k: Int, t1: Double = 1.0): Double {
    val half = t1 / 2.0
    var s = 0.0
    for (j in 0 until 4) {
        val g = GLX[j]
        s += GLW[j] * (segSpeed(p, k, half + half * g) + segSpeed(p, k, half - half * g))
    }
    return s * half
}

/** Bernstein 求值:段 k 在 t 处的点,写入 out[o],out[o+1]。 */
internal fun evalBezier(p: DoubleArray, k: Int, t: Double, out: DoubleArray, o: Int) {
    val i = 6 * k
    val u = 1.0 - t
    val b0 = u * u * u
    val b1 = 3.0 * u * u * t
    val b2 = 3.0 * u * t * t
    val b3 = t * t * t
    out[o] = b0 * p[i] + b1 * p[i + 2] + b2 * p[i + 4] + b3 * p[i + 6]
    out[o + 1] = b0 * p[i + 1] + b1 * p[i + 3] + b2 * p[i + 5] + b3 * p[i + 7]
}

/**
 * 段 k 端点切线方向(atEnd=末端 P3,否则起点 P0)。控制臂退化时回退到下一个
 * 可用的控制点;完全退化返回 null。
 */
internal fun segTangent(p: DoubleArray, k: Int, atEnd: Boolean): DoubleArray? {
    val i = 6 * k
    if (atEnd) {
        for (j in intArrayOf(4, 2, 0)) {
            val dx = p[i + 6] - p[i + j]
            val dy = p[i + 7] - p[i + j + 1]
            if (dx * dx + dy * dy > 1e-18) return doubleArrayOf(dx, dy)
        }
    } else {
        for (j in intArrayOf(2, 4, 6)) {
            val dx = p[i + j] - p[i]
            val dy = p[i + j + 1] - p[i + 1]
            if (dx * dx + dy * dy > 1e-18) return doubleArrayOf(dx, dy)
        }
    }
    return null
}

/** 弧长反解:t 使 ∫₀^t |B'| = s。Newton 带二分括界,12 次迭代收敛到 ~1e-4 内。 */
internal fun invertArc(p: DoubleArray, k: Int, s: Double, ls: Double): Double {
    if (s <= 0.0) return 0.0
    if (s >= ls) return 1.0
    var lo = 0.0
    var hi = 1.0
    var t = s / ls
    for (it in 0 until 12) {
        val f = segArc(p, k, t) - s
        if (abs(f) < 1e-10 * ls + 1e-14) break
        if (f > 0.0) hi = t else lo = t
        val sp = segSpeed(p, k, t)
        val nt = if (sp > 1e-12) t - f / sp else (lo + hi) / 2.0
        t = if (nt > lo && nt < hi) nt else (lo + hi) / 2.0
    }
    return t
}

/**
 * 角点边界:相邻活动段(非零长)切线夹角 >30° 的段起点索引。开路径不含端点
 * (由调用方补);闭合路径含接缝处(末段终点 -> 首段起点)。
 */
internal fun detectCorners(p: DoubleArray, closed: Boolean): List<Int> {
    val m = segCount(p)
    val active = ArrayList<Int>()
    for (k in 0 until m) if (segArc(p, k) > 1e-9) active.add(k)
    if (active.isEmpty()) return emptyList()
    val corners = ArrayList<Int>()
    fun test(a: Int, b: Int) {
        val u = segTangent(p, a, true) ?: return
        val v = segTangent(p, b, false) ?: return
        val cross = u[0] * v[1] - u[1] * v[0]
        val dot = u[0] * v[0] + u[1] * v[1]
        if (abs(atan2(cross, dot)) > CORNER_RAD) corners.add(b)
    }
    for (j in 0 until active.size - 1) test(active[j], active[j + 1])
    if (closed && active.size > 1) test(active[active.size - 1], active[0])
    return corners.sorted()
}
