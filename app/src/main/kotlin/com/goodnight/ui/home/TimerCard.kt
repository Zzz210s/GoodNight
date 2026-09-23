package com.goodnight.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.goodnight.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.goodnight.data.db.ProfileMode
import com.goodnight.timer.DurationFormat
import com.goodnight.timer.Phase
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon
import com.goodnight.ui.tasks.TaskChip
import com.goodnight.ui.theme.MotionTokens
import com.goodnight.ui.theme.rememberAnimationsEnabled

/** 计时卡:阶段文案(D7 交叉交换)+ 大数字(倒计时剩余 / 正计时已走)+ 循环徽标 + 动作区(重排见 TimerActions.kt)
 *  countUp(运行快照或当前配置为正计时)时:无到期/循环概念 → 徽标隐藏;skip 引擎已 no-op → 键隐藏 */
@Composable
internal fun TimerCard(
    ui: HomeUiState,
    displayMillis: Long,
    taskTitle: String?,
    onTaskChipClick: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onGoSettings: () -> Unit,
) {
    val snap = ui.snap
    val empty = ui.profiles.isEmpty()
    val countUpActive = snap?.countUp == true ||
        ui.profiles.firstOrNull { it.id == ui.activeProfileId }?.mode == ProfileMode.COUNTUP
    val animationsOn = rememberAnimationsEnabled()
    Card(Modifier.fillMaxWidth()) {
        val phaseRes = when {
            snap == null -> R.string.state_idle
            snap.phase == Phase.WORK -> R.string.state_work
            else -> R.string.state_rest
        }
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // v2.1 Task 7(修复轮 2):顶部横带 = 当前任务 chip(左)+ [相位图标][循环徽标](右)。
            // chip 在**内容流内**,与居中的大数字分属两行 —— 重叠在结构上不可能发生,不再依赖
            // "chip 限宽 < 大数字左沿"的几何余量(该余量会被 360dp 屏宽、系统字号放大、
            // 8 字符大数字(ms 累计分钟无上限)分别吃掉)。weight(1f, fill = false) 与等分
            // Spacer 配对:名字再长也只占半行,超出由省略号截断,右侧徽标不被挤压。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TaskChip(
                    taskTitle = taskTitle,
                    enabled = snap != null,
                    onClick = onTaskChipClick,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                PhaseIndicator(phaseRes)
                if (!countUpActive) {
                    Spacer(Modifier.width(10.dp))
                    CycleBadge(count = snap?.cycleCount ?: 0, animationsOn = animationsOn)
                }
            }
            // #3 空态引导:无配置时倒计时数字位换文案(数字必为 00:00,无意义),
            // 开始键维持 disabled(activeProfileId == -1),另提供直达时钟管理的按钮
            // v1.1 #7:空态 ↔ 计时内容交叉淡入/展开;关闭动画直切(M1 门控)
            val emptyBody: @Composable (Boolean) -> Unit = { isEmpty ->
                // AnimatedContent 的 content 只应产出单子布局:多个子组合会落在内部 Box
                // 上互相重叠(曾致循环图标叠在倒计时数字左上)。各分支包居中 Column。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isEmpty) {
                        Text(stringResource(R.string.empty_guide), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onGoSettings) { Text(stringResource(R.string.go_new_clock)) }
                    } else {
                        Text(
                            DurationFormat.ms(displayMillis),
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }
                }
            }
            if (animationsOn) {
                AnimatedContent(
                    targetState = empty,
                    transitionSpec = {
                        val enterMs = MotionTokens.TextSwapEnter.durationMillis
                        val exitMs = MotionTokens.TextSwapExit.durationMillis
                        (fadeIn(tween(enterMs)) + expandVertically(tween(enterMs)))
                            .togetherWith(fadeOut(tween(exitMs)) + shrinkVertically(tween(exitMs)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "emptyState",
                ) { isEmpty -> emptyBody(isEmpty) }
            } else {
                emptyBody(empty)
            }
            val haptic = LocalHapticFeedback.current
            fun act(perform: () -> Unit) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                perform()
            }
            ActionZone(
                status = snap?.status,
                startEnabled = ui.ready && ui.activeProfileId != -1L,
                animationsOn = animationsOn,
                showSkip = !countUpActive,
                onStart = { act(onStart) },
                onPause = { act(onPause) },
                onResume = { act(onResume) },
                onSkip = { act(onSkip) },
                onStop = { act(onStop) },
            )
        }
    }
}


/** D4:循环徽标(仅图标);count 递增时 repeat 图标弹跳一次(scale 1->1.25->1,约 220ms),animationsOn=false 时无动画。
 * 数字由 PathIcon 的 contentDescription 承载(TalkBack 可读),不渲染可见文本。 */
@Composable
internal fun CycleBadge(count: Int, animationsOn: Boolean, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    var lastCount by remember { mutableIntStateOf(count) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        PathIcon(
            d = IconPaths.REPEAT,
            size = 16.dp,
            contentDescription = stringResource(R.string.cycle_n, count),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
    }
    LaunchedEffect(count) {
        if (count > lastCount) {
            if (animationsOn) {
                scale.snapTo(1.25f)
                scale.animateTo(1f, spring(dampingRatio = 0.5f))
            }
        }
        lastCount = count
    }
}
