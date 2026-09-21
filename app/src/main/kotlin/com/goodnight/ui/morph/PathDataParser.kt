package com.goodnight.ui.morph

/**
 * SVG path d 解析(M/L/H/V/C/S/Q/T/A/Z,绝对+相对)。输出:坐标全部绝对化;
 * verb 统一大写保留语义,H/V 已转同坐标 L;每条子路径 = 以 M 开头的一条链。
 * 语法错误(非法字符/参数不足/缺首 M)抛 IllegalArgumentException(带位置)。
 */

/** 一条路径命令:verb ∈ {M,L,C,S,Q,T,A,Z},args 为绝对坐标参数。 */
data class PathCmd(val verb: Char, val args: FloatArray)

/** 解析 path data:外层 = 子路径,内层 = 以 M 开头的命令链。 */
fun parsePathData(d: String): List<List<PathCmd>> = PathParser(d).parse()

private class PathParser(private val d: String) {
    private var i = 0
    private var px = 0f; private var py = 0f          // 当前点
    private var mx = 0f; private var my = 0f          // 子路径起点(Z 复位)
    private var chain = mutableListOf<PathCmd>()
    private val paths = mutableListOf<List<PathCmd>>()

    fun parse(): List<List<PathCmd>> {
        while (true) {
            skipSep()
            if (i >= d.length) break
            val c = d[i]
            if (!c.isLetter()) throw fail("期望命令字母,却遇到字符 '$c'")
            i++
            if (chain.isEmpty() && c.uppercaseChar() != 'M') throw fail("path 必须以 M/m 命令开始")
            dispatch(c)
        }
        finish()
        return paths
    }

    private fun dispatch(c: Char) {
        val rel = c.isLowerCase()
        when (c.uppercaseChar()) {
            'M' -> moveto(rel)
            'L' -> repeatCmd('L', 2, rel)
            'H' -> horizontal(rel)
            'V' -> vertical(rel)
            'C' -> repeatCmd('C', 6, rel)
            'S' -> repeatCmd('S', 4, rel)
            'Q' -> repeatCmd('Q', 4, rel)
            'T' -> repeatCmd('T', 2, rel)
            'A' -> arc(rel)
            'Z' -> {
                chain.add(PathCmd('Z', FloatArray(0)))
                px = mx; py = my
            }
            else -> throw fail("未知命令 '$c'")
        }
    }

    /** M/m:开新链;其后的裸坐标对为隐式 lineto(绝对性同 M)。 */
    private fun moveto(rel: Boolean) {
        finish()
        var g = readAbs(2, rel)
        chain.add(PathCmd('M', g))
        px = g[0]; py = g[1]; mx = px; my = py
        while (more()) {
            g = readAbs(2, rel)
            chain.add(PathCmd('L', g))
            px = g[0]; py = g[1]
        }
    }

    /** 收尾当前链并复位(每个 M 之前与解析结束调用)。 */
    private fun finish() {
        if (chain.isNotEmpty()) paths.add(chain)
        chain = mutableListOf()
    }

    /** L/C/S/Q/T:每 n 参数一组重复;相对组整体相对组首当前点转绝对。 */
    private fun repeatCmd(v: Char, n: Int, rel: Boolean) {
        if (!more()) throw fail("命令 '$v' 缺少坐标")
        while (more()) {
            val g = readAbs(n, rel)
            chain.add(PathCmd(v, g))
            px = g[n - 2]; py = g[n - 1]
        }
    }

    private fun horizontal(rel: Boolean) {
        if (!more()) throw fail("命令 'H' 缺少坐标")
        while (more()) {
            val a = num()
            px = if (rel) px + a else a
            chain.add(PathCmd('L', floatArrayOf(px, py)))
        }
    }

    private fun vertical(rel: Boolean) {
        if (!more()) throw fail("命令 'V' 缺少坐标")
        while (more()) {
            val a = num()
            py = if (rel) py + a else a
            chain.add(PathCmd('L', floatArrayOf(px, py)))
        }
    }

    /** A/a:每 7 参数一组(rx ry rot 大弧 扫掠 x y);标志单字符,可紧贴数字。 */
    private fun arc(rel: Boolean) {
        if (!more()) throw fail("命令 'A' 缺少坐标")
        while (more()) {
            val rx = num(); val ry = num(); val rot = num()
            val large = flag(); val sweep = flag()
            var ex = num(); var ey = num()
            if (rel) { ex += px; ey += py }
            chain.add(PathCmd('A', floatArrayOf(rx, ry, rot, large, sweep, ex, ey)))
            px = ex; py = ey
        }
    }

    /** 读 n 个数值;rel 时偶下标加 px、奇下标加 py。 */
    private fun readAbs(n: Int, rel: Boolean): FloatArray {
        val a = FloatArray(n) { num() }
        if (rel) for (k in 0 until n) a[k] += if (k % 2 == 0) px else py
        return a
    }

    private fun num(): Float {
        skipSep()
        return readNumber()
    }

    /** 弧标志:跳过分隔后只取单个 0/1 字符。 */
    private fun flag(): Float {
        skipSep()
        if (i >= d.length || (d[i] != '0' && d[i] != '1')) throw fail("期望弧标志 0/1")
        return (d[i++] - '0').toFloat()
    }

    /** 跳过分隔符(空白与逗号)。 */
    private fun skipSep() {
        while (i < d.length && (d[i] == ',' || d[i].isWhitespace())) i++
    }

    /** 当前命令后是否仍有参数(字母 = 新命令边界)。 */
    private fun more(): Boolean {
        skipSep()
        return i < d.length && !d[i].isLetter()
    }

    /** SVG number:可选符号 + 整数 + 可选小数 + 可选指数。 */
    private fun readNumber(): Float {
        val start = i
        if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
        var ints = 0
        while (i < d.length && d[i].isDigit()) { i++; ints++ }
        var frac = false
        if (i < d.length && d[i] == '.') {
            i++
            while (i < d.length && d[i].isDigit()) { i++; frac = true }
        }
        if (ints == 0 && !frac) throw fail("期望数字")
        if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
            i++
            if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
            val e0 = i
            while (i < d.length && d[i].isDigit()) i++
            if (i == e0) throw fail("非法指数")
        }
        return try {
            d.substring(start, i).toFloat()
        } catch (e: NumberFormatException) {
            throw fail("非法数值 '${d.substring(start, i)}'")
        }
    }

    /** 带位置的解析错误。 */
    private fun fail(msg: String): IllegalArgumentException {
        val from = maxOf(0, i - 12)
        val to = minOf(d.length, i + 12)
        return IllegalArgumentException("$msg (位置 $i,附近 '…${d.substring(from, to)}…')")
    }
}
