package com.goodnight.ui.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v2.2 Task 1:备份/恢复胶水从 [SettingsViewModel] 拆出 —— 原文件被新增 KDoc 顶破 200 行上限。
 * 写成同包扩展函数,设置页调用面(`vm.backupNow()` 等)与测试都不用改,状态源仍是 [SettingsViewModel.graph]。
 */

// v1.9.12 #37:开关只持久化 —— 备份时机改为工作段结束事件触发(EventApplier 调 scheduleNow),
// 不再每日周期注册;选目录后立即做一次备份(立即验证目录可用)。
fun SettingsViewModel.setAutoBackup(context: Context, on: Boolean) {
    viewModelScope.launch { graph.settingsRepo.setAutoBackupEnabled(on) }
    if (on) com.goodnight.data.AutoBackupScheduler.scheduleNow(context)
    else com.goodnight.data.AutoBackupScheduler.cancel(context)
}

suspend fun SettingsViewModel.setBackupUri(context: Context, uri: String) {
    // v1.13.0:必须先持久化目录授权,否则重启后备份目录失效
    com.goodnight.data.BackupPermissions.persist(context, android.net.Uri.parse(uri))
    graph.settingsRepo.setBackupUri(uri)
    graph.settingsRepo.setAutoBackupEnabled(true)
    com.goodnight.data.AutoBackupScheduler.scheduleNow(context)
}

/** v1.9.13 手动备份:仅存目录(不启用自动备份),并立即写固定文件覆盖。@return 是否写入成功 */
suspend fun SettingsViewModel.setBackupDir(context: Context, uri: String): Boolean {
    com.goodnight.data.BackupPermissions.persist(context, android.net.Uri.parse(uri))
    graph.settingsRepo.setBackupUri(uri)
    return backupNow()
}

/**
 * v1.9.13 手动备份:读已存目录,写固定文件(覆盖),不触发自动备份调度。
 * v1.13.0:写入前先确保持久化授权在(不在就补做);失败按错误码记录,便于设置页给出准确提示。
 * @return 是否写入成功(目录未选/授权失效/写盘失败均为 false)
 */
suspend fun SettingsViewModel.backupNow(): Boolean {
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
suspend fun SettingsViewModel.restoreFrom(uri: android.net.Uri): Int? = runCatching {
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
