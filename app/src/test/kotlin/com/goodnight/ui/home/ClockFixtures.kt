package com.goodnight.ui.home

import com.goodnight.data.db.ProfileEntity
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot

/**
 * v2.2 Task 4 界面用例的公共夹具(拆两份用例文件以守住单文件 200 行):
 * 运行快照 + 建时钟/任务。断言一律走 VM 与 intent(见 ClockSwitchTest / ClockSwitchDisplayTest)。
 */
internal fun runningSnap(profileId: Long, taskId: Long? = null) = RuntimeSnapshot(
    profileId = profileId, workMillis = 600_000, restMillis = 60_000, phase = Phase.WORK,
    status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 600_000,
    endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
    savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0, taskId = taskId,
)

/** 建一个时钟并回读实体(倒计时 25/5 或 [mode]) */
internal suspend fun AppGraph.clock(name: String, taskId: Long? = null, mode: Int = 0): ProfileEntity =
    profileRepo.byId(profileRepo.create(name, 25, 5, mode, taskId)!!)!!

internal suspend fun AppGraph.task(title: String) = taskRepo.create(title, time.now())!!
