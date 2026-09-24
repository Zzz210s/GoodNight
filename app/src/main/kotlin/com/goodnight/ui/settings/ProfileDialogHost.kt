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
                    // 仓库层按**最终状态**一条带条件的 UPDATE 写入:目标作用域重名就整行不动,
                    // 不会出现「已换归属、名字未改」的半成品 —— 这里只负责提示
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
        // 计数口径:按钮写**选中数**,归档分支首句也用选中数;正文里被门控扣下的那条、
        // 以及正被引擎占用的那条用附注说清,否则两个数字对不上像漏删
        val body = when {
            // 计划为空 = 选中的就是最后一个活跃时钟(有历史也不归档:归档同样让它从列表消失)
            plan.count == 0 -> stringResource(R.string.delete_keep_one)
            plan.anyArchive -> stringResource(
                R.string.delete_confirm_archive_body,
                plan.selectedCount, plan.archiveCount, plan.archiveMinutes,
                plan.count - plan.archiveCount,
            )
            else -> stringResource(R.string.delete_confirm_body, plan.count)
        }
        // 附注一:「已选 N 其中 M 会保留」解释按钮计数与执行数的差额
        // 附注二:正被引擎使用的时钟归档后计时不停(只在真的正被使用时出现)
        val notes = buildList {
            if (plan.count > 0 && plan.keptIds.isNotEmpty()) {
                add(stringResource(R.string.delete_keep_one_note, plan.selectedCount, plan.keptIds.size))
            }
            if (plan.anyInUse) add(stringResource(R.string.delete_confirm_in_use_note))
        }
        AlertDialog(
            onDismissRequest = { state.deletePlan = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(
                    if (notes.isEmpty()) body else (listOf(body) + notes).joinToString("\n"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = plan.clocks
                    state.deletePlan = null
                    scope.launch {
                        // 批量执行(判定 → 归档或真删)在 VM 里;单个失败不阻断其余
                        vm.deleteProfiles(targets)
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
