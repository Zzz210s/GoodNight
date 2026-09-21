package com.goodnight.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goodnight.GoodNightApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * v1.9.11 自动备份 Worker:读设置的目标 SAF URI,导出全量 JSON 写入该 URI(数据变动后触发)。失败静默(下次重试)。
 * v1.13.0:写入前先确认**持久化授权**还在(不在就补做一次),失败时按结果记错误码
 * (`permission` = 授权失效,设置页引导重选目录;`write` = 一般写入失败)。
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GoodNightApp
        val settings = app.graph.settingsRepo

        // 未启用或未设目标 URI:静默成功
        if (!settings.autoBackupEnabled.first()) return Result.success()
        val uriStr = settings.backupUri.first() ?: return Result.success()

        return withContext(Dispatchers.IO) {
            val uri = runCatching { android.net.Uri.parse(uriStr) }.getOrNull()
            if (uri == null || !BackupPermissions.ensure(applicationContext, uri)) {
                settings.setBackupError(BackupError.PERMISSION)
                return@withContext Result.failure()
            }
            try {
                val json = DataTransfer.exportJson(app.graph.db)
                // v1.9.13:用 BackupWriter 在目录下写固定文件名(覆盖),而非直接 openOutputStream(tree uri)
                when (BackupWriter.write(applicationContext, uri, json)) {
                    BackupWriteResult.OK -> {
                        settings.setBackupLastAt(System.currentTimeMillis())
                        settings.setBackupError(null)
                        Result.success()
                    }
                    BackupWriteResult.PERMISSION_DENIED -> {
                        settings.setBackupError(BackupError.PERMISSION)
                        Result.failure()
                    }
                    BackupWriteResult.FAILED -> {
                        settings.setBackupError(BackupError.WRITE)
                        Result.failure()
                    }
                }
            } catch (e: Throwable) {
                settings.setBackupError(BackupError.WRITE)
                Result.retry()
            }
        }
    }
}
