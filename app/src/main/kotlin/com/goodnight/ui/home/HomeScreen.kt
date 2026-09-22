package com.goodnight.ui.home

import com.goodnight.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodnight.GoodNightApp
import com.goodnight.data.db.ProfileMode
import com.goodnight.service.ServiceLauncher
import com.goodnight.service.TimerCommands
import com.goodnight.timer.DurationFormat
import com.goodnight.timer.EngineStatus
import com.goodnight.ui.heatmap.Heatmap
import com.goodnight.ui.heatmap.buildHeatmapModel
import com.goodnight.ui.report.ReportRange
import com.goodnight.ui.theme.MotionTokens
import com.goodnight.ui.theme.rememberAnimationsEnabled
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onSettings: () -> Unit,
    onOpenReport: (ReportRange) -> Unit,
    onManageProfiles: () -> Unit,
    onManageTasks: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as GoodNightApp
    val vm: HomeViewModel = viewModel(factory = app.graph.vmFactory)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val dayDetail by vm.dayDetail.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Task 7 / #10:数字位 = 倒计时剩余 / 正计时已走(计到快照,暂停定格)。
    // countUp elapsed 由 accruedWork 换算(phase 恒 WORK;倒计时分支保持既有 remaining 语义)
    var displayMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(
        ui.snap?.status, ui.snap?.endElapsed, ui.snap?.timeAtPause,
        ui.snap?.startElapsed, ui.snap?.countUp,
    ) {
        while (true) {
            displayMillis = ui.snap?.let { s ->
                when {
                    s.countUp -> s.accruedWork(vm.time.elapsedRealtime())
                    s.status == EngineStatus.RUNNING -> s.remaining(vm.time.elapsedRealtime())
                    s.status == EngineStatus.PAUSED -> s.timeAtPause
                    else -> 0L
                }
            } ?: 0L
            delay(250)
        }
    }

    // 验收修复(用例 2):force-stop 会清除应用闹钟且 stopped state 拦截广播,重开应用后
    // 若无人在跑,UI 只回显引擎快照会冻结在 00:00。快照非空时确保服务在跑:无 action
    // 启动 -> 服务对账(过期推进落账/活跃重武装/空闲自停),幂等;服务已在跑时仅重复前台化。
    // key 为存在性布尔:阶段推进不重复触发,仅 null->非null/首帧带快照时启动一次。
    LaunchedEffect(ui.snap != null) {
        if (ui.snap != null) ServiceLauncher.ensureServiceRunning(ctx)
    }

    // v1.2 #1:主页改为布局流 = 顶栏(内置可展开全宽面板)+ 内容区(weight)。
    // 面板在顶栏内部占位展开/收起,内容区随之下移/回弹;底部留导航条安全区。
    Column(Modifier.fillMaxWidth()) {
        HomeTopBar(
            ui = ui,
            onSelectProfile = { p ->
                scope.launch {
                    if (vm.selectProfile(p)) {
                        TimerCommands.restartPhase(
                            ctx, p.id, p.workMinutes * 60_000L, p.restMinutes * 60_000L,
                            countUp = p.mode == ProfileMode.COUNTUP,
                        )
                    }
                }
            },
            onSettings = onSettings,
            onManageProfiles = onManageProfiles,
            onManageTasks = onManageTasks,
            onOpenReport = onOpenReport,
        )
        Column(
            Modifier.weight(1f).navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimerCard(ui, displayMillis, onStart = {
                ui.profiles.firstOrNull { it.id == ui.activeProfileId }?.let { p ->
                    TimerCommands.start(
                        ctx, p.id, p.workMinutes * 60_000L, p.restMinutes * 60_000L,
                        countUp = p.mode == ProfileMode.COUNTUP,
                    )
                }
            }, onPause = { TimerCommands.pause(ctx) }, onResume = { TimerCommands.resume(ctx) },
                onSkip = { TimerCommands.skip(ctx) }, onStop = { TimerCommands.stop(ctx) },
                onGoSettings = onManageProfiles)
            // v1.1 #7:今日合计落账变化时滑切(落账频次低,不打扰);关闭动画直切
            val todayText = stringResource(R.string.today_total, localizedDuration(ui.todayMillis))
            val animationsOn = rememberAnimationsEnabled()
            if (animationsOn) {
                AnimatedContent(
                    targetState = todayText,
                    transitionSpec = {
                        (fadeIn(tween(MotionTokens.TextSwapEnter.durationMillis)) +
                            slideInVertically(tween(MotionTokens.TextSwapEnter.durationMillis)) { it / 3 })
                            .togetherWith(
                                fadeOut(tween(MotionTokens.TextSwapExit.durationMillis)) +
                                    slideOutVertically(tween(MotionTokens.TextSwapExit.durationMillis)) { -it / 3 },
                            )
                    },
                    label = "todayTotalSwap",
                ) { t -> Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text(todayText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card {
                Column(Modifier.padding(12.dp)) {
                    // R8:模型只依赖 ui.days,remember 键控在 days 上 —— remaining 每 250ms 刷新
                    // 触发整棵重组,若每次内联重建会每秒 4 次重算全历史格子模型
                    val heatmapModel = remember(ui.days) { buildHeatmapModel(ui.days, LocalDate.now()) }
                    Heatmap(heatmapModel, selectedDay) { vm.selectDay(it) }
                    DayDetailCard(dayDetail)
                }
            }

    }
    }
}

@Composable
private fun localizedDuration(millis: Long): String {
    val totalMinutes = (millis + 59_999) / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h == 0L) stringResource(R.string.duration_m, m)
    else stringResource(R.string.duration_hm, h, m)
}
