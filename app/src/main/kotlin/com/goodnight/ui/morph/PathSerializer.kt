package com.goodnight.ui.morph

import java.util.Locale

/**
 * 点云列表 -> SVG path d 折线串(绘制用,spec D6):每个点云输出
 * "M x,y L x,y L ...",坐标 2 位小数(固定小数点,US Locale);closed=true
 * 时每个点云末尾追加 " Z"。
 *
 * 闭合性限制(v0.5):MorphPlan 不携带逐子路径 closed 标志,故 closed 由渲染
 * 侧作为统一参数传入(渲染侧知道目标图标各子路径的闭合性,Task 7 传入)。
 * 全部子路径同闭合性的图标(PLAY 全闭、PAUSE 全开)无歧义;混合闭合性图标
 * (SKIP:开竖线 + 闭合三角)飞行中只能统一按同一标志渲染——目标为 SKIP 的
 * 形变飞行段其闭合形会以开路径绘制(缺 Z,视觉差一个接缝端点),落地即恢复
 * 正确闭合;v0.5 已知限制,后续可让计划携带逐对 closed 再消除。
 */
fun interpolatedPath(pts: List<FloatArray>, closed: Boolean): String {
    if (pts.isEmpty()) return ""
    val sb = StringBuilder(pts.sumOf { it.size } * 8)
    for (cloud in pts) {
        if (cloud.size < 2) continue
        sb.append("M ").append(f2(cloud[0])).append(',').append(f2(cloud[1]))
        var i = 2
        while (i + 1 < cloud.size) {
            sb.append(" L ").append(f2(cloud[i])).append(',').append(f2(cloud[i + 1]))
            i += 2
        }
        if (closed) sb.append(" Z")
    }
    return sb.toString()
}

/** 坐标 2 位小数;归一浮点负零("-0.00" -> "0.00")。 */
private fun f2(v: Float): String {
    val s = String.format(Locale.US, "%.2f", v)
    return if (s == "-0.00") "0.00" else s
}
