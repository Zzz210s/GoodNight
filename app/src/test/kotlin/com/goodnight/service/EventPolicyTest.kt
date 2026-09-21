package com.goodnight.service

import com.goodnight.timer.EngineEvent
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventPolicy 决策层钉(Fix Round 2 F5):settle 归属事件携带的 profileId、
 * RESET 赢得锁交错下 settle 仍落库且幻影提醒被门控、常规流各事件的效果序列。
 * 纯 JVM 直测(无 Robolectric),对应 Reconciler/Checkpointer 的测试风格。
 */
class EventPolicyTest {
    private fun snap(
        profileId: Long = 1L,
        phase: Phase = Phase.REST,
        status: EngineStatus = EngineStatus.RUNNING,
    ) = RuntimeSnapshot(
        profileId = profileId, workMillis = 100_000L, restMillis = 40_000L,
        phase = phase, status = status, cycleCount = 0,
        startElapsed = 0L, endElapsed = 140_000L, endWall = 1_000_000L,
        timeSpentPaused = 0L, lastPauseTime = 0L, timeAtPause = 0L,
        savedAtWall = 1_000_000L, savedAtElapsed = 0L, ckptDate = null, ckptAccum = 0L,
    )

    /** 钉 1(F5/Concern 3 服务层):快照已指向新 profile,settle 仍归属事件携带的旧 profileId */
    @Test fun phaseRestartedSettlesToEventProfileIdEvenWhenSnapshotMovedOn() {
        val ev = EngineEvent.PhaseRestarted(Phase.WORK, 50_000L, 1L, 240_000L, 2_000_000L)
        val fx = EventPolicy.decide(ev, snap(profileId = 2L, phase = Phase.WORK))
        assertEquals(
            listOf(EventEffect.CancelAlarm, EventEffect.Settle(50_000L, 1L), EventEffect.Arm(240_000L)),
            fx,
        )
    }

    /** 钉 2 前半(F5/F2):RESET 赢得锁 —— 后继快照已空,settle 仍归属事件 profileId */
    @Test fun phaseFinishedSettlesFromEventEvenWhenSuccessorSnapshotNulled() {
        val ev = EngineEvent.PhaseFinished(Phase.WORK, 100_000L, 1L, Phase.REST, auto = true)
        val fx = EventPolicy.decide(ev, null)
        assertEquals(listOf(EventEffect.CancelAlarm, EventEffect.Settle(100_000L, 1L)), fx)
    }

    /** 钉 2 后半(F5/F2):快照为空或阶段不符时,不得发出幻影提醒(用户已 RESET) */
    @Test fun phaseFinishedSkipsRemindWhenSuccessorMissingOrMismatched() {
        val ev = EngineEvent.PhaseFinished(Phase.WORK, 100_000L, 1L, Phase.REST, auto = true)
        assertFalse(EventPolicy.decide(ev, null).any { it is EventEffect.Remind })
        assertFalse(EventPolicy.decide(ev, snap(phase = Phase.WORK)).any { it is EventEffect.Remind })
    }

    /** 钉 3(F5/F2):正常流(后继快照仍 = ev.next)auto 结束 -> settle + 提醒(工作完成) */
    @Test fun phaseFinishedNormalFlowSettlesAndReminds() {
        val ev = EngineEvent.PhaseFinished(Phase.WORK, 100_000L, 1L, Phase.REST, auto = true)
        val fx = EventPolicy.decide(ev, snap(profileId = 1L, phase = Phase.REST))
        assertEquals(
            listOf(EventEffect.CancelAlarm, EventEffect.Settle(100_000L, 1L), EventEffect.Remind(workFinished = true)),
            fx,
        )
    }

