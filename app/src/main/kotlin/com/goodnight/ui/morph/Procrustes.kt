package com.goodnight.ui.morph

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 二维相似变换(similarity):x' = scale·R(theta)·x + (tx, ty),R 为 theta 弧度的
 * 标准旋转。两端点云同坐标系(y 同向),故映射无需反射/翻转分量。
 */
data class SimTransform(val theta: Float, val scale: Float, val tx: Float, val ty: Float)

/**
 * 闭式 2D Procrustes(similarity)对齐:求相似变换 T,使 T(a) 最小二乘逼近 b。
 *
 * 输入为 2N 交错 FloatArray(x0,y0,x1,y1,...),a/b 同长、索引一一对应
 * (配对/对应是调用方职责——见 Task 5;本函数不重排任何点序)。内部全部 Double
 * 运算,仅边界转 Float。算法(二维 Kabsch 特例):
 *
 * 1) 中心化:各自 centroid 归零(平移从旋转/缩放中解耦);
 * 2) 旋转:2x2 cross-covariance H = Σ (a_i−ca)·(b_i−cb)ᵀ,
 *    θ = atan2(H[0,1]−H[1,0], H[0,0]+H[1,1])(主值落在 (−π, π]),
 *    最大化 tr(R·H),即最小化纯旋转残差;
 * 3) 缩放:给定 θ 的最小二乘解 σ = Σ b̃·(R·ã) / Σ‖ã‖²(非负,见下);
 * 4) 平移:t = centroid_b − σ·R·centroid_a。
 *
 * 退化保护:空/长度 <2 -> 恒等;源点云零方差(全部同点,含单点)-> 纯平移
 * (θ=0, σ=1)。输出恒有限,不产生 NaN/Infinity。
 *
 * 遍历方向(点序逆走)与索引环形偏移的选择不在此处——那是对应阶段(Task 5)
 * 的职责,对本函数只需把 b 的点序按候选方向重排后再调用即可逐方向打分。
 */
fun similarity(a: FloatArray, b: FloatArray): SimTransform {
    val n = minOf(a.size, b.size) / 2
    if (n <= 0) return SimTransform(0f, 1f, 0f, 0f)
    if (n == 1) return SimTransform(0f, 1f, b[0] - a[0], b[1] - a[1])
    var caX = 0.0; var caY = 0.0; var cbX = 0.0; var cbY = 0.0
    var i = 0
    while (i < 2 * n) {
        caX += a[i]; caY += a[i + 1]; cbX += b[i]; cbY += b[i + 1]
        i += 2
    }
    caX /= n; caY /= n; cbX /= n; cbY /= n
    var h00 = 0.0; var h01 = 0.0; var h10 = 0.0; var h11 = 0.0
    var ssa = 0.0
    i = 0
    while (i < 2 * n) {
        val ax = a[i].toDouble() - caX; val ay = a[i + 1].toDouble() - caY
        val bx = b[i].toDouble() - cbX; val by = b[i + 1].toDouble() - cbY
        h00 += ax * bx; h01 += ax * by; h10 += ay * bx; h11 += ay * by
        ssa += ax * ax + ay * ay
        i += 2
    }
    // 源零方差:旋转/缩放无定义,退化为质心平移
    if (ssa < 1e-24) return SimTransform(0f, 1f, (cbX - caX).toFloat(), (cbY - caY).toFloat())
    val theta = atan2(h01 - h10, h00 + h11)
    val co = cos(theta); val si = sin(theta)
    // σ = Σ b̃·(R·ã) / Σ‖ã‖²;θ 取最大化角,该叉积项非负(max(0,·) 仅兜底浮点)
    var cross = 0.0
    i = 0
    while (i < 2 * n) {
        val ax = a[i].toDouble() - caX; val ay = a[i + 1].toDouble() - caY
        val bx = b[i].toDouble() - cbX; val by = b[i + 1].toDouble() - cbY
        val rx = co * ax - si * ay
        val ry = si * ax + co * ay
        cross += bx * rx + by * ry
        i += 2
    }
    val scale = max(0.0, cross / ssa)
    val tx = cbX - scale * (co * caX - si * caY)
    val ty = cbY - scale * (si * caX + co * caY)
    return SimTransform(theta.toFloat(), scale.toFloat(), tx.toFloat(), ty.toFloat())
}

/**
 * 应用相似变换:x' = scale·R(theta)·x + (tx, ty)。返回与 pts 等长的新数组,
 * 源数组不变;变换恒为有限值(pts 全有限时)。
 */
fun apply(t: SimTransform, pts: FloatArray): FloatArray {
    val out = FloatArray(pts.size)
    val co = cos(t.theta.toDouble()); val si = sin(t.theta.toDouble())
    val s = t.scale.toDouble(); val tx = t.tx.toDouble(); val ty = t.ty.toDouble()
    var i = 0
    while (i < pts.size) {
        val x = pts[i].toDouble(); val y = pts[i + 1].toDouble()
        out[i] = (s * (co * x - si * y) + tx).toFloat()
        out[i + 1] = (s * (si * x + co * y) + ty).toFloat()
        i += 2
    }
    return out
}

/**
 * 对齐后归一化 RMS 残差:先取 similarity(a, b) 对齐,再求
 * sqrt( Σ‖scale·R·a + t − b‖² / Σ‖b‖² )——spec 的 res 公式,字面实现。
 * 语义:0 = 完全可被相似变换对齐;值越大相似度越低。退化保护:空 -> 0;
 * 目标零范数(全落原点)时,对齐也精确 -> 0,否则返回 1(不可比,有界)。
 */
fun residual(a: FloatArray, b: FloatArray): Float {
    val n = minOf(a.size, b.size) / 2
    if (n <= 0) return 0f
    val t = similarity(a, b)
    val co = cos(t.theta.toDouble()); val si = sin(t.theta.toDouble())
    val s = t.scale.toDouble(); val tx = t.tx.toDouble(); val ty = t.ty.toDouble()
    var num = 0.0
    var den = 0.0
    var i = 0
    while (i < 2 * n) {
        val x = a[i].toDouble(); val y = a[i + 1].toDouble()
        val bx = b[i].toDouble(); val by = b[i + 1].toDouble()
        val mx = s * (co * x - si * y) + tx
        val my = s * (si * x + co * y) + ty
        val dx = mx - bx; val dy = my - by
        num += dx * dx + dy * dy
        den += bx * bx + by * by
        i += 2
    }
    if (den < 1e-24) return if (num < 1e-24) 0f else 1f
    return sqrt(num / den).toFloat()
}
