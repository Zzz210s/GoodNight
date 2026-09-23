package com.goodnight.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.TaskEntity
import com.goodnight.di.AppGraph
import com.goodnight.service.TimerCommands
import com.goodnight.timer.EnginePolicy
import com.goodnight.timer.PolicyAction
import com.goodnight.timer.RuntimeSnapshot
import com.goodnight.timer.TimeProvider
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val ready: Boolean = false,
    val profiles: List<ProfileEntity> = emptyList(),
    val activeProfileId: Long = -1,
    val snap: RuntimeSnapshot? = null,
    val todayMillis: Long = 0,
    val days: Map<LocalDate, Long> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(val graph: AppGraph) : ViewModel() {
    val time: TimeProvider get() = graph.time
    private val today: LocalDate = LocalDate.now()

    /** D2:全历史数据窗口 —— 热力图 v2 从最早记录渲染到 today,不再按周数截断 */
    private val from: String = LocalDate.ofEpochDay(0).toString()

    val ui: StateFlow<HomeUiState> = combine(
        graph.engine.ready,
        graph.profileRepo.profiles,
        graph.settingsRepo.activeProfileId,
        graph.engine.snapshot,
        graph.totalsRepo.dayTotals(from),
    ) { ready, profiles, active, snap, totals ->
        HomeUiState(
            ready = ready,
            profiles = profiles,
            activeProfileId = if (profiles.any { it.id == active }) active else profiles.firstOrNull()?.id ?: -1,
            snap = snap,
            todayMillis = totals.firstOrNull { it.date == today.toString() }?.total ?: 0,
            days = totals.associate { LocalDate.parse(it.date) to it.total },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _selectedDay = MutableStateFlow<LocalDate?>(null)
    val selectedDay: StateFlow<LocalDate?> = _selectedDay.asStateFlow()
    fun selectDay(d: LocalDate?) { _selectedDay.value = d }

    val dayDetail: StateFlow<DayDetailUi?> = _selectedDay
        .flatMapLatest { day -> if (day == null) flowOf(null) else dayDetailFlow(graph, day, from) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- v2.1 Task 7:计时页当前任务 chip ----

    /** 当前工作段绑定的任务(未绑定 = null);绑定随运行态持久化,杀进程/重启后仍在 */
    val currentTask: StateFlow<TaskEntity?> = graph.engine.snapshot
        .map { it?.taskId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else graph.taskRepo.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 选择器列表:仅进行中任务(已完成的不参与绑定) */
    val activeTasks: StateFlow<List<TaskEntity>> = graph.taskRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _taskPickerOpen = MutableStateFlow(false)
    val taskPickerOpen: StateFlow<Boolean> = _taskPickerOpen.asStateFlow()
    fun onOpenTaskPicker() { _taskPickerOpen.value = true }
    fun onDismissTaskPicker() { _taskPickerOpen.value = false }

    /**
     * 选择/解绑当前任务(null = 不绑定)。计时中切换由引擎按切点切段(§3 语义,引擎已实现);
     * 命令走 [TimerCommands] -> 服务 -> [com.goodnight.service.EngineCoordinator] —— 引擎的唯一
     * 驱动者、所有命令共用同一把 mutex,不直接调引擎(与 Task 6 删除路径的 bypass 相反)。
     */
    fun onPickTask(id: Long?) {
        _taskPickerOpen.value = false
        TimerCommands.setTask(graph.appContext, id)
    }

    /** @return true 时调用方需发 TimerCommands.restartPhase */
    suspend fun selectProfile(p: ProfileEntity): Boolean {
        return when (EnginePolicy.onSwitchProfile(graph.engine.snapshot.value)) {
            PolicyAction.RESTART_PHASE -> {
                graph.settingsRepo.setActiveProfile(p.id)
                true
            }
            PolicyAction.SET_ACTIVE -> {
                graph.settingsRepo.setActiveProfile(p.id)
                false
            }
            else -> false
        }
    }
}
