package com.goodnight.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodnight.data.TaskRepository
import com.goodnight.data.db.TaskEntity
import com.goodnight.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 新建/改名输入被拒的原因(文案在资源层,VM 只给原因,便于无资源单测) */
enum class TaskInputError { BLANK, TOO_LONG }

/**
 * 删除确认载荷:[minutes] = 该任务已记录的分钟数(四舍五入到整分)= 已落库段落 + **在途工作段**
 * (运行中删当前绑定任务时本段尚未落库,见 [inFlightMillisFor])。
 */
data class TaskDeletePrompt(val id: Long, val title: String, val minutes: Long)

/**
 * v2.1 Task 6:任务页 VM。列表读仓库的活跃/已完成两条流,写操作全部走 [TaskRepository] 事务。
 *
 * 写操作一律在协程内**从库里读现况**(不依赖被订阅的 StateFlow 当前值)——
 * `WhileSubscribed` 在无人订阅时 `.value` 还是初始空表,据此判完成态会翻反。
 */
class TaskListViewModel(private val graph: AppGraph) : ViewModel() {
    val active: StateFlow<List<TaskEntity>> = graph.taskRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val done: StateFlow<List<TaskEntity>> = graph.taskRepo.observeDone()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _inputError = MutableStateFlow<TaskInputError?>(null)
    val inputError: StateFlow<TaskInputError?> = _inputError.asStateFlow()

    private val _deletePrompt = MutableStateFlow<TaskDeletePrompt?>(null)
    val deletePrompt: StateFlow<TaskDeletePrompt?> = _deletePrompt.asStateFlow()

    /** 与 [TaskRepository.create] 同口径:去首尾空白后空为 BLANK、超长为 TOO_LONG */
    fun inputErrorFor(title: String): TaskInputError? = when {
        title.isBlank() -> TaskInputError.BLANK
        title.trim().length > TaskRepository.MAX_TITLE -> TaskInputError.TOO_LONG
        else -> null
    }

    fun clearInputError() { _inputError.value = null }

    fun onCreate(title: String) {
        viewModelScope.launch {
            val err = inputErrorFor(title)
            if (err != null) {
                _inputError.value = err
                return@launch
            }
            _inputError.value = null
            graph.taskRepo.create(title, graph.time.now())
        }
    }

    fun onRename(id: Long, title: String) {
        viewModelScope.launch {
            val err = inputErrorFor(title)
            if (err != null) {
                _inputError.value = err
                return@launch
            }
            _inputError.value = null
            graph.taskRepo.rename(id, title)
            graph.coordinator.notifier.refreshTaskTitle() // 通知里的任务名跟着改(缓存按 taskId 记)
        }
    }

    /** 完成勾选:进行中 <-> 已完成互移;[TaskRepository.setDone] 负责盖/清 doneAt */
    fun onToggleDone(id: Long) {
        viewModelScope.launch {
            val isDone = graph.taskRepo.doneOf(id) ?: return@launch
            graph.taskRepo.setDone(id, done = !isDone, now = graph.time.now())
        }
    }

    /** 拖动落点:[from]/[to] 为进行中列表下标;重排与 sortOrder 压实由仓库事务完成 */
    fun onMove(from: Int, to: Int) {
        viewModelScope.launch {
            val list = graph.taskRepo.observeActive().first()
            val task = list.getOrNull(from) ?: return@launch
            graph.taskRepo.moveTo(task.id, to, list)
        }
    }

    /** 弹删除确认:先取该任务已记录的分钟数(含在途,查询失败/任务已不在则不开弹窗) */
    fun onDeleteRequest(id: Long) {
        viewModelScope.launch {
            val title = graph.taskRepo.titleById(id) ?: return@launch
            _deletePrompt.value = TaskDeletePrompt(id, title, recordedMinutes(id))
        }
    }

    fun onDeleteDismiss() { _deletePrompt.value = null }

    fun onDeleteConfirmed() {
        val id = _deletePrompt.value?.id
        _deletePrompt.value = null
        if (id != null) onDelete(id)
    }

    /**
     * 删除:若运行态正绑着这个任务,必须先 [com.goodnight.timer.TimerEngine.setTask] 解绑 ——
     * 否则删除后结算会把已删 id 写进段落(孤儿引用)。库内引用由
     * [TaskRepository.deleteTask] 同事务置空,时间账保留为未绑定。
     */
    fun onDelete(id: Long) {
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
    suspend fun recordedMinutes(id: Long): Long =
        (graph.taskRepo.recordedMillis(id) + inFlightMillis(id) + 30_000L) / 60_000L

    /** 在途工作毫秒:当前绑定就是 [id] 且引擎在 WORK 段时为该段归属本任务的时长,否则 0 */
    fun inFlightMillis(id: Long): Long =
        graph.engine.snapshot.value?.let { inFlightMillisFor(it, id, graph.time.now()) } ?: 0L
}
