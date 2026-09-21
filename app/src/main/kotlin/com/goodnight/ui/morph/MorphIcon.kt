package com.goodnight.ui.morph

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goodnight.ui.theme.MotionTokens

/**
 * 动态形变图标(spec D3,旗舰集成 = TimerCard 播放/暂停钮):target path 变化时
 * 以 MorphEngine.plan(上一目标, 新目标) 建计划,Animatable 进度 0->1 沿
 * MorphSpring 驱动;绘制期逐帧把 interpolate 点云直接构建 android Path
 * (moveTo/lineTo,不走 d 字符串往返——interpolatedPath 仅供调试/测试)。
 *
 * 打断(v0.5 简化,绑定约定):飞行中 target 再变 -> 从"上一目标典型形状"
 * (非当前中间形态)重建计划,snapTo(0) 重启;快速连击有视觉跳变,弹簧重启
 * 掩盖。不做中途再规划。
 *
 * 闭合性(v0.5 简化,Task 6 已知限制):计划不携带逐子路径 closed,飞行段按
 * 目标图标统一标志——全闭(PLAY/STOP)画闭合,全开/混合(PAUSE/SKIP)画开。
 *
 * animationsOn=false 或首帧组合:直绘目标典型形状。
 * 缩放:不用 DrawScope.scale(pivot 在按钮容器内偏移字形),改 Matrix 预缩放
 * 路径绕画布中心对齐 24 栅格中心,drawPath 零变换直绘(同 PathIcon 修复)。
 */
@Composable
fun MorphIcon(
    target: String,
    size: Dp,
    animationsOn: Boolean,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
    strokeWidth: Float? = null,
) {
    var current by remember { mutableStateOf(target) }
    var flight by remember { mutableStateOf<MorphPlan?>(null) }
    val progress = remember { Animatable(1f) }

    // 飞行完成才提交 current;被打断(新 target)时 current 仍是上一目标,
    // 新计划自然从上一目标典型形状重建(见类 KDoc 打断语义)。旧协程在
    // animateTo 处被取消,flight 可能滞留非空:target 折返(==current)的
    // 早退分支必须清除滞留 flight,否则冻结的中间形态被永久绘制(评审 R1)。
    LaunchedEffect(target) {
        if (target == current) { flight = null; return@LaunchedEffect }
        if (!animationsOn) {
            current = target
            return@LaunchedEffect
        }
        flight = MorphEngine.plan(current, target)
        progress.snapTo(0f)
        progress.animateTo(1f, MotionTokens.MorphSpring)
        current = target
        flight = null
    }

    // animationsOn 关闭时无视飞行状态直绘 target;否则飞行外绘已落定的 current
    val idleD = if (animationsOn) current else target
    val idlePath = remember(idleD) { PathParser().parsePathString(idleD).toPath() }
    val closed = remember(target) { allSubpathsClosed(target) }
    Spacer(
        modifier
            .size(size)
            .then(if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription })
            .drawWithCache {
                val w = this.size.width.toFloat()
                val s = w / GRID
                // 画布中心对齐 24 栅格中心 (12,12) 的预缩放矩阵(路径局部空间一次成型)
                val m = Matrix().apply {
                    translate(w / 2f - GRID / 2f * s, w / 2f - GRID / 2f * s)
                    scale(s, s)
                }
                val style = strokeStylePx(strokeWidth, w)
                // 静止路径预缩放一次(共享实例复用,不再每帧建 Path 拷贝 —— v1.4.3 闪帧修复)
                val scaledIdle = Path().apply {
                    addPath(idlePath)
                    transform(m)
                }
                onDrawBehind {
                    val f = flight
                    val p: Path = if (animationsOn && f != null) {
                        // 飞行中:点云(24 栅格)构 Path 后套同一矩阵(每帧新对象,就地变换安全)
                        buildPath(interpolate(f, progress.value), closed).apply { transform(m) }
                    } else {
                        scaledIdle
                    }
                    drawPath(p, tint, style = style)
                }
            },
    )
}

/** 点云列表 -> android Path(直建 M/L,无 d 往返);closed 时逐子路径闭合。 */
private fun buildPath(clouds: List<FloatArray>, closed: Boolean): Path {
    val p = Path()
    for (c in clouds) {
        if (c.size < 4) continue
        p.moveTo(c[0], c[1])
        var i = 2
        while (i + 1 < c.size) {
            p.lineTo(c[i], c[i + 1])
            i += 2
        }
        if (closed) p.close()
    }
    return p
}

/** 目标全部子路径闭合才飞行闭合;全开或混合(SKIP)统一画开。 */
private fun allSubpathsClosed(d: String): Boolean {
    val subs = normalize(parsePathData(d))
    return subs.isNotEmpty() && subs.all { it.closed }
}
