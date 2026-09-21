package com.goodnight.timer

/** START_STICKY 重启后(null intent)的对账决策 */
enum class ReconcileAction { STOP_SELF, FINISH_EXPIRED, RESUME_ACTIVE, SHOW_PAUSED }

object Reconciler {
    fun decide(s: RuntimeSnapshot?, nowElapsed: Long): ReconcileAction = when {
        s == null -> ReconcileAction.STOP_SELF
        s.status == EngineStatus.RUNNING && s.endElapsed <= nowElapsed -> ReconcileAction.FINISH_EXPIRED
        s.status == EngineStatus.RUNNING -> ReconcileAction.RESUME_ACTIVE
        else -> ReconcileAction.SHOW_PAUSED
    }
}
