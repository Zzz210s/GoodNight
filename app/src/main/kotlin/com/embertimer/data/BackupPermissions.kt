package com.embertimer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.first

/** 备份错误码(写入 SettingsRepository.backup_error,设置页据此提示) */
object BackupError {
    /** 目录不可写 / provider 报错等一般性写入失败 */
    const val WRITE = "write"

    /** 目录授权已失效(需用户重新选择备份目录) */
    const val PERMISSION = "permission"
}

/**
 * SAF 目录授权持久化 —— 修复"每次重启后备份目录失效"(v1.13.0)。
 *
 * 症状:选好备份目录后当次备份成功,重启(或进程被杀)后再备份就失败,设置页显示"备份失败"。
 *
 * 根因:`OpenDocumentTree` 返回的 tree Uri 授权**只对当前进程有效**;要长期使用必须显式
 * `takePersistableUriPermission` 把它交给系统保存。本项目此前从未调用过该方法
 * (真机取证:`dumpsys activity` 的 Granted Uri Permissions 里查不到本应用 UID 的任何授权,
 * 而设置库里已存 backup_uri + backup_error=write)。
 */
object BackupPermissions {
    private const val TAG = "BackupPermissions"
    private const val FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 选择目录后立刻持久化授权;返回是否成功 */
    fun persist(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(treeUri, FLAGS)
        com.embertimer.diag.DiagLog.add("Backup", "持久化目录授权成功 $treeUri")
        true
    }.getOrElse {
        Log.w(TAG, "takePersistableUriPermission failed: $treeUri", it)
        com.embertimer.diag.DiagLog.add("Backup", "持久化目录授权失败 $treeUri")
        false
    }

    /** 是否已持有该目录的持久化写授权 */
    fun hasPersisted(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
    }.getOrDefault(false)

    /** 备份前自愈:已持久化 → true;否则补做一次;仍失败 → false(需用户重选目录) */
    fun ensure(context: Context, treeUri: Uri): Boolean =
        hasPersisted(context, treeUri) || persist(context, treeUri)

    /**
     * 启动自愈:已设目标目录但没有持久化授权时补做一次;补不上就记录"授权失效"错误,
     * 让设置页给出"重新选择备份目录"的明确提示(而不是备份静默失败)。
     */
    suspend fun healOnStartup(context: Context, settings: SettingsRepository) {
        val uriStr = settings.backupUri.first() ?: return
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        if (ensure(context, uri)) {
            if (settings.backupError.first() == BackupError.PERMISSION) settings.setBackupError(null)
        } else {
            settings.setBackupError(BackupError.PERMISSION)
        }
    }
}
