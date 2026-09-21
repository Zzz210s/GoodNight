package com.goodnight.timer

interface TimeProvider {
    fun now(): Long
    fun elapsedRealtime(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}
