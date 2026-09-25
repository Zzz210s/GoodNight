package com.goodnight.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.goodnight.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodnight.GoodNightApp
import com.goodnight.timer.DurationFormat
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon

/** 报表屏(主页汉堡进入):周报/月报/时钟累计 三页签 + 明细 + 各时钟合计 + 空态 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onBack: () -> Unit, initialRange: ReportRange = ReportRange.WEEK) {
    val app = LocalContext.current.applicationContext as GoodNightApp
    val vm: ReportViewModel = viewModel(factory = app.graph.vmFactory)
    val ui by vm.ui.collectAsStateWithLifecycle()
    // v1.1 顶栏汉堡/设置入口携带预选范围:VM 常驻 activity 级 store,每次进屏重放 setRange
    LaunchedEffect(initialRange) { vm.setRange(initialRange) }
    // 系统返回等同 Toolbar BACK:REPORT -> SETTINGS(pop),不结束 Activity
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { PathIcon(IconPaths.BACK, size = 24.dp, contentDescription = stringResource(R.string.report_back)) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tabs = listOf(ReportRange.WEEK to stringResource(R.string.tab_week), ReportRange.MONTH to stringResource(R.string.tab_month), ReportRange.LIFETIME to stringResource(R.string.tab_lifetime))
            SingleChoiceSegmentedButtonRow {
                // 等宽:各段 weight(1f) 均分整行,标签不再按内容宽窄参差
                tabs.forEachIndexed { index, (range, label) ->
                    SegmentedButton(
                        selected = ui.range == range,
                        onClick = { vm.setRange(range) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
            // v1.9.8 回顾以往报表:标签可点击弹下拉(搜索栏自由选择);两侧保留上一期/下一期
            if (ui.range != ReportRange.LIFETIME) {
                PeriodPicker(
                    range = ui.range,
                    anchor = ui.anchor,
                    canGoNext = ui.canGoNext,
                    minDate = ui.firstLaunch,
                    onPrev = { vm.prevPeriod() },
                    onNext = { vm.nextPeriod() },
                    onJump = { vm.jumpTo(it) },
                )
            }
            // v1.5 健康风摘要(周/月;长期累计页签无指标)
            if (ui.range != ReportRange.LIFETIME) {
                ui.metrics?.let { m ->
                    ReportSummary(
                        metrics = m,
                        slots = ui.timeSlots,
                        totalMillis = ui.rows.sumOf { it.millis },
                        isMonth = ui.range == ReportRange.MONTH,
                    )
                }
            }
            // 周/月/时钟累计内容直渲(曾包 AnimatedContent 时内容停留首帧旧 ui,直渲零状态依赖)
            if (ui.range == ReportRange.LIFETIME) {
                ui.metrics?.let { m ->
                    ReportSummary(
                        metrics = m,
                        slots = ui.timeSlots,
                        totalMillis = ui.profileTotals.sumOf { it.millis },
                        isMonth = false,
                        showAvg = false,
                    )
                }
                if (ui.profileTotals.isEmpty()) {
                    Text(
                        stringResource(R.string.empty_lifetime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    BarsList(
                        title = stringResource(R.string.total_lifetime),
                        rows = ui.profileTotals.map { it.profileName to (it.millis / 60_000) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.total_all) + "  " + localizedDur(ui.profileTotals.sumOf { it.millis }),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else if (ui.rows.isEmpty()) {
                Text(
                    stringResource(R.string.empty_period),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val trendRows = ui.rows.map { row ->
                    val label = if (row.weekIndex > 0)
                        stringResource(R.string.report_bucket_week, row.weekIndex, row.rangeFrom, row.rangeTo)
                    else row.label
                    label to (row.millis / 60_000)
                }
                BarsList(title = stringResource(R.string.metric_trend), rows = trendRows)
                if (ui.profileTotals.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    BarsList(
                        title = stringResource(R.string.total_window),
                        rows = ui.profileTotals.map { it.profileName to (it.millis / 60_000) },
                    )
                }
            }
            // v2.1 Task 8:按任务分解(追加在既有结构之后;时钟累计页签为空,窗口内无段也不渲染)
            if (ui.taskSlices.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TaskBreakdownSection(ui.taskSlices, resetKey = ui.range to ui.anchor)
            }
        }
    }
}


/** 报表时长本地化(v1.3 EN 对照):默认中文,en 设备出 "1h 30m";与 DurationFormat.hm 同语义(向上取整) */
@Composable
private fun durationLocalized(millis: Long): String {
    val totalMinutes = (millis + 59_999) / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h == 0L) stringResource(R.string.duration_m, m)
    else stringResource(R.string.duration_hm, h, m)
}
