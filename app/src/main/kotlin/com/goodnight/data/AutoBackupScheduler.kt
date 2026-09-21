package com.goodnight.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * v1.9.12 自动备份调度(#37 重构):从"每日周期"改为"一段工作时间结束自动备份一次"。
 * EventApplier 在 WORK 段结算(完成/终止/跳过/切换,settle>0 且非误触)时调 scheduleNow()
 * 入队一次性任务;同 ID REPLACE 合并,短时间多段结束不堆积(只备份最新状态)。
 * 需网络(网盘目标);失败按指数退避重试。
 */
object AutoBackupScheduler {
    private const val WORK_NAME = "auto_backup_on_work_end"

    fun scheduleNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** v1.9.11 的每日周期调度已废弃(#37);保留空实现避免调用方悬空 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
