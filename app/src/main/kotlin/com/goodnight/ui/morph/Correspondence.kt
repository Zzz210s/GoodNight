package com.goodnight.ui.morph

import kotlin.math.max

/**
 * 子路径对应(spec D2.3)与点云级对齐。匹配内核(代价/排列/surjective 分配,
 * internal)见 SubpathMatch.kt。
 *
 * 对齐约定(单一,固定):永远重排 to 侧(b)去对齐 from 侧(a)——a 的点序绝不
 * 动,b 只用其副本做环形偏移/倒序。闭合对:圆形偏移寻优(粗扫步进后细扫)x
 * 2 遍历方向(正序/倒序),分数 = similarity 对齐后的 residual,取最优,平局取
 * 正序;含开路径的对:端点锚定不做偏移(固定索引配对),但倒序遍历仍是合法
 * 候选(左到右 vs 右到左画的两笔),同一打分标准择优。输出全部为新数组,
 * 输入 SampledPath 不可变。
 */

/** 形变计划:n 对已对齐 (from, to) 点云(各 2N 交错,N=64);pairSims 为
 * 对齐后每对的 similarity(from_i, to_i)——供 Task 6 引擎做全局同余判定与
 * block transport 混合,亦避免 interpolate 重复计算;空表则 interpolate 现算。 */
data class MorphPlan(
    val fromPts: List<FloatArray>,
    val toPts: List<FloatArray>,
    val n: Int,
    val pairSims: List<SimTransform> = emptyList(),
)

/** 单子路径对便捷入口(直接配对 + 圆形寻优),brief 单子路径测试用。 */
fun buildPlan(a: SampledPath, b: SampledPath): MorphPlan =
    buildPlanIcon(listOf(a), listOf(b))

/** 主入口:两侧子路径集合匹配(等数排列 / 不等数 surjective 分裂,小侧按需
 * 复制进多对),再逐对对齐。n = max(两侧子路径数)。 */
fun buildPlanIcon(a: List<SampledPath>, b: List<SampledPath>): MorphPlan {
    val A = if (a.isEmpty()) b.map { degenerateLike(it) } else a
    val B = if (b.isEmpty()) A.map { degenerateLike(it) } else b
    val ia = A.map { info(it) }
    val ib = B.map { info(it) }
    val pairs: List<Pair<Int, Int>> = if (A.size == B.size) {
        val perm = matchEqual(ia, ib)
        A.indices.map { it to perm[it] }
    } else if (A.size < B.size) {
        matchSurjective(ia, ib) // (小=a, 大=b) -> (aIdx, bIdx)
    } else {
        matchSurjective(ib, ia).map { (s, l) -> l to s } // (小=b, 大=a) -> (aIdx, bIdx)
    }
    val from = ArrayList<FloatArray>(pairs.size)
    val to = ArrayList<FloatArray>(pairs.size)
    for ((i, j) in pairs) {
        val (f, t) = alignPair(A[i], B[j])
        from.add(f)
        to.add(t)
    }
    val sims = from.indices.map { similarity(from[it], to[it]) }
    return MorphPlan(from, to, pairs.size, sims)
}

/**
 * 闭合点云圆形偏移寻优:返回 k,使 b 滚动 k 位(to[i] = b[(i+k) mod N])后
 * 经 similarity 对齐 a 的 residual 最小。默认步进 4(粗扫 N/4 次)后细扫
 * ±(step-1);只搜给定遍历方向——倒序候选是调用方(alignPair)的职责。
 * N<2 -> 0。
 */
fun bestCircularOffset(closedA: FloatArray, closedB: FloatArray, step: Int = 4): Int {
    val n = closedB.size / 2
    if (n < 2) return 0
    val st = max(1, step)
    var bestK = 0
    var bestS = Float.MAX_VALUE
    for (k in 0 until n step st) {
        val s = residual(closedA, rollCloud(closedB, k))
        if (s < bestS) { bestS = s; bestK = k }
    }
    for (d in 1 - st until st) {
        val k = ((bestK + d) % n + n) % n
        val s = residual(closedA, rollCloud(closedB, k))
        if (s < bestS) { bestS = s; bestK = k }
    }
    return bestK
}

/** 闭合点云滚动 k 位:out[i] = pts[(i+k) mod N];k 模 N 为 0 时返回等值副本。 */
internal fun rollCloud(pts: FloatArray, k: Int): FloatArray {
    val n = pts.size / 2
    if (n == 0) return pts.copyOf()
    val kk = ((k % n) + n) % n
    val out = FloatArray(pts.size)
    for (i in 0 until n) {
        val s = ((i + kk) % n) * 2
        out[2 * i] = pts[s]
        out[2 * i + 1] = pts[s + 1]
    }
    return out
}

/** 点云倒序(遍历方向反转),开路径语义为笔迹反向。 */
internal fun reversedCloud(pts: FloatArray): FloatArray {
    val n = pts.size / 2
    val out = FloatArray(pts.size)
    for (i in 0 until n) {
        out[2 * i] = pts[2 * (n - 1 - i)]
        out[2 * i + 1] = pts[2 * (n - 1 - i) + 1]
    }
    return out
}

/** 单对对齐:返回 (from 副本, 对齐后的 to)。闭合对做偏移x方向寻优,
 * 含开路径对只做方向(正序/倒序)择优,平局取正序。 */
internal fun alignPair(a: SampledPath, b: SampledPath): Pair<FloatArray, FloatArray> {
    val from = a.points.copyOf()
    if (a.closed && b.closed) {
        var best = b.points
        var bestS = Float.MAX_VALUE
        for (cand in listOf(b.points, reversedCloud(b.points))) {
            val rolled = rollCloud(cand, bestCircularOffset(a.points, cand))
            val s = residual(a.points, rolled)
            if (s < bestS) { bestS = s; best = rolled }
        }
        return from to best
    }
    val fwd = residual(a.points, b.points)
    val rev = residual(a.points, reversedCloud(b.points))
    val to = if (rev < fwd) reversedCloud(b.points) else b.points.copyOf()
    return from to to
}
