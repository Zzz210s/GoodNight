package com.goodnight.ui.morph

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val PI_F = 3.14159265f
private const val TWO_PI_F = 6.2831853f
private const val HALF_PI_F = 1.5707963f

/** 三次贝塞尔段(起终点 + 两控制点);直线/二次/弧均折算为该形式。 */
data class Cubic(
    val x0: Float, val y0: Float, val c1x: Float, val c1y: Float,
    val c2x: Float, val c2y: Float, val x1: Float, val y1: Float,
)

/** 子路径:三次链 + 闭合标记(Z 只标记,闭合边由重采样环节补回)。 */
data class SubPath(val cubics: List<Cubic>, val closed: Boolean)

/**
 * 规格化命令链为三次贝塞尔子路径链:L/Q/T 转 cubic;S/T 反射前段控制点;
 * A 按 SVG F.6 中心参数化,每片中心角 <=90 度,α=(4/3)·tan(Δθ/4);
 * 输入须为 parsePathData 输出(坐标已绝对化,H/V 已转 L)。
 */
fun normalize(path: List<List<PathCmd>>): List<SubPath> =
    path.map { SubPath(chainCubics(it), it.any { c -> c.verb == 'Z' }) }

/** 单条链逐命令产出 cubic;维护当前点与上一曲线命令的末端控制点(供反射)。
 * Z 复位当前点与反射状态至子路径起点(与 parsePathData 的 px/py 语义一致)。 */
private fun chainCubics(cmds: List<PathCmd>): List<Cubic> {
    val out = ArrayList<Cubic>()
    var x = 0f
    var y = 0f
    var sx = 0f // 子路径起点(M 落笔点,Z 复位处)
    var sy = 0f
    var curve = ' ' // 上一 C/S/Q/T 类别,决定 S/T 是否反射
    var lx = 0f // 其末端控制点(C/S 的 c2,或 Q/T 的二次控制点)
    var ly = 0f
    for (cmd in cmds) {
        val a = cmd.args
        when (cmd.verb) {
            'M' -> {
                x = a[0]; y = a[1]; sx = a[0]; sy = a[1]; curve = ' '
            }
            'L' -> {
                val dx3 = (a[0] - x) / 3f
                val dy3 = (a[1] - y) / 3f
                out.add(Cubic(x, y, x + dx3, y + dy3, a[0] - dx3, a[1] - dy3, a[0], a[1]))
                x = a[0]; y = a[1]; curve = ' '
            }
            'C' -> {
                out.add(Cubic(x, y, a[0], a[1], a[2], a[3], a[4], a[5]))
                lx = a[2]; ly = a[3]; x = a[4]; y = a[5]; curve = 'C'
            }
            'S' -> {
                val reflect = curve == 'C' || curve == 'S'
                val c1x = if (reflect) 2f * x - lx else x
                val c1y = if (reflect) 2f * y - ly else y
                out.add(Cubic(x, y, c1x, c1y, a[0], a[1], a[2], a[3]))
                lx = a[0]; ly = a[1]; x = a[2]; y = a[3]; curve = 'S'
            }
            'Q' -> {
                val qx = a[0]; val qy = a[1]; val q1x = a[2]; val q1y = a[3]
                out.add(Cubic(x, y,
                    x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
                    q1x + 2f / 3f * (qx - q1x), q1y + 2f / 3f * (qy - q1y),
                    q1x, q1y))
                lx = qx; ly = qy; x = q1x; y = q1y; curve = 'Q'
            }
            'T' -> {
                val reflect = curve == 'Q' || curve == 'T'
                val qx = if (reflect) 2f * x - lx else x
                val qy = if (reflect) 2f * y - ly else y
                val q1x = a[0]; val q1y = a[1]
                out.add(Cubic(x, y,
                    x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
                    q1x + 2f / 3f * (qx - q1x), q1y + 2f / 3f * (qy - q1y),
                    q1x, q1y))
                lx = qx; ly = qy; x = q1x; y = q1y; curve = 'T'
            }
            'A' -> {
                arcCubics(x, y, a[0], a[1], a[2], a[3] == 1f, a[4] == 1f, a[5], a[6], out)
                x = a[5]; y = a[6]; curve = ' '
            }
            'Z' -> { // 闭合不产出 cubic;画笔回到子路径起点并清除反射(链可能继续绘图)
                x = sx; y = sy; curve = ' '; lx = sx; ly = sy
            }
            else -> throw IllegalArgumentException("normalize 不支持命令 '${cmd.verb}'")
        }
    }
    return out
}

