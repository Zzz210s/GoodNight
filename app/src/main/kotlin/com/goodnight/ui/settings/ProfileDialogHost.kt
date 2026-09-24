package com.goodnight.ui.settings

import com.goodnight.R
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.service.TimerCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 时钟管理页的对话框状态:哪个开着、编辑/新建选中的**归属**(null = 通用层)、
 * 仓库侧重名拒绝、以及待确认的删除计划(非空 = 确认框开着,内容就是将要执行的行)。
 */
@Stable
internal class ProfileDialogState {
    var editing by mutableStateOf<ProfileEntity?>(null)
    var creating by mutableStateOf(false)
    var scope by mutableStateOf<Long?>(null)
    var nameTaken by mutableStateOf(false)
    var deletePlan by mutableStateOf<DeletePlan?>(null)

    fun openEdit(p: ProfileEntity) {
        editing = p
        scope = p.taskId
        nameTaken = false
    }

    fun openCreate() {
        creating = true
        scope = null
        nameTaken = false
    }

    /** 关掉编辑/新建(保留删除确认框的状态不动:两条流程互不干扰) */
    fun closeEditing() {
        editing = null
        creating = false
        nameTaken = false
    }
}

/**
 * 时钟管理页的对话框宿主(编辑/新建/批量删除确认),与列表屏解耦保持各文件 <=200 行。
 *
 * 重名预校验只喂**目标作用域**的时钟(`ui.profiles.filter { it.taskId == state.scope }`,指针 2):
 * v2.2 起「不同任务下同名」合法,拿全库比会把合法输入挡在门外。
 */
@Composable
internal fun ProfileDialogHost(
    vm: SettingsViewModel,
    ui: SettingsUiState,
    state: ProfileDialogState,
    onDeleteModeExit: () -> Unit,
) {
    val ctx: Context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()
    val genericLabel = stringResource(R.string.clock_scope_generic)
    val scopes = remember(ui.tasks, genericLabel) {
        listOf(ClockScope(null, genericLabel)) + ui.tasks.map { ClockScope(it.id, it.title) }
    }

    state.editing?.let { p ->
        ProfileEditDialog(
            initial = p,
            existing = ui.profiles.filter { it.taskId == state.scope },
            title = ctx.getString(R.string.edit_clock),
            scopes = scopes,
            scopeTaskId = state.scope,
            onScopeChange = { state.scope = it; state.nameTaken = false },
            onNameChange = { state.nameTaken = false },
            error = if (state.nameTaken) stringResource(R.string.clock_name_taken) else null,
            onDismiss = { state.closeEditing() },
            onConfirm = { name, w, r, mode ->
                scope.launch {
                    // 改归属 + 改名两步都要过「作用域内唯一」:失败已在 VM 内回滚(搬迁不残留),
                    // 所以这里只负责提示 —— 不再出现「已换归属、名字未改」的半成品
                    if (!vm.commitScopeAndName(p, state.scope, name)) {
                        state.nameTaken = true
                        return@launch
                    }
                    if (vm.editDurations(p, w, r, mode)) {
                        TimerCommands.restartPhase(
                            ctx, p.id, w * 60_000L, r * 60_000L, mode == ProfileMode.COUNTUP,
                        )
                    }
                    state.closeEditing()
                }
            },
        )
    }
    if (state.creating) {
        ProfileEditDialog(
            initial = null,
            existing = ui.profiles.filter { it.taskId == state.scope },
            title = ctx.getString(R.string.new_clock),
            scopes = scopes,
            scopeTaskId = state.scope,
            onScopeChange = { state.scope = it; state.nameTaken = false },
            onNameChange = { state.nameTaken = false },
            error = if (state.nameTaken) stringResource(R.string.clock_name_taken) else null,
            onDismiss = { state.closeEditing() },
            onConfirm = { name, w, r, mode ->
                scope.launch {
                    val id = vm.createProfile(name, w, r, mode, state.scope)
                    if (id == null) {
                        state.nameTaken = true
                        return@launch
                    }
                    state.closeEditing()
                }
            },
        )
    }
    state.deletePlan?.let { plan ->
        // 确认框的计数按**真正会执行**的 [DeletePlan.count],而按钮写的是选中数:
        // 计划里被「至少保留 1 个活跃时钟」扣掉的那条必须解释清楚,否则两个数字对不上像漏删
        val body = when {
            // 计划为空 = 选中的就是最后一个活跃时钟(有历史也不归档:归档同样让它从列表消失)
            plan.count == 0 -> stringResource(R.string.delete_keep_one)
            plan.anyArchive -> stringResource(
                R.string.delete_confirm_archive_body,
                plan.count, plan.archiveCount, plan.archiveMinutes,
                plan.count - plan.archiveCount,
            )
            else -> stringResource(R.string.delete_confirm_body, plan.count)
        }
        // 被「至少保留 1 个活跃时钟」扣掉的那条:按钮写选中数、确认框按 plan.count,差额要解释
        // (计划为空时正文已说明,不再重复)
        val note = plan.keptIds.size.takeIf { plan.count > 0 && it > 0 }
        AlertDialog(
            onDismissRequest = { state.deletePlan = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(if (note == null) body else body + "\n" + stringResource(R.string.delete_keep_one_note, note))
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = plan.clocks
                    state.deletePlan = null
                    scope.launch {
                        // 单个失败不阻断其余删除(仓库层已保证失败不写库);批量执行在 VM 内,
                        // 返回 true = 被删的正是引擎当前认的时钟(含暂停态)→ 补一条 stop
                        if (vm.deleteProfiles(targets)) TimerCommands.stop(ctx)
                        onDeleteModeExit()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { state.deletePlan = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
