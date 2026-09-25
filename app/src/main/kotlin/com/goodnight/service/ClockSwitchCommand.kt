package com.goodnight.service

import com.goodnight.timer.TimerEngine

/**
 * v2.2 Task 4:换时钟的引擎动作 —— 「终止当前段 + 按新时钟开始」(设计 §4 拍板 1),
 * 由 [EngineCoordinator.run] 在**同一把 mutex 内**调用,顺序由临界区保证:
 *
 * 1. `reset()` 先把当前段的窗口 / 归属 / 暂停空档搭上 `Reset` 事件 —— 事件异步派发时按**事件负载**
 *    落库(见 [EventApplier]),所以「先终止(结算)」这一半不会被后续的 start 搅乱;
 * 2. `start()` 再按新时钟起新段(快照此时为空,`start` 不会被 no-op 挡下);
 * 3. `setTask()` 定义新段的归属:新鲜快照的绑定为空 -> 首次绑定 = 定义整段,不产生段内切点。
 *
 * 为什么不用「STOP 再 START 两条命令」:STOP 会走拆除握手(脱前台 + 空闲通知 + stopSelf),
 * 而换时钟从不进入空闲态 —— 前台通知会闪掉,且两条 intent 的到达顺序不在契约内。
 * 不新增切点类型;UI 只发命令,不直接调引擎。
 */
internal fun applyClockSwitch(engine: TimerEngine, cmd: TimerCommand) {
    engine.reset()
    engine.start(cmd.profileId, cmd.workMillis, cmd.restMillis, cmd.countUp)
    if (cmd.taskId != null) engine.setTask(cmd.taskId)
}
