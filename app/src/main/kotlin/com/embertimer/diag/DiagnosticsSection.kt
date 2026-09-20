package com.embertimer.diag

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.embertimer.service.TimerNotifications
import kotlinx.coroutines.delay

/** 诊断面板是否可用(仅 debuggable 构建) */
fun diagAvailable(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * v1.12.2 设置页「诊断(debug)」面板 —— 仅 debug 构建显示。
 *
 * 回答两个问题:
 * 1) **通知栏状态**:ID_NOTIFY 是否在栏上、是否 ongoing/前台服务、渠道与时间戳;
 * 2) **后台运行状态**:进程内服务是否存活(TimerService 存活标志)、前后台切换、闹钟/引擎阶段。
 *
 * 数据每秒刷新一次,可直接用 uiautomator 抓文本(不依赖 logcat)。
 */
@Composable
fun DiagnosticsSection(serviceAliveFlag: () -> Boolean) {
    val ctx = LocalContext.current
    if (!diagAvailable(ctx)) return

    var tick by remember { mutableLongStateOf(0L) }
    var appForeground by remember { mutableStateOf(DiagState.appForeground) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            appForeground = DiagState.appForeground
            delay(1_000)
        }
    }

    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    val active = remember(tick) {
        runCatching { nm?.activeNotifications?.firstOrNull { it.id == TimerNotifications.ID_NOTIFY } }.getOrNull()
    }
    val notifState = when {
        active == null -> "无(通知栏上没有我们的通知)"
        else -> buildString {
            append("已发布 id=${active.id} ongoing=${active.isOngoing} ")
            append("FGS=${if (active.notification.flags and 0x40 != 0) "是" else "否"} ")
            append("channel=${active.notification.channelId} ")
            append("when=${DiagLog.format(active.postTime)}")
        }
    }
    val proc = "应用内服务=${if (serviceAliveFlag()) "存活" else "未运行"},App=${if (appForeground) "前台" else "后台"}"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("诊断(debug)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { DiagLog.clear(); tick++ }) { Text("清空日志") }
            }
            Text("通知栏: $notifState", style = MaterialTheme.typography.bodySmall)
            Text("运行态: $proc", style = MaterialTheme.typography.bodySmall)
            Text("环境: ${DiagLog.env()}", style = MaterialTheme.typography.bodySmall)
            Text("日志文件: ${DiagLog.filePath() ?: "未启用"}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                DiagLog.recent(30).forEach { e ->
                    Text(
                        "${DiagLog.format(e.at)} [${e.tag}] ${e.text}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
