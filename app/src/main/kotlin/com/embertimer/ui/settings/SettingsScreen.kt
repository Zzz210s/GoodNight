package com.embertimer.ui.settings

import com.embertimer.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.embertimer.diag.DiagnosticsSection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.embertimer.EmberApp
import com.embertimer.data.ReminderIntensity
import com.embertimer.ui.morph.IconPaths
import com.embertimer.ui.morph.PathIcon
import com.embertimer.ui.theme.ThemePack
import kotlinx.coroutines.launch

/**
 * 设置页(v1.9.13 分区化):精确闹钟横幅 / 外观(配色)/ 数据(备份·自动备份)/ 提醒。
 * 每区一个 SectionHeader,配色的色块每行 4 个(7 包折两行),消除堆叠与拥挤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as EmberApp
    val vm: SettingsViewModel = viewModel(factory = app.graph.vmFactory)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (onBackup, onRestore) = rememberBackupActions(vm)
    LaunchedEffect(Unit) { vm.refreshExactAlarm(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { PathIcon(IconPaths.BACK, size = 24.dp, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 精确闹钟
            if (ui.exactAlarmBlocked) {
                item {
                    SectionHeader(stringResource(R.string.alarm_banner_header))
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.exact_alarm_hint), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 31) {
                                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                                }
                            }) { Text(stringResource(R.string.go_grant)) }
                        }
                    }
                }
            }
            // 外观
            item {
                SectionHeader(stringResource(R.string.appearance_section))
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        ThemePack.entries.chunked(4).forEach { rowPacks ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowPacks.forEach { pack ->
                                    Swatch(
                                        name = stringResource(pack.labelRes),
                                        color = pack.primary,
                                        selected = ui.themePack == pack,
                                        onClick = { scope.launch { vm.setThemePack(pack) } },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 数据
            item {
                SectionHeader(stringResource(R.string.data_section))
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onBackup) { Text(stringResource(R.string.export_data)) }
                            OutlinedButton(onClick = onRestore) { Text(stringResource(R.string.import_data)) }
                        }
                        AutoBackupSection(vm)
                    }
                }
            }

            // 诊断(仅 debug 构建显示;正式版此区块不渲染)
            item {
                DiagnosticsSection(serviceAliveFlag = { com.embertimer.diag.DiagState.serviceAlive })
            }

            // 提醒强度
            item {
                SectionHeader(stringResource(R.string.reminder_intensity))
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        SingleChoiceSegmentedButtonRow {
                            ReminderIntensity.entries.forEachIndexed { index, intensity ->
                                SegmentedButton(
                                    selected = ui.intensity == intensity,
                                    onClick = { scope.launch { vm.setIntensity(intensity) } },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ReminderIntensity.entries.size,
                                    ),
                                ) {
                                    Text(
                                        when (intensity) {
                                            ReminderIntensity.LIGHT -> stringResource(R.string.intensity_light)
                                            ReminderIntensity.STANDARD -> stringResource(R.string.intensity_standard)
                                            ReminderIntensity.STRONG -> stringResource(R.string.intensity_strong)
                                        },
                                    )
                                }
                            }
                        }
                        // v1.13.0:提醒随系统静音/振动/响铃模式自动适配,强度只管振动节奏与铃声时长
                        Text(
                            stringResource(R.string.reminder_mode_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
