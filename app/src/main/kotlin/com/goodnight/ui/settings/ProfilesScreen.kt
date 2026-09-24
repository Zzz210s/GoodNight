package com.goodnight.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodnight.GoodNightApp
import com.goodnight.R
import com.goodnight.timer.EngineStatus
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon
import kotlinx.coroutines.launch

/**
 * 时钟管理:列表分「通用 / 任务专属(按任务分组)」两段(v2.2 Task 5);点卡片编辑(含改归属);
 * 垃圾桶进删除模式(勾选 + 底部删除选中);运行中时钟不可点。
 *
 * 删除按设计 §4 分两种结局:有历史 → 归档(行保留、列表隐藏、账不动),无历史 → 真删;
 * 确认框先把「保留多少分钟」说清楚(见 [ProfileDialogHost])。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GoodNightApp
    val vm: SettingsViewModel = viewModel(factory = app.graph.vmFactory)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = remember { ProfileDialogState() }
    var deleteMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    /**
     * 列表门控 = **运行中**的时钟不可点(编辑/勾选都不行);暂停中仍可编辑(见 EnginePolicy)。
     * 删除后要不要补 stop 不在这里判 —— 那是 [SettingsViewModel.deleteProfiles] 的职责
     * (RUNNING 与 PAUSED 都算,见 [shouldStopAfterDelete])。
     */
    val runningActiveId = if (ui.snap?.status == EngineStatus.RUNNING) ui.snap?.profileId else null
    BackHandler(enabled = deleteMode) { deleteMode = false; selectedIds = emptySet() }

    /**
     * 删除预告需要「已记录多少分钟」——会话段优先、缺段回落到每日合计(与 [planDeletion]
     * 同一条口径),所以查库后才弹确认框。
     */
    fun requestDelete() {
        scope.launch {
            val sessions = app.graph.profileRepo.sessionMinutes()
            val targets = ui.profiles.filter { it.id in selectedIds }
            state.deletePlan = planDeletion(targets, ui.profiles.size, sessions, ui.totals)
        }
    }

    fun toggleSelect(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clocks_manage)) },
                navigationIcon = {
                    IconButton(onClick = if (deleteMode) { { deleteMode = false; selectedIds = emptySet() } } else onBack) {
                        PathIcon(IconPaths.BACK, size = 24.dp, contentDescription = stringResource(if (deleteMode) R.string.exit_delete else R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        deleteMode = !deleteMode
                        selectedIds = emptySet()
                    }) {
                        PathIcon(
                            IconPaths.TRASH, size = 24.dp,
                            contentDescription = stringResource(R.string.delete_manage),
                            tint = if (deleteMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!deleteMode) {
                        IconButton(onClick = { state.openCreate() }) {
                            PathIcon(IconPaths.PLUS, size = 24.dp, contentDescription = stringResource(R.string.new_clock))
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                clockSectionItems(
                    sections = ui.sections,
                    deleteMode = deleteMode,
                    selectedIds = selectedIds,
                    runningActiveId = runningActiveId,
                    onEdit = { state.openEdit(it) },
                    onToggle = { toggleSelect(it) },
                )
                if (ui.profiles.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.empty_clocks),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (deleteMode) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { deleteMode = false; selectedIds = emptySet() }) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = { requestDelete() },
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Text(stringResource(R.string.delete_selected_n, selectedIds.size), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    ProfileDialogHost(
        vm = vm,
        ui = ui,
        state = state,
        onDeleteModeExit = { deleteMode = false; selectedIds = emptySet() },
    )
}
