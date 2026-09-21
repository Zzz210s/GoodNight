package com.goodnight.timer

/** 设备重启后的双时钟换算(goodtime 方案) */
object StateRestorer {
    fun afterBoot(s: RuntimeSnapshot, nowWall: Long, nowElapsed: Long): RuntimeSnapshot {
        if (nowElapsed >= s.savedAtElapsed) return s // 未重启:savedAtElapsed 每次引擎状态迁移都会刷新(含 pause/resume/checkpoint),恒为本机单调钟(startElapsed 重锚后可为负,不可用作重启判据)
        return when (s.status) {
            EngineStatus.PAUSED -> s.copy(savedAtWall = nowWall, savedAtElapsed = nowElapsed)
            EngineStatus.RUNNING -> {
                val remainingWall = s.endWall - nowWall // 可为负:关机期间已到期
                val newEnd = nowElapsed + remainingWall
                val span = s.endElapsed - s.startElapsed // 阶段总跨度(含暂停前部分)
                s.copy(
                    startElapsed = newEnd - span,
                    endElapsed = newEnd,
                    savedAtWall = nowWall,
                    savedAtElapsed = nowElapsed,
                )
            }
            EngineStatus.IDLE -> s
        }
    }
}
