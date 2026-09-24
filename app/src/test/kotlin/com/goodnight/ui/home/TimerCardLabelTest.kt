package com.goodnight.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 4:计时卡顶部横带文案 =「任务 · 时钟」。
 *
 * 三种情形(设计 §5):
 * 1. 有任务有时钟 -> `任务 · 时钟`;
 * 2. 只有时钟(通用时钟 / 未绑任务)-> 只有时钟名;
 * 3. 都没有 -> null,调用方回退**既有相位文案**(空闲/工作中/休息中)。
 *
 * 文案规则是纯函数(分隔符由资源 [R.string.timer_chip_pair] 提供,便于中英各写一份)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
class TimerCardLabelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val pair = "%1\$s · %2\$s"

    @Test fun pairsTaskAndClock() {
        assertEquals("写周报 · 专注", timerCardLabel("写周报", "专注", pair))
    }

    @Test fun clockAloneWhenTaskIsUnbound() {
        assertEquals("专注", timerCardLabel(null, "专注", pair))
    }

    @Test fun taskAloneWhenClockIsUnknown() {
        assertEquals("写周报", timerCardLabel("写周报", null, pair))
    }

    @Test fun nullWhenBothMissingSoCallerFallsBackToPhaseText() {
        assertNull("两者都没有:回退相位文案由调用方决定", timerCardLabel(null, null, pair))
    }

    /** 分隔符走资源(中英双份):zh 资源实际值必须与用例里的模板一致 */
    @Test fun separatorComesFromZhResource() {
        assertEquals(pair, ctx.getString(R.string.timer_chip_pair))
    }

    /** 显示的时钟:有会话取运行快照(真值),空闲取将要用哪个时钟 */
    @Test fun displayedClockPrefersRunningSnapshotThenSelection() {
        val running = RuntimeSnapshot(
            profileId = 7L, workMillis = 1, restMillis = 1, phase = Phase.WORK,
            status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 1,
            endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
            savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
        )
        assertEquals(7L, displayedClockId(running, 3L))
        assertEquals(3L, displayedClockId(null, 3L))
        assertEquals(-1L, displayedClockId(null, -1L))
    }
}
