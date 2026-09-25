package com.goodnight.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodnight.data.ReminderIntensity
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.TaskEntity
import com.goodnight.di.AppGraph
import com.goodnight.timer.EnginePolicy
import com.goodnight.timer.PolicyAction
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


private data class BackupState(
    val pack: com.goodnight.ui.theme.ThemePack = com.goodnight.ui.theme.ThemePack.EMBER,
    val auto: Boolean = false,
    val uri: String? = null,
    val last: Long = 0L,
    val err: String? = null,
)

data class SettingsUiState(
    /** 只含**活跃**(未归档)时钟:归档行仍在库里供历史解析,但列表与选择器都不该再喂给用户 */
    val profiles: List<ProfileEntity> = emptyList(),
    val totals: Map<Long, Long> = emptyMap(),
    val intensity: ReminderIntensity = ReminderIntensity.STANDARD,
    val snap: RuntimeSnapshot? = null,
    val exactAlarmBlocked: Boolean = false,
    val themePack: com.goodnight.ui.theme.ThemePack = com.goodnight.ui.theme.ThemePack.EMBER,
    // v1.9.11 自动备份
    val autoBackup: Boolean = false,
    val backupUri: String? = null,
    val backupLastAt: Long = 0L,
    /** v1.11.0:上次自动/手动备份失败原因(null = 正常) */
    val backupError: String? = null,
    /** v1.14.0:疲劳提醒开关(同一任务连续工作 90 分钟提醒长休息) */
    val fatigueReminder: Boolean = true,
    // v2.2 Task 5:时钟管理页的「通用 / 任务专属」两段与归属选择器
    /** 全部任务(未完成在前)—— 分组顺序与归属选择的候选,来自 observeAllOrdered */
    val tasks: List<TaskEntity> = emptyList(),
    val sections: List<ClockSection> = emptyList(),
)

class SettingsViewModel(val graph: AppGraph) : ViewModel() {

    private val _exactAlarmBlocked = kotlinx.coroutines.flow.MutableStateFlow(false)

    val ui: StateFlow<SettingsUiState> = combine(
        combine(
            // 指针 1(v2.2 Task 5):列表与「至少保留 1 个」都只看看得见的行 —— 归档行不参与
            graph.profileRepo.observeAllActive(),
            graph.totalsRepo.profileTotals(),
            graph.settingsRepo.reminderIntensity,
            graph.engine.snapshot,
            _exactAlarmBlocked,
        ) { profiles, totals, intensity, snap, blocked ->
            SettingsUiState(profiles, totals.associate { it.profileId to it.total }, intensity, snap, blocked, com.goodnight.ui.theme.ThemePack.EMBER)
        },
        combine(
            graph.settingsRepo.themePack,
            graph.settingsRepo.autoBackupEnabled,
            graph.settingsRepo.backupUri,
            graph.settingsRepo.backupLastAt,
            graph.settingsRepo.backupError,
        ) { pack, auto, uri, last, err ->
            BackupState(pack, auto, uri, last, err)
        },
        graph.settingsRepo.fatigueReminder,
        graph.taskRepo.observeAllOrdered(),
    ) { s, b, fatigue, tasks ->
        s.copy(
            themePack = b.pack, autoBackup = b.auto, backupUri = b.uri,
            backupLastAt = b.last, backupError = b.err, fatigueReminder = fatigue,
            tasks = tasks, sections = clockSections(s.profiles, tasks),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setFatigueReminder(on: Boolean) {
        viewModelScope.launch { graph.settingsRepo.setFatigueReminder(on) }
    }

    fun refreshExactAlarm(context: Context) {
        viewModelScope.launch {
            val blocked = Build.VERSION.SDK_INT >= 31 &&
                !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            _exactAlarmBlocked.value = blocked
        }
    }

    /** v2.2:重名返回 null(旧版返回 -1L,调用方一律忽略返回值);[taskId] = 归属(null = 通用层) */
    suspend fun createProfile(
        name: String,
        workMinutes: Int,
        restMinutes: Int,
        mode: Int,
        taskId: Long? = null,
    ): Long? =
        // 对话框带模式选择(新建缺省倒计时由对话框状态决定);禁缺省:模式是显式用户选择
        graph.profileRepo.create(name, workMinutes, restMinutes, mode, taskId)

    /** @return true 时调用方需发 TimerCommands.restartPhase(mode 参数为对话框当前选中的模式) */
    suspend fun editDurations(p: ProfileEntity, workMinutes: Int, restMinutes: Int, mode: Int): Boolean {
        val action = EnginePolicy.onEditDurations(graph.engine.snapshot.value, p.id)
        if (action == PolicyAction.IGNORED) return false
        graph.profileRepo.updateDurations(p.id, workMinutes, restMinutes, mode)
        return action == PolicyAction.RESTART_PHASE
    }

    /** suspend 落库(非计划里的 viewModelScope 发射后不管):Robolectric 主循环暂停, fire-and-forget
     *  对测试不可见;改为挂起语义与 selectProfile(H1 pin settings writes)一致 */
    suspend fun setIntensity(i: ReminderIntensity) = graph.settingsRepo.setReminderIntensity(i)

    suspend fun setThemePack(pack: com.goodnight.ui.theme.ThemePack) = graph.settingsRepo.setThemePack(pack)
}
