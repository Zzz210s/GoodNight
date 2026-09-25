package com.goodnight.ui.tasks

import androidx.lifecycle.viewModelScope
import com.goodnight.R
import kotlinx.coroutines.launch

/**
 * 删除确认载荷:[minutes] = 该任务已记录的分钟数(四舍五入到整分)= 已落库段落 + **在途工作段**
 * (运行中删当前绑定任务时本段尚未落库,见 [inFlightMillisFor]);
 * [clocks] = 该任务专属时钟数(v2.2 Task 7 起确认框要讲清它们的去向)。
 */
data class TaskDeletePrompt(val id: Long, val title: String, val minutes: Long, val clocks: Int)

/**
 * 确认框正文资源:只有真有时钟会被转成通用时才追加那句(v2.2 Task 7)。
 * 抽成函数是为了让「有/无专属时钟」两条分支都能被单测钉住(TaskDialogs 本身是 Compose 树)。
 */
internal fun deleteConfirmTextRes(clocks: Int): Int =
    if (clocks > 0) R.string.task_delete_confirm_clocks else R.string.task_delete_confirm

/**
 * v2.1 Task 6 / v2.2 Task 4:任务删除与「已记录的 N 分钟」口径。
 *
 * 单独成文件只为 [TaskListViewModel] 守住 200 行约束(Task 4 又要给它加换时钟流程),
 * 语义上仍属任务页:状态由 VM 持有([TaskListViewModel.deletePromptState]),这里只放写入口。
 * 写成扩展函数后调用方语法不变(`vm.onDeleteRequest(id)` / `vm.recordedMinutes(id)`)。
 */

/** 弹删除确认:先取该任务已记录的分钟数(含在途,查询失败/任务已不在则不开弹窗) */
internal fun TaskListViewModel.onDeleteRequest(id: Long) {
    viewModelScope.launch {
        val title = graph.taskRepo.titleById(id) ?: return@launch
        // 时钟数一并取:删除事务会把该任务专属时钟转通用(TaskRepository.deleteTask),
        // 只在弹窗文案里讲清去向,不做任何预判/预改
        deletePromptState.value = TaskDeletePrompt(id, title, recordedMinutes(id), graph.profileRepo.countByTask(id))
    }
}

internal fun TaskListViewModel.onDeleteDismiss() { deletePromptState.value = null }

internal fun TaskListViewModel.onDeleteConfirmed() {
    val id = deletePromptState.value?.id
    deletePromptState.value = null
    if (id != null) onDelete(id)
}

/**
 * 删除:若运行态正绑着这个任务,必须先 [com.goodnight.timer.TimerEngine.setTask] 解绑 ——
 * 否则删除后结算会把已删 id 写进段落(孤儿引用)。库内引用由
 * [com.goodnight.data.TaskRepository.deleteTask] 同事务置空,时间账保留为未绑定。
 */
internal fun TaskListViewModel.onDelete(id: Long) {
    viewModelScope.launch {
        if (graph.engine.snapshot.value?.taskId == id) graph.engine.setTask(null)
        graph.taskRepo.deleteTask(id)
        graph.coordinator.notifier.refreshTaskTitle()
    }
}

/**
 * 该任务已记录的分钟数(四舍五入到整分):删除确认文案「已记录的 N 分钟」用。
 * = 已落库段落 + 在途工作段 —— 删除后两部分都以未绑定保留,口径必须一致。
 */
internal suspend fun TaskListViewModel.recordedMinutes(id: Long): Long =
    (graph.taskRepo.recordedMillis(id) + inFlightMillis(id) + 30_000L) / 60_000L

/** 在途工作毫秒:当前绑定就是 [id] 且引擎在 WORK 段时为该段归属本任务的时长,否则 0 */
internal fun TaskListViewModel.inFlightMillis(id: Long): Long =
    graph.engine.snapshot.value?.let { inFlightMillisFor(it, id, graph.time.now()) } ?: 0L
