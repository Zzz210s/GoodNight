package com.goodnight.timer

/** 60 秒周期性落库计算器(纯函数) */
object Checkpointer {
    data class Flush(val date: String, val deltaMillis: Long, val newAccum: Long)

    fun compute(s: RuntimeSnapshot, nowElapsed: Long, today: String): Flush {
        if (s.phase != Phase.WORK || s.status != EngineStatus.RUNNING) {
            return Flush(s.ckptDate ?: today, 0L, s.ckptAccum)
        }
        val accrued = s.accruedWork(nowElapsed)
        val delta = (accrued - s.ckptAccum).coerceAtLeast(0)
        return Flush(today, delta, accrued)
    }
}
