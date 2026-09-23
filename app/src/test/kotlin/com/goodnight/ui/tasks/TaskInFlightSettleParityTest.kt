package com.goodnight.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.MERGE_GAP_MS
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 6(评审修复):在途口径 ↔ 结算落库口径对齐。
 *
 * 删除确认的「已记录的 N 分钟」= 已落库 + 在途,两者必须是同一套归属/暂停规则 —— 否则用户看到
 * 的是两套算法拼出来的数,恰好 3 分钟暂停这类边界会露馅。这里让**同一窗口**分别走真实落库
 * ([com.goodnight.data.DailyTotalRepository.recordWorkSessionSplit])与在途估算
 * ([inFlightMillisFor]),四舍五入到整分后必须相等。
 *
 * 边界依据:`>= 3 分钟` 的暂停把段落切开,`mergeSessions` 又把间隔 `<= 3 分钟` 的相邻段并回,
 * 净效果是「恰好 3 分钟的暂停仍算工作」,故在途侧必须用严格大于;若改回 `>=`,第一个用例(0 与
 * `MERGE_GAP_MS` 两个点)会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TaskInFlightSettleParityTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun snap(taskId: Long, startWall: Long, pauseGaps: String) = RuntimeSnapshot(
        profileId = 1, workMillis = 1, restMillis = 1, phase = Phase.WORK,
        status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 1,
        endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = startWall, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
        taskId = taskId, sessionStartWall = startWall, pauseGaps = pauseGaps,
    )

    private fun minutes(ms: Long) = (ms + 30_000L) / 60_000L

    /** 同一窗口两种口径的整分结果必须相等(每条用例自带 graph,避免共享状态) */
    private suspend fun assertParity(pauseMillis: Long) {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "parity_$pauseMillis")
        try {
            val now = g.time.now()
            val start = now - 60 * 60_000L
            val id = g.taskRepo.create("写周报", start)!!
            val p0 = start + 20 * 60_000L
            val pauses = if (pauseMillis > 0) listOf(longArrayOf(p0, p0 + pauseMillis)) else emptyList()
            g.totalsRepo.recordWorkSessionSplit(profileId = 1, startAt = start, endAt = now, pauses = pauses, taskId = id)
            val gaps = pauses.joinToString("") { "${it[0]},${it[1]}" }
            g.engine.restore(snap(id, start, gaps))

            val settled = minutes(g.taskRepo.recordedMillis(id))
            val inFlight = minutes(inFlightMillisFor(g.engine.snapshot.value!!, id, now))
            assertEquals("pause=$pauseMillis 已落库 vs 在途", settled, inFlight)
            // 超阈值暂停才从整段里扣;恰好 3 分钟的暂停仍算工作(入库合并规则)
            val expected = 60 * 60_000L - if (pauseMillis > MERGE_GAP_MS) pauseMillis else 0L
            assertEquals("pause=$pauseMillis 期望分钟", minutes(expected), settled)
        } finally {
            runCatching { g.db.close() }
        }
    }

    @Test fun inFlightEqualsSettledMinutesAroundPauseThreshold() = runTest {
        assertParity(0L) // 无暂停
        assertParity(MERGE_GAP_MS) // 恰好 3 分钟:入库合并回整段,在途同样不计扣
        assertParity(MERGE_GAP_MS + 1) // 刚过阈值:两边都扣
        assertParity(10 * 60_000L) // 长暂停:切段留痕
    }

    @Test fun longPauseParityUnderPauseStartWall() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "parity_paused")
        try {
            val now = g.time.now()
            val start = now - 60 * 60_000L
            val id = g.taskRepo.create("写周报", start)!!
            // 进行中的暂停:工作只算到暂停起点(与「暂停后续干」的入库窗口一致)
            val pausedAt = now - 5 * 60_000L
            val s = snap(id, start, "").copy(status = EngineStatus.PAUSED, pauseStartWall = pausedAt)
            g.engine.restore(s)
            assertEquals(55L, minutes(inFlightMillisFor(s, id, now)))
            g.totalsRepo.recordWorkSessionSplit(profileId = 1, startAt = start, endAt = pausedAt, pauses = emptyList(), taskId = id)
            assertEquals(55L, minutes(g.taskRepo.recordedMillis(id)))
        } finally {
            runCatching { g.db.close() }
        }
    }
}
