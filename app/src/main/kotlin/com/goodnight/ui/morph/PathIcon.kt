package com.goodnight.ui.morph

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** IconPaths 常量的逻辑栅格边长(全部 d 以 24x24 坐标系表达)。 */
internal const val GRID = 24f

/** 统一描边样式:2dp(24 栅格)等比,圆头线帽/圆角连接。宽度单位 px(路径已预缩放到画布)。 */
internal fun strokeStylePx(strokeWidthPx: Float?, drawWidthPx: Float): Stroke = Stroke(
    width = strokeWidthPx ?: drawWidthPx * (2f / GRID),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

/**
 * 静态图标绘制(spec D5):Kotlin 单源 path 圆头描边直绘,不经 painterResource。
 * [d] 为 24 栅格坐标;绘制前用 Matrix 预缩放路径到画布 px 并绕画布中心对齐
 * 24 栅格中心 (12,12),drawPath 零变换直绘。不用 DrawScope.scale——按钮容器内
 * 其 pivot 语义会使字形偏移到左上象限并越出画布(修复 2026-09-04:release 与
 * debug 实测三控制键图标挤在圆左上)。[strokeWidth] 单位 px,null 时随 size 等比
 * (24 栅格上 2dp)。contentDescription 为 null 时不挂语义(装饰性)。
 */
@Composable
fun PathIcon(
    d: String,
    size: Dp,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
    strokeWidth: Float? = null,
) {
    val rawPath = remember(d) { PathParser().parsePathString(d).toPath() }
    Spacer(
        modifier
            .size(size)
            .then(if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription })
            .drawWithCache {
                val w = this.size.width.toFloat()
                val s = w / GRID
                // 预缩放一次(路径局部空间成型),逐帧直绘零拷贝(v1.4.3 闪帧修复:静态图标不再每帧建 Path)
                val scaledIdle = Path().apply {
                    addPath(rawPath)
                    transform(
                        Matrix().apply {
                            translate(w / 2f - GRID / 2f * s, w / 2f - GRID / 2f * s)
                            scale(s, s)
                        },
                    )
                }
                onDrawBehind {
                    drawPath(scaledIdle, tint, style = strokeStylePx(strokeWidth, w))
                }
            },
    )
}
