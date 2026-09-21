package com.goodnight.service

import org.junit.Assert.assertEquals
import org.junit.Test

import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot

/** 通知测试共用构造与反射工具(v1.11.0 拆分,保持单文件 <=200 行) */
internal fun snapOf(
    phase: Phase = Phase.WORK,
    status: EngineStatus = EngineStatus.RUNNING,
    startElapsed: Long = 0L,
    endElapsed: Long = 60_000L,
    timeSpentPaused: Long = 0L,
    timeAtPause: Long = 0L,
    cycleCount: Int = 0,
    countUp: Boolean = false,
): RuntimeSnapshot = RuntimeSnapshot(
    profileId = 1, workMillis = 60_000, restMillis = 60_000, phase = phase, status = status,
    cycleCount = cycleCount, startElapsed = startElapsed, endElapsed = endElapsed, endWall = 0L,
    timeSpentPaused = timeSpentPaused, lastPauseTime = 0L, timeAtPause = timeAtPause,
    savedAtWall = 0L, savedAtElapsed = 0L, ckptDate = null, ckptAccum = 0L, countUp = countUp,
)
    /** 沿类层次查找字段(RemoteViews 的 Action 子类把 methodName 声明在父类) */
    internal fun fieldValue(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            }
            c = c.superclass
        }
        return null
    }


/** v1.11.1 由 NotificationsTest 拆出:轮询节拍锚定到期时刻 */
class TickerDelayTest {
    @Test fun adaptiveTickerDelayIsCoarseUntilNearDeadline() {
        // v1.11.0 省电:剩余 >5s 时 15s 一次;临近到点 500ms;正计时/空闲恒 15s
        val now = 1_000_000L
        fun snapOf(end: Long, countUp: Boolean = false, status: EngineStatus = EngineStatus.RUNNING) = RuntimeSnapshot(
            profileId = 1, workMillis = 60_000, restMillis = 60_000, phase = Phase.WORK, status = status,
            cycleCount = 0, startElapsed = now - 1_000, endElapsed = end, endWall = 0L,
            timeSpentPaused = 0L, lastPauseTime = 0L, timeAtPause = 0L,
            savedAtWall = 0L, savedAtElapsed = 0L, ckptDate = null, ckptAccum = 0L, countUp = countUp,
        )
        // 粗节拍 15s;不足 15s 时按剩余时间等待(锚定到到期时刻,避免越过 00:00);极短则 500ms 兜底
        org.junit.Assert.assertEquals(15_000L, com.goodnight.service.nextDelayMs(snapOf(endElapsed = now + 60_000), now))
        org.junit.Assert.assertEquals(8_000L, com.goodnight.service.nextDelayMs(snapOf(endElapsed = now + 8_000), now))
        org.junit.Assert.assertEquals(2_000L, com.goodnight.service.nextDelayMs(snapOf(endElapsed = now + 2_000), now))
        org.junit.Assert.assertEquals(500L, com.goodnight.service.nextDelayMs(snapOf(endElapsed = now - 1_000), now))
        org.junit.Assert.assertEquals(15_000L, com.goodnight.service.nextDelayMs(snapOf(endElapsed = now + 60_000, countUp = true), now))
        org.junit.Assert.assertEquals(15_000L, com.goodnight.service.nextDelayMs(null, now))
    }
}
