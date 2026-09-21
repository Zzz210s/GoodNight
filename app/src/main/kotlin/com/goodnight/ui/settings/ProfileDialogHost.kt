package com.goodnight.ui.settings

import com.goodnight.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.goodnight.data.db.ProfileEntity
import com.goodnight.service.TimerCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 时钟管理页的对话框宿主(编辑/新建/批量删除确认),与列表屏解耦保持各文件 <=200 行 */
@Composable
internal fun ProfileDialogHost(
    vm: SettingsViewModel,
    editing: ProfileEntity?,
    onEditChange: (ProfileEntity?) -> Unit,
    creating: Boolean,
    onCreateChange: (Boolean) -> Unit,
    confirmDelete: Boolean,
    onConfirmDeleteChange: (Boolean) -> Unit,
    deleteMode: Boolean,
    onDeleteModeExit: () -> Unit,
    runningActiveId: Long?,
    selectedIds: Set<Long>,
    profiles: List<ProfileEntity>,
) {
    val ctx: Context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()

    editing?.let { p ->
        ProfileEditDialog(
            initial = p,
            existing = profiles,
            title = ctx.getString(R.string.edit_clock),
            onDismiss = { onEditChange(null) },
            onConfirm = { name, w, r, mode ->
                scope.launch {
                    try {
                        if (name != p.name) vm.renameProfile(p.id, name)
                        if (vm.editDurations(p, w, r, mode)) {
                            TimerCommands.restartPhase(ctx, p.id, w * 60_000L, r * 60_000L, mode == com.goodnight.data.db.ProfileMode.COUNTUP)
                        }
                        onEditChange(null)
                    } catch (_: SQLiteConstraintException) {
                        // 预验证后不应到达(极端并发兜底):对话框保持开启由用户改名
                    }
                }
            },
        )
    }
    if (creating) {
        ProfileEditDialog(
            initial = null,
            existing = profiles,
            title = ctx.getString(R.string.new_clock),
            onDismiss = { onCreateChange(false) },
            onConfirm = { name, w, r, mode ->
                scope.launch {
                    vm.createProfile(name, w, r, mode)
                    onCreateChange(false)
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { onConfirmDeleteChange(false) },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    onConfirmDeleteChange(false)
                    scope.launch {
                        // v1.9.12 修复删除模式无法真正删除:全选时不再静默跳过整个删除,
                        // 而是按“至少保留 1 个时钟”规则删到剩 1(修复前:targets.size == profiles.size
                        // 直接 return,用户确认了却什么都没删);单个删除加 try-catch 防协程中断。
                        val targets = profiles.filter { it.id in selectedIds }
                        val deletable = if (targets.size >= profiles.size) targets.dropLast(1) else targets
                        deletable.forEach { p ->
                            try {
                                if (vm.deleteProfile(p)) {
                                    if (runningActiveId == p.id) TimerCommands.stop(ctx)
                                }
                            } catch (_: Exception) {
                                // 单个失败不阻断其余删除
                            }
                        }
                        onDeleteModeExit()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { onConfirmDeleteChange(false) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
