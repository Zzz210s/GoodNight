package com.embertimer.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.embertimer.R
import kotlinx.coroutines.launch

/**
 * v1.10.1 备份/恢复动作(修复"点击备份无效果"):两个按钮都有明确反馈。
 * - 备份:已选目录 → 直接覆盖写固定文件并提示成功/失败;未选或授权失效 → 弹目录选择器,选后立即写。
 * - 恢复:选备份文件 → 合并入库并提示条数;文件无效 → 提示失败。
 */
@Composable
internal fun rememberBackupActions(vm: SettingsViewModel): Pair<() -> Unit, () -> Unit> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 选择备份目录(tree Uri):选后立即写一次(即"立即验证目录可用"),并记住供自动备份复用
    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching { vm.setBackupDir(ctx, uri.toString()) }.getOrDefault(false)
            Toast.makeText(
                ctx,
                ctx.getString(if (ok) R.string.backup_done else R.string.backup_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val count = vm.restoreFrom(uri)
            Toast.makeText(
                ctx,
                if (count != null) ctx.getString(R.string.data_imported, count)
                else ctx.getString(R.string.data_import_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    return Pair(
        {
            scope.launch {
                val ok = runCatching { vm.backupNow() }.getOrDefault(false)
                if (ok) {
                    Toast.makeText(ctx, ctx.getString(R.string.backup_done), Toast.LENGTH_SHORT).show()
                } else {
                    // 无目录 / 授权失效(如重装后 SAF 授权丢失)→ 引导重选目录,不再静默无反应
                    Toast.makeText(ctx, ctx.getString(R.string.backup_pick_dir), Toast.LENGTH_SHORT).show()
                    dirLauncher.launch(null)
                }
            }
        },
        { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
    )
}
