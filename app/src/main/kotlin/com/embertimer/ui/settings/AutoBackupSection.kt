package com.embertimer.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embertimer.EmberApp
import com.embertimer.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * v1.9.11 自动备份区块:开关 + 目标网盘目录选择 + 上次备份时间。
 * v1.11.0:授权失效时不再静默失败 —— 追加一行错误提示(仅在有错误时显示)。
 * (抽出为独立文件以保证 SettingsScreen ≤200 行)
 */
@Composable
internal fun AutoBackupSection(vm: SettingsViewModel) {
    val app = LocalContext.current.applicationContext as EmberApp
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = vm.ui.collectAsStateWithLifecycle()
    val backupLast = ui.value.backupLastAt
    val error = ui.value.backupError

    val pickDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) scope.launch { vm.setBackupUri(ctx, uri.toString()) }
    }

    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.autobackup_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = ui.value.autoBackup,
            onCheckedChange = { on -> vm.setAutoBackup(ctx, on) },
        )
    }
    if (ui.value.autoBackup) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDir.launch(null) }) {
                Text(stringResource(R.string.autobackup_pick_target))
            }
        }
        Text(
            if (ui.value.backupUri.isNullOrEmpty()) stringResource(R.string.autobackup_no_target)
            else stringResource(R.string.autobackup_target, ui.value.backupUri!!),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (backupLast > 0L) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        Text(
            stringResource(R.string.autobackup_last, fmt.format(Date(backupLast))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    // 仅失败时显示:提示用户重新选择备份目录(授权失效)或检查目录可写性(一般写入失败)
    if (error != null) {
        Text(
            stringResource(
                if (error == com.embertimer.data.BackupError.WRITE) R.string.backup_error_write
                else R.string.backup_error_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    // 引用 app 以保持与手动备份路径相同的 Application 断言(与既有实现一致)
    @Suppress("UNUSED_EXPRESSION")
    app
}
