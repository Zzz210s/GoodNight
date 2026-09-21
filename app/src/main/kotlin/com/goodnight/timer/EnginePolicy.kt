package com.goodnight.timer

/** R11 语义的 UI 层决策:运行中禁改;暂停中改则重开 */
enum class PolicyAction { SET_ACTIVE, RESTART_PHASE, NONE, IGNORED, RESET_THEN_DELETE, DELETE }

object EnginePolicy {
    fun onSwitchProfile(snap: RuntimeSnapshot?): PolicyAction = when {
        snap?.status == EngineStatus.RUNNING -> PolicyAction.IGNORED
        snap?.status == EngineStatus.PAUSED -> PolicyAction.RESTART_PHASE
        else -> PolicyAction.SET_ACTIVE
    }

    fun onEditDurations(snap: RuntimeSnapshot?, profileId: Long): PolicyAction = when {
        snap?.status == EngineStatus.RUNNING -> PolicyAction.IGNORED
        snap?.status == EngineStatus.PAUSED && snap.profileId == profileId -> PolicyAction.RESTART_PHASE
        else -> PolicyAction.NONE
    }

    fun onDelete(snap: RuntimeSnapshot?, profileId: Long, remaining: Int): PolicyAction = when {
        remaining <= 1 -> PolicyAction.IGNORED
        snap?.status == EngineStatus.RUNNING && snap.profileId == profileId -> PolicyAction.IGNORED
        snap?.status == EngineStatus.PAUSED && snap.profileId == profileId -> PolicyAction.RESET_THEN_DELETE
        else -> PolicyAction.DELETE
    }
}
