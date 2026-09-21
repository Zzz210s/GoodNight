package com.goodnight.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class EnginePolicyTest {
    private fun snap(status: EngineStatus?, profileId: Long = 1) = status?.let {
        RuntimeSnapshot(profileId, 1, 1, Phase.WORK, it, 0, 0, 1, 0, 0, 0, 0, 0, 0, null, 0)
    }

    @Test fun switchProfile() {
        assertEquals(PolicyAction.SET_ACTIVE, EnginePolicy.onSwitchProfile(null))
        assertEquals(PolicyAction.RESTART_PHASE, EnginePolicy.onSwitchProfile(snap(EngineStatus.PAUSED)))
        assertEquals(PolicyAction.IGNORED, EnginePolicy.onSwitchProfile(snap(EngineStatus.RUNNING)))
    }
    @Test fun editDurations() {
        assertEquals(PolicyAction.NONE, EnginePolicy.onEditDurations(null, 1))
        assertEquals(PolicyAction.IGNORED, EnginePolicy.onEditDurations(snap(EngineStatus.RUNNING), 1))
        assertEquals(PolicyAction.RESTART_PHASE, EnginePolicy.onEditDurations(snap(EngineStatus.PAUSED), 1))
        assertEquals(PolicyAction.NONE, EnginePolicy.onEditDurations(snap(EngineStatus.PAUSED), 2)) // 编辑的是别的配置
    }
    @Test fun delete() {
        assertEquals(PolicyAction.DELETE, EnginePolicy.onDelete(null, 1, remaining = 3))
        assertEquals(PolicyAction.DELETE, EnginePolicy.onDelete(snap(EngineStatus.PAUSED), 2, remaining = 3))
        assertEquals(PolicyAction.RESET_THEN_DELETE, EnginePolicy.onDelete(snap(EngineStatus.PAUSED), 1, remaining = 3))
        assertEquals(PolicyAction.IGNORED, EnginePolicy.onDelete(snap(EngineStatus.RUNNING), 1, remaining = 3))
        assertEquals(PolicyAction.IGNORED, EnginePolicy.onDelete(snap(EngineStatus.PAUSED), 1, remaining = 1)) // 最后一条
    }
}
