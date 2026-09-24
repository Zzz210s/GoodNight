package com.goodnight.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodnight.data.ReminderIntensity
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.timer.EnginePolicy
import com.goodnight.timer.PolicyAction
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
)

class SettingsViewModel(val graph: AppGraph) : ViewModel() {

    private val _exactAlarmBlocked = kotlinx.coroutines.flow.MutableStateFlow(false)

    val ui: StateFlow<SettingsUiState> = combine(
        combine(
            graph.profileRepo.profiles,
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
    ) { s, b, fatigue ->
        s.copy(
            themePack = b.pack, autoBackup = b.auto, backupUri = b.uri,
            backupLastAt = b.last, backupError = b.err, fatigueReminder = fatigue,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setFatigueReminder(on: Boolean) {
        viewModelScope.launch { graph.settingsRepo.setFatigueReminder(on) }
    }

    // v1.9.12 #37:开关只持久化 —— 备份时机改为工作段结束事件触发(EventApplier 调 scheduleNow),
    // 不再每日周期注册;选目录后立即做一次备份(立即验证目录可用)。
    fun setAutoBackup(context: Context, on: Boolean) {
        viewModelScope.launch { graph.settingsRepo.setAutoBackupEnabled(on) }
        if (on) com.goodnight.data.AutoBackupScheduler.scheduleNow(context)
        else com.goodnight.data.AutoBackupScheduler.cancel(context)
    }

    suspend fun setBackupUri(context: Context, uri: String) {
        // v1.13.0:必须先持久化目录授权,否则重启后备份目录失效
        com.goodnight.data.BackupPermissions.persist(context, android.net.Uri.parse(uri))
        graph.settingsRepo.setBackupUri(uri)
        graph.settingsRepo.setAutoBackupEnabled(true)
        com.goodnight.data.AutoBackupScheduler.scheduleNow(context)
    }

    /** v1.9.13 手动备份:仅存目录(不启用自动备份),并立即写固定文件覆盖。@return 是否写入成功 */
    suspend fun setBackupDir(context: Context, uri: String): Boolean {
        com.goodnight.data.BackupPermissions.persist(context, android.net.Uri.parse(uri))
        graph.settingsRepo.setBackupUri(uri)
        return backupNow()
    }

    /**
     * v1.9.13 手动备份:读已存目录,写固定文件(覆盖),不触发自动备份调度。
     * v1.13.0:写入前先确保持久化授权在(不在就补做);失败按错误码记录,便于设置页给出准确提示。
     * @return 是否写入成功(目录未选/授权失效/写盘失败均为 false)
     */
    suspend fun backupNow(): Boolean {
        val uriStr = graph.settingsRepo.backupUri.first() ?: return false
        val uri = android.net.Uri.parse(uriStr)
        if (!com.goodnight.data.BackupPermissions.ensure(graph.appContext, uri)) {
            graph.settingsRepo.setBackupError(com.goodnight.data.BackupError.PERMISSION)
            return false
        }
        val json = com.goodnight.data.DataTransfer.exportJson(graph.db)
        return when (com.goodnight.data.BackupWriter.write(graph.appContext, uri, json)) {
            com.goodnight.data.BackupWriteResult.OK -> {
                graph.settingsRepo.setBackupLastAt(System.currentTimeMillis())
                graph.settingsRepo.setBackupError(null)
                true
            }
            com.goodnight.data.BackupWriteResult.PERMISSION_DENIED -> {
                graph.settingsRepo.setBackupError(com.goodnight.data.BackupError.PERMISSION)
                false
            }
            com.goodnight.data.BackupWriteResult.FAILED -> {
                graph.settingsRepo.setBackupError(com.goodnight.data.BackupError.WRITE)
                false
            }
        }
    }

    /**
     * 手动恢复:自 SAF 文档 Uri 读 JSON 并合并入库。
     * @return 写入的行数合计(配置/日累计/段/任务,v2.1 Task 9 起含任务);读/解析失败返回 null(由 UI 提示)
     */
    suspend fun restoreFrom(uri: android.net.Uri): Int? = runCatching {
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            graph.appContext.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        }
        val counts = com.goodnight.data.DataTransfer.importJson(graph.db, text)
        // v1.10.8:导入后按"段落派生"重算全部合计,保证与每日详情时间段之和一致
        graph.totalsRepo.recomputeAllDays()
        counts.total
    }.getOrNull()

    fun refreshExactAlarm(context: Context) {
        viewModelScope.launch {
            val blocked = Build.VERSION.SDK_INT >= 31 &&
                !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            _exactAlarmBlocked.value = blocked
        }
    }

    /** v2.2:重名返回 null(旧版返回 -1L,调用方一律忽略返回值) */
    suspend fun createProfile(name: String, workMinutes: Int, restMinutes: Int, mode: Int): Long? =
        // 对话框带模式选择(新建缺省倒计时由对话框状态决定);禁缺省:模式是显式用户选择
        graph.profileRepo.create(name, workMinutes, restMinutes, mode)

    /** v2.2:作用域内重名返回 false(Task 5 据此给中文错误提示) */
    suspend fun renameProfile(id: Long, name: String): Boolean = graph.profileRepo.rename(id, name)

    /** @return true 时调用方需发 TimerCommands.restartPhase(mode 参数为对话框当前选中的模式) */
    suspend fun editDurations(p: ProfileEntity, workMinutes: Int, restMinutes: Int, mode: Int): Boolean {
        val action = EnginePolicy.onEditDurations(graph.engine.snapshot.value, p.id)
        if (action == PolicyAction.IGNORED) return false
        graph.profileRepo.updateDurations(p.id, workMinutes, restMinutes, mode)
        return action == PolicyAction.RESTART_PHASE
    }

    /** @return true 时调用方需先发 TimerCommands.stop 再删除 */
    suspend fun deleteProfile(p: ProfileEntity): Boolean {
        val action = EnginePolicy.onDelete(graph.engine.snapshot.value, p.id, graph.profileRepo.count().toInt())
        when (action) {
            PolicyAction.IGNORED -> return false
            PolicyAction.RESET_THEN_DELETE -> {
                graph.profileRepo.delete(p)
                graph.totalsRepo.deleteProfileData(p.id)
                return true // 调用方发 stop(顺序:reset 引擎结算后清快照;DB 行已删)
            }
            PolicyAction.DELETE -> {
                graph.profileRepo.delete(p)
                graph.totalsRepo.deleteProfileData(p.id)  // v1.10.8:级联清段落/合计,避免"已删除配置"残留
                return false
            }
            else -> return false
        }
    }

    /** suspend 落库(非计划里的 viewModelScope 发射后不管):Robolectric 主循环暂停, fire-and-forget
     *  对测试不可见;改为挂起语义与 selectProfile(H1 pin settings writes)一致 */
    suspend fun setIntensity(i: ReminderIntensity) = graph.settingsRepo.setReminderIntensity(i)

    suspend fun setThemePack(pack: com.goodnight.ui.theme.ThemePack) = graph.settingsRepo.setThemePack(pack)
}
