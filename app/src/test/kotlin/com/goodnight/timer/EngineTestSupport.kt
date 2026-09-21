package com.goodnight.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * 引擎测试共享桩(原先在两个测试类里各复制一份):可手动推进的假时钟 +
 * 引擎构造与事件收集辅助。抽到此处后各测试类只保留用例本体,便于维持文件行数上限。
 */

/** 可手动推进的假时钟(墙钟 nowMs 与 elapsed 各自独立,便于模拟重启/暂停) */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FakeTime(var nowMs: Long = 1_000_000L, var el: Long = 10_000L) : TimeProvider {
    override fun now() = nowMs
    override fun elapsedRealtime() = el
}

/** 构造被测引擎;persist 收集落库快照 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun testEngine(
    t: FakeTime,
    saved: MutableList<RuntimeSnapshot?> = mutableListOf(),
) =
    // UnconfinedTestDispatcher: persist 在 save() 内同步执行,避免独立 scheduler 永不推进
    TimerEngine(t, TestScope(UnconfinedTestDispatcher()), persist = { saved += it })

/**
 * 事件收集器(replay=0 后 replayCache 恒空,断言改对订阅列表):
 * Unconfined 订阅同步生效,tryEmit 同步投递,先订阅后驱动不漏事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun recordEvents(e: TimerEngine): MutableList<EngineEvent> {
    val seen = mutableListOf<EngineEvent>()
    TestScope(UnconfinedTestDispatcher()).launch { e.events.collect { seen += it } }
    return seen
}