/** 两点间的直线三次段(控制点位于 1/3 与 2/3 处)。 */
private fun lineCubic(x0: Float, y0: Float, x1: Float, y1: Float): Cubic {
    val dx3 = (x1 - x0) / 3f
    val dy3 = (y1 - y0) / 3f
    return Cubic(x0, y0, x0 + dx3, y0 + dy3, x1 - dx3, y1 - dy3, x1, y1)
}

/**
 * SVG 附录 F.6:椭圆弧转三次贝塞尔片(每片 <=90 度),追加到 out。
 * 退化:端点重合 -> 省略;rx/ry=0 -> 直线。数值禁止 NaN/Infinity。
 */
private fun arcCubics(
    x0: Float, y0: Float, rxi: Float, ryi: Float, deg: Float,
    large: Boolean, sweep: Boolean, x1: Float, y1: Float, out: MutableList<Cubic>,
) {
    if (x0 == x1 && y0 == y1) return
    var rx = abs(rxi)
    var ry = abs(ryi)
    if (rx == 0f || ry == 0f) {
        out.add(lineCubic(x0, y0, x1, y1))
        return
    }
    val phi = deg * (PI_F / 180f)
    val cp = cos(phi)
    val sp = sin(phi)
    val hx = (x0 - x1) / 2f
    val hy = (y0 - y1) / 2f
    val ux = cp * hx + sp * hy // F.6.5.1 平移旋转后的半差
    val uy = -sp * hx + cp * hy
    val lam = ux * ux / (rx * rx) + uy * uy / (ry * ry)
    if (lam > 1f) { // F.6.6 半径按需放大
        val k = sqrt(lam)
        rx *= k
        ry *= k
    }
    var cxp = 0f // F.6.5.2 中心(取法由大弧标志与扫掠标志共同决定)
    var cyp = 0f
    val den = rx * rx * uy * uy + ry * ry * ux * ux
    if (den > 0f) {
        val num = rx * rx * ry * ry - rx * rx * uy * uy - ry * ry * ux * ux
        val sign = if (large != sweep) 1f else -1f
        val s = sqrt(max(0f, num / den))
        cxp = sign * s * rx * uy / ry
        cyp = -sign * s * ry * ux / rx
    }
    val cx = cp * cxp - sp * cyp + (x0 + x1) / 2f // F.6.5.3 世界坐标中心
    val cy = sp * cxp + cp * cyp + (y0 + y1) / 2f
    val ta = atan2((uy - cyp) / ry, (ux - cxp) / rx) // F.6.5.4/5 起止角
    val tb = atan2((-uy - cyp) / ry, (-ux - cxp) / rx)
    var dt = (tb - ta) % TWO_PI_F
    if (dt < 0f) dt += TWO_PI_F
    if (!sweep) dt -= TWO_PI_F // 反向 sweep = 负角方向
    if (dt == 0f) return
    fun ex(t: Float) = cx + rx * cos(t) * cp - ry * sin(t) * sp // 椭圆点
    fun ey(t: Float) = cy + rx * cos(t) * sp + ry * sin(t) * cp
    fun exd(t: Float) = -rx * sin(t) * cp - ry * cos(t) * sp // 对 θ 的导数
    fun eyd(t: Float) = -rx * sin(t) * sp + ry * cos(t) * cp
    val n = max(1, ceil((abs(dt) - 1e-3f) / HALF_PI_F).toInt()) // <=90 度切片(带浮点容差)
    val seg = dt / n
    val alpha = 4f / 3f * tan(seg / 4f)
    for (k in 0 until n) {
        val a = ta + seg * k
        val b = ta + seg * (k + 1)
        val sx = if (k == 0) x0 else ex(a)
        val sy = if (k == 0) y0 else ey(a)
        val ex1 = if (k == n - 1) x1 else ex(b)
        val ey1 = if (k == n - 1) y1 else ey(b)
        out.add(Cubic(sx, sy,
            sx + alpha * exd(a), sy + alpha * eyd(a),
            ex1 - alpha * exd(b), ey1 - alpha * eyd(b), ex1, ey1))
    }
}
