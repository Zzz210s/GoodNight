package com.goodnight.service

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v2.2 Task 5(复审修复 W1):停机 + 等**结算落库**完成。
 *
 * 删除时钟前必须先走这一步:`reset()` 的 Reset 事件由 [EventApplier] 落一段,而它**不校验
 * profile 是否还在库里**(只校验 taskId),所以「先删行、再 stop」会把暂停段的窗口写到已删的
 * profileId 上 —— 会话段/每日合计出现悬空引用。
 *
 * 等待信号是 [EngineCoordinator.stopDrained](与服务拆除握手同一个:Reset 事件处理完才 complete);
 * 有界 3s,超时只记日志(引擎此刻已经 reset,只是结算可能晚到)。
 *
 * 放在协调器外的扩展里是为了保持 [EngineCoordinator] 单文件 <=200 行;行为与协调器内部一致。
 *
 * @return true = 确实停机并等到了 Reset 结算;false = 本来就空闲 / 握手超时
 */
suspend fun EngineCoordinator.stopAndSettle(timeoutMs: Long = 3_000): Boolean {
    if (graph.engine.snapshot.value == null) return false
    run(TimerCommand(ACTION_STOP))
    val drained = stopDrained ?: return false
    val ok = withTimeoutOrNull(timeoutMs) { drained.await() } != null
    if (!ok) Log.w("EngineCoordinator", "stopAndSettle: Reset settle drain timed out (${timeoutMs}ms)")
    return ok
}
