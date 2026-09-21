package com.goodnight.diag

/**
 * 跨组件共享的诊断状态(仅 debug 用途;正式版不读取也不写入)。
 * - [serviceAlive]:前台计时服务是否存活(由 TimerService.onCreate/onDestroy 维护)
 * - [appForeground]:应用是否在前台(由 GoodNightApp 的 ActivityLifecycleCallbacks 维护)
 */
object DiagState {
    @Volatile var serviceAlive: Boolean = false
    @Volatile var appForeground: Boolean = false
}
