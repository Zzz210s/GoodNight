package com.goodnight.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodnight.GoodNightApp
import com.goodnight.R
import com.goodnight.data.db.TaskEntity
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon

/**
 * 两段列表共处同一个 LazyColumn 的 key 空间:同一 id 勾选完成时,「进行中」与「已完成」是两条
 * 独立 Room 流,两帧之间同一个 id 可能同时在两条流里 —— 不带段前缀就会撞 key,
 * Compose 直接抛 `Key "..." was already used` 硬崩。段前缀从构造上保证 key 全局唯一。
 */
internal fun activeRowKey(id: Long): String = "a$id"

internal fun doneRowKey(id: Long): String = "d$id"

/**
 * v2.1 Task 6 任务页:进行中(长按拖动排序)+ 已完成(按完成时间倒序,不参与拖动)。
 * 行内勾选完成/取消完成,点卡片改名,垃圾桶删除(确认文案含已记录分钟数)。
 * 视觉沿用既有页面:Scaffold + TopAppBar + 卡片列表(见 ProfilesScreen)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GoodNightApp
    val vm: TaskListViewModel = viewModel(factory = app.graph.vmFactory)
    val active by vm.active.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val inputError by vm.inputError.collectAsStateWithLifecycle()
    val prompt by vm.deletePrompt.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TaskEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PathIcon(IconPaths.BACK, size = 24.dp, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearInputError(); creating = true }) {
                        PathIcon(IconPaths.PLUS, size = 24.dp, contentDescription = stringResource(R.string.task_new))
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(TaskRowSpacing),
        ) {
            item { SectionLabel(stringResource(R.string.tasks_active)) }
            itemsIndexed(active, key = { _, t -> activeRowKey(t.id) }) { index, task ->
                ActiveTaskRow(
                    task = task,
                    index = index,
                    count = active.size,
                    onMove = vm::onMove,
                    onToggle = { vm.onToggleDone(task.id) },
                    onRename = { vm.clearInputError(); renaming = task },
                    onDelete = { vm.onDeleteRequest(task.id) },
                )
            }
            if (active.isEmpty()) item { EmptyHint(stringResource(R.string.tasks_empty_active)) }

            item { SectionLabel(stringResource(R.string.tasks_done)) }
            items(done, key = { doneRowKey(it.id) }) { task ->
                DoneTaskRow(
                    task = task,
                    onToggle = { vm.onToggleDone(task.id) },
                    onDelete = { vm.onDeleteRequest(task.id) },
                )
            }
            if (done.isEmpty()) item { EmptyHint(stringResource(R.string.tasks_empty_done)) }
        }
    }

    if (creating) {
        TaskInputDialog(
            titleRes = R.string.task_new,
            initial = "",
            error = inputError,
            onClearError = vm::clearInputError,
            onDismiss = { creating = false; vm.clearInputError() },
            onConfirm = { text ->
                vm.onCreate(text)
                if (vm.inputErrorFor(text) == null) creating = false
            },
        )
    }
    renaming?.let { task ->
        TaskInputDialog(
            titleRes = R.string.task_rename,
            initial = task.title,
            error = inputError,
            onClearError = vm::clearInputError,
            onDismiss = { renaming = null; vm.clearInputError() },
            onConfirm = { text ->
                vm.onRename(task.id, text)
                if (vm.inputErrorFor(text) == null) renaming = null
            },
        )
    }
    prompt?.let { p ->
        TaskDeleteDialog(prompt = p, onConfirm = vm::onDeleteConfirmed, onDismiss = vm::onDeleteDismiss)
    }
}
