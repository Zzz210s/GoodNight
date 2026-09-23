package com.goodnight.service

import android.content.Context
import android.content.Intent

/** UI -> 服务的命令入口。start 用 startForegroundService(Activity 前台调用合法),其余走 startService。
 *  countUp(正计时)经 EXTRA_COUNT_UP 贯穿(缺省 false = 倒计时);intent 构建器 internal 供单测钉 extras。 */
object TimerCommands {
    fun start(context: Context, profileId: Long, workMillis: Long, restMillis: Long, countUp: Boolean = false) {
        context.startForegroundService(startIntent(context, profileId, workMillis, restMillis, countUp))
    }

    fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
    fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
    fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))
    fun skip(context: Context) = context.startService(intent(context, ACTION_SKIP))

    fun restartPhase(context: Context, profileId: Long, workMillis: Long, restMillis: Long, countUp: Boolean = false) {
        context.startService(restartPhaseIntent(context, profileId, workMillis, restMillis, countUp))
    }

    /**
     * v2.1 Task 7:绑定/解绑当前工作段的任务(null = 不绑定)。计时中切换由引擎按切点切段(§3)。
     * 与其它命令同走 Intent -> [TimerService] -> [EngineCoordinator](引擎的唯一驱动者)。
     */
    fun setTask(context: Context, taskId: Long?) = context.startService(setTaskIntent(context, taskId))

    internal fun startIntent(context: Context, profileId: Long, workMillis: Long, restMillis: Long, countUp: Boolean) =
        intent(context, ACTION_START)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_WORK_MILLIS, workMillis)
            .putExtra(EXTRA_REST_MILLIS, restMillis)
            .putExtra(EXTRA_COUNT_UP, countUp)

    internal fun restartPhaseIntent(context: Context, profileId: Long, workMillis: Long, restMillis: Long, countUp: Boolean) =
        intent(context, ACTION_RESTART_PHASE)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_WORK_MILLIS, workMillis)
            .putExtra(EXTRA_REST_MILLIS, restMillis)
            .putExtra(EXTRA_COUNT_UP, countUp)

    private fun intent(context: Context, action: String) =
        Intent(context, TimerService::class.java).setAction(action)

    internal fun setTaskIntent(context: Context, taskId: Long?) =
        intent(context, ACTION_SET_TASK).putExtra(EXTRA_TASK_ID, taskId ?: NO_TASK_ID)
}
