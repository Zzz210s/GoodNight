package com.goodnight.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodnight.data.TaskRepository
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.data.db.TaskEntity
import com.goodnight.di.AppGraph
import com.goodnight.service.TimerCommands
import com.goodnight.timer.EngineStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 新建/改名输入被拒的原因(文案在资源层,VM 只给原因,便于无资源单测) */
enum class TaskInputError { BLANK, TOO_LONG }

/**
 * v2.1 Task 6:任务页 VM。列表读仓库的活跃/已完成两条流,写操作全部走 [TaskRepository] 事务。
 *
 * 写操作一律在协程内**从库里读现况**(不依赖被订阅的 StateFlow 当前值)——
 * `WhileSubscribed` 在无人订阅时 `.value` 还是初始空表,据此判完成态会翻反。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskListViewModel(internal val graph: AppGraph) : ViewModel() {
    val active: StateFlow<List<TaskEntity>> = graph.taskRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val done: StateFlow<List<TaskEntity>> = graph.taskRepo.observeDone()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _inputError = MutableStateFlow<TaskInputError?>(null)
    val inputError: StateFlow<TaskInputError?> = _inputError.asStateFlow()

    internal val deletePromptState = MutableStateFlow<TaskDeletePrompt?>(null)

    /** 删除确认载荷(读写入口见 [onDeleteRequest] / [onDeleteConfirmed],同文件 TaskDeleteActions.kt) */
    val deletePrompt: StateFlow<TaskDeletePrompt?> = deletePromptState.asStateFlow()

    // ---- v2.2 Task 3:任务卡片上的时钟 ----

    /**
     * 每张卡片的可用时钟 = 该任务专属 + 全部通用(排除归档),按进行中任务 id 建索引。
     * 只覆盖进行中任务:已完成的任务不再有卡片(与首页任务选择器「只列进行中」同口径)。
     */
    val clocksByTask: StateFlow<Map<Long, TaskClocks>> = active
        .flatMapLatest { tasks ->
            if (tasks.isEmpty()) flowOf(emptyMap())
            else combine(tasks.map { t -> graph.profileRepo.availableFor(t.id).map { t.id to splitClocks(it) } }) { rows ->
                rows.toMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _addClockTaskId = MutableStateFlow<Long?>(null)

    /** 「+ 添加时钟」弹窗状态:非空 = 正在给这个任务新建时钟 */
    val addClockTaskId: StateFlow<Long?> = _addClockTaskId.asStateFlow()

    private val _clockError = MutableStateFlow<TaskClockError?>(null)

    /** 新建被仓库拒绝的原因(重名预校验的数据源瞬时为空时会走到这里,必须给用户提示) */
    val clockError: StateFlow<TaskClockError?> = _clockError.asStateFlow()

    fun onAddClockRequest(taskId: Long) { _addClockTaskId.value = taskId; _clockError.value = null }

    fun onAddClockDismiss() { _addClockTaskId.value = null; _clockError.value = null }

    /** 在该任务下新建时钟(作用域内唯一由仓库保证)。仓库拒绝时不关弹窗**并给出原因** ——
     * 关掉的话这次输入就静默丢了。 */
    fun onCreateClock(taskId: Long, name: String, workMinutes: Int, restMinutes: Int, mode: Int) {
        viewModelScope.launch {
            val id = graph.profileRepo.create(name, workMinutes, restMinutes, mode, taskId)
            _clockError.value = if (id == null) TaskClockError.NAME_TAKEN else null
            if (id != null) _addClockTaskId.value = null
        }
    }

    // ---- v2.2 Task 4:计时中换时钟的确认闸门(与计时卡共用同一份实现) ----

    private val clockSwitch = ClockSwitchPrompt(graph)

    /** 非空 = 弹「终止当前并开始新的?」确认;**未确认前不发任何命令** */
    val pendingClockSwitch: StateFlow<PendingClockSwitch?> get() = clockSwitch.pending

    fun onConfirmClockSwitch() = clockSwitch.confirm()

    fun onDismissClockSwitch() = clockSwitch.dismiss()

    /**
     * 点卡片上的时钟 chip:空闲 = 起画(任务由卡片决定,时钟搭在同一条 START 上);
     * 已有会话(运行/暂停)= **先确认** —— 静默换时钟会让 45/15 与 25/5 混在同一段里;
     * 确认后发一条换时钟命令,新会话绑定**这张卡片的任务**。
     * `engine.ready` 未就绪一律 no-op(由 [ClockSwitchPrompt] 把关):冷启动 restore() 前快照为空,
     * 放行会覆盖尚未恢复的运行快照(`engine.start` -> `save()`)并丢掉本段未落账时间。
     */
    fun onStartClock(taskId: Long, clock: ProfileEntity) {
        clockSwitch.pickForTask(clock, taskId) { startClock(clock, taskId) }
    }

    private fun startClock(clock: ProfileEntity, taskId: Long) {
        TimerCommands.start(
            graph.appContext, clock.id, clock.workMinutes * 60_000L, clock.restMinutes * 60_000L,
            countUp = clock.mode == ProfileMode.COUNTUP, taskId = taskId,
        )
        // 指针 1:启动即镜像写回首页高亮的时钟(顶栏的读取点在 HomeTopBar/HomePanel,属 Task 5)
        clockSwitch.mirror(clock.id)
    }

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
            graph.coordinator.notifier.refreshNames() // 通知里的任务名跟着改(缓存按身份记)
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
}
