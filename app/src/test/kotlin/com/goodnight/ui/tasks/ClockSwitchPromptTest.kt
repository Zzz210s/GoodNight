package com.goodnight.ui.tasks

import com.goodnight.data.db.ProfileEntity
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2 Task 4 的纯决策层(无 Android 依赖,纯 JVM 直测):
 * 1. 点一个时钟的三态 —— 同一时钟 = 无动作;有会话(运行/暂停)= 先确认;空闲 = 直接生效;
 * 2. 选择器里的时钟分组 —— 按所属任务分组,通用时钟(taskId == null)单独一组。
 */
class ClockSwitchPromptTest {
    private fun snap(status: EngineStatus, profileId: Long) = RuntimeSnapshot(
        profileId = profileId, workMillis = 600_000, restMillis = 60_000, phase = Phase.WORK,
        status = status, cycleCount = 0, startElapsed = 0, endElapsed = 600_000, endWall = 0,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    private fun clock(id: Long, name: String, taskId: Long?) =
        ProfileEntity(id = id, name = name, workMinutes = 25, restMinutes = 5, createdAt = id, taskId = taskId)

    @Test fun idleTakesEffectDirectly() {
        assertEquals(ClockPickAction.FREE, clockPickAction(null, 7L, null))
        assertEquals(
            "IDLE 快照(防御)同样直接生效",
            ClockPickAction.FREE,
            clockPickAction(snap(EngineStatus.IDLE, 7L), 9L, null),
        )
    }

    @Test fun pickingTheRunningClockIsANoOp() {
        assertEquals(ClockPickAction.SAME_CLOCK, clockPickAction(snap(EngineStatus.RUNNING, 7L), 7L, null))
    }

    @Test fun pickingAnotherClockWhileRunningAsksFirst() {
        assertEquals(ClockPickAction.ASK_CONFIRM, clockPickAction(snap(EngineStatus.RUNNING, 7L), 8L, null))
    }

    /** 暂停中也是「有会话」:换时钟同样先确认(设计 §4:不允许段内静默换规则) */
    @Test fun pausedSessionAlsoAsksFirst() {
        assertEquals(ClockPickAction.ASK_CONFIRM, clockPickAction(snap(EngineStatus.PAUSED, 7L), 8L, null))
        assertEquals(ClockPickAction.SAME_CLOCK, clockPickAction(snap(EngineStatus.PAUSED, 7L), 7L, null))
    }

    /**
     * 修复:同一个时钟**换任务**也算变化 —— 在任务 A 的卡片上点正在任务 B 名下跑的通用时钟 X,
     * 旧判定只比 profileId,会静默 no-op(用户看不到任何反馈)。现在走确认流程。
     */
    @Test fun sameClockOnAnotherTaskAsksFirst() {
        val running = snap(EngineStatus.RUNNING, 7L).copy(taskId = 2L)

        assertEquals(ClockPickAction.ASK_CONFIRM, clockPickAction(running, 7L, targetTaskId = 1L))
        assertEquals("归属与目标任务相同:仍是 no-op", ClockPickAction.SAME_CLOCK, clockPickAction(running, 7L, 2L))
        assertEquals("目标任务为空(解绑)也算变化", ClockPickAction.ASK_CONFIRM, clockPickAction(running, 7L, null))
    }

    /** 计时卡入口目标 = 当前绑定,故「点正在跑的时钟」仍恒为 no-op(不因本修复新增弹窗) */
    @Test fun timerCardTargetKeepsRunningClockANoOp() {
        val running = snap(EngineStatus.RUNNING, 7L).copy(taskId = 2L)

        assertEquals(ClockPickAction.SAME_CLOCK, clockPickAction(running, 7L, running.taskId))
    }

    @Test fun groupsSpecificClocksByTheirTaskWithGenericOnItsOwn() {
        val own = clock(1, "A 专属", taskId = 9L)
        val shared = clock(2, "通用 25/5", taskId = null)

        val groups = clockPickerGroups(TaskClocks(specific = listOf(own), generic = listOf(shared)))

        assertEquals("先专属组(以所属任务命名),通用单独一组", listOf(9L, null), groups.map { it.taskId })
        assertEquals(listOf(listOf(own), listOf(shared)), groups.map { it.clocks })
    }

    @Test fun genericOnlyScopeHasASingleGenericGroup() {
        val shared = clock(2, "通用 25/5", taskId = null)

        val groups = clockPickerGroups(TaskClocks(generic = listOf(shared)))

        assertEquals(listOf<Long?>(null), groups.map { it.taskId })
        assertEquals(listOf(shared), groups.single().clocks)
    }

    @Test fun emptyScopeHasNoGroups() {
        assertTrue(clockPickerGroups(TaskClocks()).isEmpty())
    }
}
