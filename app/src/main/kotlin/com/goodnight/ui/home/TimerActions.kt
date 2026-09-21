package com.goodnight.ui.home
import androidx.compose.ui.res.stringResource
import com.goodnight.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goodnight.timer.EngineStatus
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.MorphIcon
import com.goodnight.ui.morph.PathIcon
import com.goodnight.ui.theme.MotionTokens
import kotlinx.coroutines.delay

/** D6:运行态回空闲的反向时长 = 正向 token 的 0.6 倍(快速收拢,不拖沓) */
private const val ReverseFactor = 0.6f

/** D3:按压时图标微缩至 92%,释放弹簧回弹(曲线 = MotionTokens.PressSpring) */
private fun Modifier.pressScale(pressed: Boolean): Modifier = composed {
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, MotionTokens.PressSpring, label = "pressScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * D6 动作区重排:空闲「开始」播放图标键 ⇄ 运行态三键行(56dp 统一:暂停/恢复 · 终止 · 跳过,
 * 见 v1.0 #9/#11/#8)。容器 AnimatedContent 的键是 isIdle 而非 status —— RUNNING⇄PAUSED
 * 不触发容器过渡,MorphIcon 在原组合槽内就地形变、另两键原位静止。IDLE⇄运行态才做槽位交换:
 * 开始键上滑淡出(TextSwapExit 时长,与状态文本同族),三键行本体不挂容器入场,
 * 由各键 StaggerKey 自带错峰展开(终止 0ms、暂停/恢复 +40ms 原位、跳过 +80ms 自下滑入);
 * 反向(终止回空闲)以 0.6x 时长收拢。animationsOn=false 时 snap 直切。
 */
@Composable
internal fun ActionZone(
    status: EngineStatus?,
    startEnabled: Boolean,
    animationsOn: Boolean,
    showSkip: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    val isIdle = status == null || status == EngineStatus.IDLE
    AnimatedContent(
        targetState = isIdle,
        contentAlignment = Alignment.Center,
        transitionSpec = {
            val exitMs = MotionTokens.TextSwapExit.durationMillis
            val enterMs = MotionTokens.TextSwapEnter.durationMillis
            // animationsOn=false 等价 snap():进出均 None 且容器尺寸不补间,直接落定
            val spec = if (!animationsOn) {
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            } else if (targetState) { // 运行态 -> 空闲:反向 0.6x,行整体微缩淡出、开始钮自下滑入
                val reverseExitMs = (exitMs * ReverseFactor).toInt()
                (fadeIn(tween((enterMs * ReverseFactor).toInt())) +
                    slideInVertically(tween((enterMs * ReverseFactor).toInt())) { it / 8 })
                    .togetherWith(fadeOut(tween(reverseExitMs)) + scaleOut(tween(reverseExitMs), targetScale = 0.92f))
                    .using(SizeTransform(clip = false))
            } else { // 空闲 -> 运行态:开始钮上滑淡出;行入场由子键 StaggerKey 承载
                EnterTransition.None
                    .togetherWith(fadeOut(tween(exitMs)) + slideOutVertically(tween(exitMs)) { -it / 8 })
                    .using(SizeTransform(clip = false))
            }
            spec
        },
        label = "actionZone",
    ) { idle ->
        if (idle) {
            // #9 开始改图标键:PLAY 外观与暂停态「恢复」键同款,播放语义连贯
            FilledTonalIconButton(
                onClick = onStart,
                enabled = startEnabled,
                modifier = Modifier.size(56.dp),
            ) {
                PathIcon(d = IconPaths.PLAY, size = 24.dp, contentDescription = stringResource(R.string.act_start))
            }
        } else {
            ActiveKeys(status, animationsOn, showSkip, onPause, onResume, onSkip, onStop)
        }
    }
}

/** 运行态动作行(#11 均 56dp;顺序 #8 暂停/恢复 · 终止 · 跳过;正计时无 skip 语义 → 键不渲染) */
@Composable
private fun ActiveKeys(
    status: EngineStatus?,
    animationsOn: Boolean,
    showSkip: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    // RUNNING/PAUSED 须共用一个组合槽:morph 靠同一 MorphIcon 实例的 targetState
    // 翻转触发;若拆成两个 when 分支,状态切换会整枝替换、新图标直接落定,动效永不播
    val running = status == EngineStatus.RUNNING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 键序(v1.8.7 #3):停止 | 暂停/恢复 | 跳过
        StaggerKey(animationsOn, delaySteps = 0) {
            FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(56.dp)) {
                PathIcon(d = IconPaths.STOP, size = 24.dp, contentDescription = stringResource(R.string.act_stop))
            }
        }
        StaggerKey(animationsOn, delaySteps = 1, slide = 0.dp) { // 暂停/恢复
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            FilledIconToggleButton(
                checked = running,
                onCheckedChange = { if (running) onPause() else onResume() },
                modifier = Modifier.size(56.dp),
                interactionSource = interaction,
            ) {
                MorphIcon(
                    target = if (running) IconPaths.PAUSE else IconPaths.PLAY,
                    size = 24.dp,
                    animationsOn = animationsOn,
                    contentDescription = if (running) "暂停" else "恢复",
                    modifier = if (animationsOn) Modifier.pressScale(pressed) else Modifier,
                )
            }
        }
        if (showSkip) {
            StaggerKey(animationsOn, delaySteps = 2) {
                FilledTonalIconButton(onClick = onSkip, modifier = Modifier.size(56.dp)) {
                    PathIcon(d = IconPaths.SKIP, size = 24.dp, contentDescription = stringResource(R.string.act_skip))
                }
            }
        }
    }
}

/**
 * D6 错峰进入:delaySteps * StaggerMs 延迟后以 ReflowSpring 展开 scale 0.92 -> 1 并淡入;
 * [slide] 为自下滑入量(0 = 原位展开)。延迟走协程 delay(弹簧无 delayMillis 参数),
 * graphicsLayer 读 Animatable 状态 = 绘制期逐帧失效,零重组。animationsOn=false 时直落 1。
 */
@Composable
private fun StaggerKey(
    animationsOn: Boolean,
    delaySteps: Int,
    slide: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (animationsOn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animationsOn) return@LaunchedEffect
        delay(delaySteps * MotionTokens.StaggerMs.toLong())
        progress.animateTo(1f, MotionTokens.ReflowSpring)
    }
    Box(
        Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            val s = 0.92f + 0.08f * p
            scaleX = s
            scaleY = s
            translationY = (1f - p) * slide.toPx()
        },
    ) { content() }
}
