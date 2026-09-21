package com.goodnight.ui.morph

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 子路径匹配内核(spec D2.3,internal):代价 = dist(centroid) + 0.35·|ΔL|
 * (L = 采样点云总弧长,闭合含首尾环绕边)。等数 -> 最小代价排列(<=8 穷举
 * DFS 带剪枝,>8 全局贪心);不等数 -> 大侧 surjective 分配到小侧(阶段一按
 * 全局代价升序给每个小侧配一个互异大侧,阶段二剩余大侧就近落位、允许重复)
 * ——小侧子路径由此复制进多对,分裂语义。空侧兜底:对侧质心退化点云(真实
 * 图标不会触发;保证不崩、值有限)。数值内核 Double,边界 Float(屋规)。
 */

internal class SubInfo(
    val closed: Boolean,
    val cx: Double,
    val cy: Double,
    val len: Double,
)

/** 质心与采样弧长(闭合含环绕闭合边)。 */
internal fun info(sp: SampledPath): SubInfo {
    val p = sp.points
    val n = p.size / 2
    var sx = 0.0
    var sy = 0.0
    var i = 0
    while (i + 1 < p.size) { sx += p[i]; sy += p[i + 1]; i += 2 }
    var len = 0.0
    for (j in 0 until n - 1) {
        len += hypot((p[2 * j + 2] - p[2 * j]).toDouble(), (p[2 * j + 3] - p[2 * j + 1]).toDouble())
    }
    if (sp.closed && n > 1) {
        len += hypot((p[0] - p[2 * n - 2]).toDouble(), (p[1] - p[2 * n - 1]).toDouble())
    }
    return SubInfo(sp.closed, if (n > 0) sx / n else 0.0, if (n > 0) sy / n else 0.0, len)
}

internal fun cost(x: SubInfo, y: SubInfo): Double =
    hypot(x.cx - y.cx, x.cy - y.cy) + 0.35 * abs(x.len - y.len)

/** 等数配对:perm[i] = 与 a[i] 配对的 b 下标。 */
internal fun matchEqual(a: List<SubInfo>, b: List<SubInfo>): IntArray {
    val m = a.size
    if (m == 0) return IntArray(0)
    if (m > 8) return greedyPermutation(a, b)
    val used = BooleanArray(m)
    val perm = IntArray(m)
    val best = IntArray(m)
    var bestCost = Double.MAX_VALUE
    fun dfs(i: Int, acc: Double) {
        if (acc >= bestCost) return // 剪枝:部分代价已不优于当前最优
        if (i == m) {
            bestCost = acc
            System.arraycopy(perm, 0, best, 0, m)
            return
        }
        for (j in 0 until m) {
            if (used[j]) continue
            used[j] = true
            perm[i] = j
            dfs(i + 1, acc + cost(a[i], b[j]))
            used[j] = false
        }
    }
    dfs(0, 0.0)
    return best
}

/** >8 子路径的全局贪心:代价升序遍历全部 (i,j),双侧未占则配。 */
private fun greedyPermutation(a: List<SubInfo>, b: List<SubInfo>): IntArray {
    val m = a.size
    val usedA = BooleanArray(m)
    val usedB = BooleanArray(m)
    val perm = IntArray(m) { -1 }
    val order = (0 until m * m).sortedBy { cost(a[it / m], b[it % m]) }
    for (idx in order) {
        val i = idx / m
        val j = idx % m
        if (!usedA[i] && !usedB[j]) { perm[i] = j; usedA[i] = true; usedB[j] = true }
    }
    return perm
}

/**
 * 不等数 surjective 分配(小侧 <- 大侧):返回 (小下标, 大下标) 对,按大侧
 * 下标有序(大侧全分配;小侧至少 1 个,多者复制)。
 */
internal fun matchSurjective(small: List<SubInfo>, large: List<SubInfo>): List<Pair<Int, Int>> {
    val s = small.size
    val l = large.size
    val assign = IntArray(l) { -1 } // 大 j -> 小 i
    val covered = BooleanArray(s)
    // 阶段一:全局代价升序,给每个小侧配一个互异大侧(l >= s 必然覆盖全部小侧)
    val order = (0 until s * l).sortedBy { cost(small[it / l], large[it % l]) }
    for (idx in order) {
        val i = idx / l
        val j = idx % l
        if (!covered[i] && assign[j] == -1) { assign[j] = i; covered[i] = true }
    }
    // 阶段二:剩余大侧就近落位(允许重复——分裂)
    for (j in 0 until l) if (assign[j] == -1) {
        var bi = 0
        var bc = Double.MAX_VALUE
        for (i in 0 until s) {
            val c = cost(small[i], large[j])
            if (c < bc) { bc = c; bi = i }
        }
        assign[j] = bi
    }
    return (0 until l).map { assign[it] to it }
}

/** 与 sp 同长度、全落其质心的退化点云(空侧兜底)。 */
internal fun degenerateLike(sp: SampledPath): SampledPath {
    val p = sp.points
    val out = FloatArray(p.size)
    var cx = 0.0
    var cy = 0.0
    var i = 0
    while (i + 1 < p.size) { cx += p[i]; cy += p[i + 1]; i += 2 }
    if (p.size >= 2) { cx /= p.size / 2; cy /= p.size / 2 }
    val fx = cx.toFloat()
    val fy = cy.toFloat()
    i = 0
    while (i + 1 < out.size) { out[i] = fx; out[i + 1] = fy; i += 2 }
    return SampledPath(out, sp.closed)
}
