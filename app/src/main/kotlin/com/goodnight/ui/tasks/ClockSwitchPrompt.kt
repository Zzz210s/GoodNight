package com.goodnight.ui.tasks

import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import com.goodnight.service.TimerCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * v2.2 Task 4:计时中换时钟的**确认闸门**(计时卡与任务卡片共用一份,避免两处各写一套)。
 *
 * 语义(设计 §4 拍板 1):点另一个时钟先弹确认,**未确认前不发出任何命令**;确认后发**一条**
 * [TimerCommands.switchClock] —— 协调器在同一把 mutex 内先终止(结算落库)再按新时钟开始,
 * 顺序由临界区保证,不新增切点类型,UI 不直接调引擎。
 *
 * 指针 1(首页高亮时钟):启动 / 换时钟时把选中时钟**镜像写回** `settingsRepo.activeProfileId`,
 * 于是首页顶栏高亮与运行时钟同源(顶栏的读取点属 Task 5,不在本任务改);计时卡文案另取运行快照
 * (真值),写入没落地也不影响显示。
 */
internal class ClockSwitchPrompt(
    private val graph: AppGraph,
) {
    private val _pending = MutableStateFlow<PendingClockSwitch?>(null)
    val pending: StateFlow<PendingClockSwitch?> = _pending.asStateFlow()

    /** 计时卡:选择另一个时钟,新会话**沿用当前绑定任务** */
    fun pickKeepingBinding(clock: ProfileEntity, onFree: () -> Unit) = ask(clock, null, onFree)

    /** 任务卡片:点时钟 chip,新会话绑定**这张卡片的任务** */
    fun pickForTask(clock: ProfileEntity, taskId: Long, onFree: () -> Unit) = ask(clock, taskId, onFree)

    fun dismiss() { _pending.value = null }

    /** 确认:一条命令内「终止 + 重新开始」,并把选中时钟镜像写回首页高亮 */
    fun confirm() {
        val p = _pending.value ?: return
        _pending.value = null
        TimerCommands.switchClock(
            graph.appContext, p.clock.id, p.clock.workMinutes * 60_000L, p.clock.restMinutes * 60_000L,
            countUp = p.clock.mode == ProfileMode.COUNTUP, taskId = p.taskId,
        )
        mirror(p.clock.id)
    }

    /** 这个时钟成为「当前时钟」时同步写回首页高亮(空闲起画 / 确认换时钟后) */
    fun mirror(profileId: Long) {
        // 用图作用域而非 viewModelScope:镜像写入不属于某个界面的生命周期(界面销毁不该丢掉它),
        // 且 DataStore 的 actor 本身就跑在 appScope 上(见 AppGraph),同一作用域内不会互相等锁。
        graph.appScope.launch { graph.settingsRepo.setActiveProfile(profileId) }
    }

    /**
     * 单次快照读:空闲直接生效;有会话则记下待确认请求(未确认前零命令)。
     * `engine.ready` 未就绪一律不发命令动作 —— 冷启动 restore() 前快照为空,放行会覆盖尚未恢复的
     * 运行快照(`engine.start` -> `save()`)并丢掉本段未落账时间。
     */
    private fun ask(clock: ProfileEntity, cardTaskId: Long?, onFree: () -> Unit) {
        if (!graph.engine.ready.value) return
        val snap = graph.engine.snapshot.value
        when (clockPickAction(snap, clock.id)) {
            ClockPickAction.SAME_CLOCK -> Unit
            ClockPickAction.ASK_CONFIRM -> _pending.value = PendingClockSwitch(clock, cardTaskId ?: snap?.taskId)
            ClockPickAction.FREE -> onFree()
        }
    }
}
