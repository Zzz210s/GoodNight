package com.goodnight.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow

enum class ReminderIntensity { LIGHT, STANDARD, STRONG }

class SettingsRepository(private val ds: DataStore<Preferences>) {
    private val keyActive = longPreferencesKey("active_profile_id")
    private val keyIntensity = stringPreferencesKey("reminder_intensity")
    private val keyThemePack = stringPreferencesKey("theme_pack")
    // v1.9.11 自动备份
    private val keyAutoBackup = booleanPreferencesKey("autobackup")
    private val keyBackupUri = stringPreferencesKey("backup_uri")
    private val keyBackupError = stringPreferencesKey("backup_error")
    private val keyBackupLast = longPreferencesKey("backup_last")
    private val keyFirstLaunch = stringPreferencesKey("first_launch_date")
    // v1.14.0 疲劳提醒
    private val keyFatigue = booleanPreferencesKey("fatigue_reminder")

    val activeProfileId: Flow<Long> = ds.data.map { it[keyActive] ?: -1L }

    suspend fun setActiveProfile(id: Long) { ds.edit { it[keyActive] = id } }

    val reminderIntensity: Flow<ReminderIntensity> = ds.data.map { prefs ->
        prefs[keyIntensity]?.let { name -> ReminderIntensity.entries.firstOrNull { it.name == name } } ?: ReminderIntensity.STANDARD
    }

    suspend fun setReminderIntensity(v: ReminderIntensity) { ds.edit { it[keyIntensity] = v.name } }

    val themePack: Flow<com.goodnight.ui.theme.ThemePack> = ds.data.map { prefs ->
        com.goodnight.ui.theme.ThemePack.fromName(prefs[keyThemePack])
    }

    suspend fun setThemePack(p: com.goodnight.ui.theme.ThemePack) { ds.edit { it[keyThemePack] = p.name } }

    // ---- 自动备份 ----
    val autoBackupEnabled: Flow<Boolean> = ds.data.map { it[keyAutoBackup] ?: false }
    val backupUri: Flow<String?> = ds.data.map { it[keyBackupUri] }
    val backupLastAt: Flow<Long> = ds.data.map { it[keyBackupLast] ?: 0L }

    suspend fun setAutoBackupEnabled(v: Boolean) { ds.edit { it[keyAutoBackup] = v } }
    suspend fun setBackupUri(uri: String?) {
        ds.edit { if (uri == null) it.remove(keyBackupUri) else it[keyBackupUri] = uri }
    }

    suspend fun setBackupLastAt(t: Long) { ds.edit { it[keyBackupLast] = t } }

    /** v1.11.0:上次备份失败原因("grant" 授权失效 / "write" 写入失败);null = 无错误 */
    val backupError: Flow<String?> = ds.data.map { it[keyBackupError] }

    suspend fun setBackupError(err: String?) {
        ds.edit { if (err == null) it.remove(keyBackupError) else it[keyBackupError] = err }
    }

    /** 首次打开应用日期(yyyy-MM-dd);报表往期回顾的起点。未设置时返回 null。 */
    val firstLaunchDate: Flow<String?> = ds.data.map { it[keyFirstLaunch] }
    suspend fun setFirstLaunchDate(d: String) { ds.edit { it[keyFirstLaunch] = d } }

    // ---- v1.14.0 疲劳提醒(同一任务连续工作 90 分钟提醒长休息;默认开) ----
    val fatigueReminder: Flow<Boolean> = ds.data.map { it[keyFatigue] ?: true }

    suspend fun setFatigueReminder(v: Boolean) { ds.edit { it[keyFatigue] = v } }
}