    /** 钉 3 补充:休息结束的提醒是“开始工作”;手动 skip(auto=false)永不提醒 */
    @Test fun restFinishRemindsWorkStartsAndManualFinishNeverReminds() {
        val autoRest = EngineEvent.PhaseFinished(Phase.REST, 0L, 1L, Phase.WORK, auto = true)
        assertEquals(
            listOf(EventEffect.CancelAlarm, EventEffect.Remind(workFinished = false)),
            EventPolicy.decide(autoRest, snap(phase = Phase.WORK)),
        )
        val manual = EngineEvent.PhaseFinished(Phase.WORK, 30_000L, 1L, Phase.REST, auto = false)
        val fx = EventPolicy.decide(manual, snap(phase = Phase.REST))
        assertEquals(listOf(EventEffect.CancelAlarm, EventEffect.Settle(30_000L, 1L)), fx)
    }

    /** 钉 4(F5):Reset -> 取消闹钟 + settle 归属事件字段;PhaseStarted -> 武装 + 强制 checkpoint;
     *  Resumed -> 武装;Paused -> 取消闹钟 */
    @Test fun resetStartResumedPausedEffects() {
        assertEquals(
            listOf(EventEffect.CancelAlarm, EventEffect.Settle(70_000L, 3L)),
            EventPolicy.decide(EngineEvent.Reset(70_000L, 3L), null),
        )
        assertEquals(
            listOf(EventEffect.Arm(140_000L), EventEffect.ForceCheckpoint),
            EventPolicy.decide(EngineEvent.PhaseStarted(Phase.REST, 140_000L, 1_000_000L), snap(phase = Phase.REST)),
        )
        assertEquals(
            listOf(EventEffect.Arm(90_000L)),
            EventPolicy.decide(EngineEvent.Resumed(90_000L, 1_000_000L), null),
        )
        assertEquals(
            listOf(EventEffect.CancelAlarm),
            EventPolicy.decide(EngineEvent.Paused(50_000L), null),
        )
    }

    /** 钉 5(F5):settle<=0 不产生 Settle 效果(决策层省略;flushSettle 同款守卫为纵深防御) */
    @Test fun nonPositiveSettleOmitted() {
        assertEquals(
            listOf(EventEffect.CancelAlarm),
            EventPolicy.decide(EngineEvent.Reset(0L, 3L), null),
        )
        assertTrue(
            EventPolicy.decide(EngineEvent.PhaseRestarted(Phase.WORK, 0L, 1L, 240_000L, 2_000_000L), snap())
                .none { it is EventEffect.Settle },
        )
    }

    // ---- Task 7 / #10:正计时无到期 —— start/resume/重启均不得武装阶段到期闹钟 ----

    private fun countUpSnap() = snap(profileId = 2L, phase = Phase.WORK).copy(countUp = true)

    /** 钉 6:countUp start 的 PhaseStarted 只强制 checkpoint,不武装闹钟(ForceCheckpoint 顺序保留) */
    @Test fun countUpPhaseStartedSkipsArm() {
        assertEquals(
            listOf(EventEffect.ForceCheckpoint),
            EventPolicy.decide(
                EngineEvent.PhaseStarted(Phase.WORK, 140_000L, 1_000_000L),
                countUpSnap(),
            ),
        )
    }

    /** 钉 7:countUp resume 不重武装;倒计时(后继快照无 countUp)行为不变 */
    @Test fun countUpResumedSkipsArmButCountdownStillArms() {
        assertEquals(
            emptyList<EventEffect>(),
            EventPolicy.decide(EngineEvent.Resumed(90_000L, 1_000_000L), countUpSnap()),
        )
        assertEquals(
            listOf(EventEffect.Arm(90_000L)),
            EventPolicy.decide(EngineEvent.Resumed(90_000L, 1_000_000L), snap(phase = Phase.WORK)),
        )
    }

    /** 钉 8:countUp 重启仍取消闹钟 + 结算旧 profile,只是不武装新到期点 */
    @Test fun countUpPhaseRestartedSkipsArmButStillCancelsAndSettles() {
        val ev = EngineEvent.PhaseRestarted(Phase.WORK, 50_000L, 1L, 240_000L, 2_000_000L)
        val fx = EventPolicy.decide(ev, countUpSnap())
        assertEquals(
            listOf(EventEffect.CancelAlarm, EventEffect.Settle(50_000L, 1L)),
            fx,
        )
    }
}
