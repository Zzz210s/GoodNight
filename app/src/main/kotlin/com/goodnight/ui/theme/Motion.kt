package com.goodnight.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** D6:animator 缩放为 0 = 系统"关闭动画",所有动效退化为瞬时切换 */
fun animationsEnabledFor(animatorScale: Float): Boolean = animatorScale != 0f

/**
 * 尊重系统"关闭动画"(D6):animator 时长缩放为 0 时返回 false,
 * 本应用全部动效(morph/按压缩放/徽标弹跳)退化为瞬时切换。
 * 读一次后按组合生命周期记住;读取失败(如权限/平台差异)回落 true(动效开启)。
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
        } catch (_: Exception) {
            1f
        }
        animationsEnabledFor(scale)
    }
}

/**
 * 统一运动语言 token 集(spec D4):全应用动效曲线单源;初值见各属性,Task 8
 * 模拟器目检后仅回改本表。命名 -> 用途:
 * - PressSpring:可按压元素按压微缩回弹(沿用 v0.4 既有 0.6 阻尼手感)
 * - MorphSpring:图标路径形变进度(0->1)弹簧
 * - ReflowSpring:动作区重排位移动画(Task 8 接入)
 * - TextSwapEnter/Exit:状态文本交叉交换的进/出透明度曲线(出快于进,交叉不糊)
 * - StaggerMs:重排子项错峰间隔(毫秒)
 */
object MotionTokens {
    /** 按压微缩回弹(v0.4 pressScale 既有值) */
    val PressSpring: SpringSpec<Float> = spring(dampingRatio = 0.6f)

    /** 图标形变:中度欠阻尼 + 中低刚度(起跳带弹性、收敛不拖尾) */
    val MorphSpring: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    /** 动作区重排(Task 8 接入) */
    val ReflowSpring: SpringSpec<Float> = spring(dampingRatio = 0.55f)

    /** 状态文本交换:进 160ms */
    val TextSwapEnter: TweenSpec<Float> = tween(160)

    /** 状态文本交换:出 100ms */
    val TextSwapExit: TweenSpec<Float> = tween(100)

    /** 重排子项错峰间隔;20..80ms 为验收区间(MotionTokensTest 断言) */
    const val StaggerMs: Int = 40
}
/** 间距 token(v1.9.3 设计系统一致性):全局统一留白/间距,替代散落的字面 dp */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** 报表条形生长动画(v1.9.3 可视化增强):比例条从 0 -> 目标弹性展开 */
val BarAnim: SpringSpec<Float> = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
