package com.goodnight.ui.morph

/** 每子路径弧长采样点数(引擎管线固定 64)。 */
private const val N_SAMPLES = 64

/** 缓存容量上限(plan 缓存与采样缓存各自独立计数;图标仅 7 个,天然远低)。 */
private const val CACHE_MAX = 64

/** 整体同余阈值:拼接大点云 residual 低于该值才启用全局混合(spec D2.4)。 */
private const val GLOBAL_CONGRUENT_EPS = 5e-3f

/** plan 缓存键分隔符:d 字符串不含 NUL,拼接无歧义。 */
private const val KEY_SEP = "\u0000"

/**
 * 形变引擎门面(spec D2.4/D2.5 pipeline 装配):d 字符串 -> parsePathData ->
 * normalize -> resample(n=64) -> buildPlanIcon -> 全局同余混合 -> MorphPlan。
 * planAndMorph = plan + interpolate。
 *
 * 全局同余混合(block transport):计划构建后把全部对的点云拼接成整体 from/to
 * 大点云跑一次 similarity;若整体 residual < 5e-3,图标可被单一相似变换对齐
 * (如整体旋转的两条杠),此时每对 pairSims 共享全局变换;interpolate 输出
 * out_t(x) = sigma_t·R(theta·t)·x + t·(g − sigma·R(theta)·x),其中 (tx,ty)
 * 恰被逐点 residual 项吸收(共享整个变换即可),而整体同余时
 * g − sigma·R(theta)·x = tau_global 逐点常量——故输出为整图 sigma_t·R(theta·t)
 * 刚体运动 + t·tau_global 平移,任意两点相对向量全程按 sigma_t·R(theta·t)
 * 演化(与 spec 绕全局质心公式相差一个逐时刻公共平移,相对几何等价),端点
 * 仍精确(t=0 逐位 from,t=1 落 to)。非整体同余(如 PLAY->PAUSE,1 三角分裂
 * 2 竖条)保留 Task 5 逐对 pairSims 默认行为。
 *
 * 缓存:plan 以 "fromD\0toD" 为键、采样结果以 d 字符串为键,各自存放于访问
 * 序 LRU(上限 64,仅为防御任意输入设界)。线程模型:仅 UI 线程使用(Compose
 * 绘制期调用),不加锁——跨线程并发访问是调用方契约违规。
 */
object MorphEngine {

    /** 形变计划(全管线 + 全局混合;同键命中缓存返回同一实例)。 */
    fun plan(fromD: String, toD: String): MorphPlan =
        planCache.getOrPut(fromD + KEY_SEP + toD) {
            blendGlobal(buildPlanIcon(sampled(fromD), sampled(toD)))
        }

    /** 计划 + 插值一步完成(热路径:plan 命中缓存时仅 interpolate 开销)。 */
    fun planAndMorph(fromD: String, toD: String, t: Float): List<FloatArray> =
        interpolate(plan(fromD, toD), t)

    private val planCache = lruCache<String, MorphPlan>()
    private val sampleCache = lruCache<String, List<SampledPath>>()

    private fun sampled(d: String): List<SampledPath> =
        sampleCache.getOrPut(d) { normalize(parsePathData(d)).map { resample(it, N_SAMPLES) } }

    /**
     * 全局同余混合:整体 residual 达标 -> 每对共享全局 similarity(推导见类
     * KDoc;写入 pairSims,interpolate 无需感知混合与否);否则原样返回
     * (Task 5 逐对默认)。n=0 时无对可混(实际不触发,兜底)。
     */
    private fun blendGlobal(p: MorphPlan): MorphPlan {
        if (p.n == 0) return p
        val a = concatClouds(p.fromPts)
        val b = concatClouds(p.toPts)
        if (residual(a, b) >= GLOBAL_CONGRUENT_EPS) return p
        val g = similarity(a, b)
        return p.copy(pairSims = List(p.n) { g })
    }
}

/** 拼接点云列表为单个 2N 交错大点云。 */
private fun concatClouds(clouds: List<FloatArray>): FloatArray {
    var total = 0
    for (c in clouds) total += c.size
    val out = FloatArray(total)
    var w = 0
    for (c in clouds) {
        c.copyInto(out, w)
        w += c.size
    }
    return out
}

/** 访问序 LRU:LinkedHashMap(accessOrder=true),超容逐最旧访问条目。 */
private fun <K, V> lruCache(): LinkedHashMap<K, V> =
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > CACHE_MAX
    }
