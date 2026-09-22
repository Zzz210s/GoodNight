package com.goodnight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.goodnight.service.ReportAlarmActions
import com.goodnight.ui.home.HomeScreen
import com.goodnight.ui.report.ReportRange
import com.goodnight.ui.report.ReportScreen
import com.goodnight.ui.settings.ProfilesScreen
import com.goodnight.ui.settings.SettingsScreen
import com.goodnight.ui.tasks.TaskScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import com.goodnight.ui.theme.MotionTokens
import com.goodnight.ui.theme.rememberAnimationsEnabled
import com.goodnight.ui.theme.EmberTheme

/** 无导航库:五屏手写状态切换(主页/设置/报表/时钟管理/任务,rememberSaveable 存 Int 序数) */
private enum class Screen { HOME, SETTINGS, REPORT, PROFILES, TASKS }

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 报表通知点开直达(v1.1 #5):cold start 经 onCreate 解析,热启动经 onNewIntent */
    private val pendingReportRange = mutableStateOf<ReportRange?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseReportExtra(intent)
    }

    private fun parseReportExtra(intent: Intent?) {
        when (intent?.getStringExtra(ReportAlarmActions.EXTRA_REPORT_RANGE)) {
            "week" -> pendingReportRange.value = ReportRange.WEEK
            "month" -> pendingReportRange.value = ReportRange.MONTH
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        com.goodnight.service.TimerNotifications.ensureChannels(this)
        // v1.9.13 #41:恢复常驻 —— app 启动即显示空闲常驻通知(计时开始后同 ID 替换)
        com.goodnight.service.TimerNotifIdle.showIdle(this)
        parseReportExtra(intent)
        setContent {
            val app = application as GoodNightApp
            // 配色包(设置页切换,持久化 DataStore)
            val themeFlow = androidx.compose.runtime.remember { app.graph.settingsRepo.themePack }
            val themePack by themeFlow.collectAsState(initial = com.goodnight.ui.theme.ThemePack.EMBER)
            EmberTheme(pack = themePack) {
                // 全屏底色垫底:切换过渡/透明层永不透出窗口白底(真机 edge-to-edge 闪白修复)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                var screenOrdinal by rememberSaveable { mutableStateOf(Screen.HOME.ordinal) }
                // 进程恢复兜底:enum 增删/重排后旧序数可能越界,回退主页
                val screen = Screen.entries.getOrNull(screenOrdinal) ?: Screen.HOME
                // v1.1 顶栏汉堡直达报表:携带预选 tab(周报/月报),与屏序数一并 rememberSaveable
                var reportRangeOrdinal by rememberSaveable { mutableStateOf(ReportRange.WEEK.ordinal) }
                val openReport: (ReportRange) -> Unit = { r ->
                    reportRangeOrdinal = r.ordinal
                    screenOrdinal = Screen.REPORT.ordinal
                }
                // 通知点开直达:每次值变化(冷启解析或 onNewIntent)触发一次跳转后清空
                LaunchedEffect(pendingReportRange.value) {
                    pendingReportRange.value?.let { r ->
                        pendingReportRange.value = null
                        openReport(r)
                    }
                }
                // v1.1 #7:三屏切换交叉过渡(进 160 出 100,轻上移);关闭动画直切。
                // 过渡窗两屏短暂共存,各自 VM 均为 activity 级,无重复副作用。
                val animationsOn = rememberAnimationsEnabled()
                val screenContent: @Composable () -> Unit = {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            onSettings = { screenOrdinal = Screen.SETTINGS.ordinal },
                            onOpenReport = openReport,
                            onManageProfiles = { screenOrdinal = Screen.PROFILES.ordinal },
                            onManageTasks = { screenOrdinal = Screen.TASKS.ordinal },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            onBack = { screenOrdinal = Screen.HOME.ordinal },
                        )
                        Screen.PROFILES -> ProfilesScreen(
                            onBack = { screenOrdinal = Screen.HOME.ordinal },
                        )
                        Screen.TASKS -> TaskScreen(
                            onBack = { screenOrdinal = Screen.HOME.ordinal },
                        )
                        Screen.REPORT -> ReportScreen(
                            onBack = { screenOrdinal = Screen.HOME.ordinal },
                            initialRange = ReportRange.entries.getOrElse(reportRangeOrdinal) { ReportRange.WEEK },
                        )
                    }
                }
                if (animationsOn) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            // v1.7:等时交叉淡化(两屏 alpha 恒相加=1,全程不透出底色)——
                            // 旧屏退场由新屏同步覆盖,无空白帧也无双透白跳;短时混合不闪烁
                            fadeIn(tween(160)).togetherWith(fadeOut(tween(160)))
                        },
                        label = "screenSwap",
                    ) { screenContent() }
                } else {
                    screenContent()
                }
                // v1.3 #1:手势/三键返回 = 子屏回主页,主页最小化到后台(不退出)。
                // 系统真正销毁(最近任务上滑/系统回收)才结束进程。
                BackHandler {
                    if (screen == Screen.HOME) moveTaskToBack(true)
                    else screenOrdinal = Screen.HOME.ordinal
                }
                }
            }
        }
    }
}
