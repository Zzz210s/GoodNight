package com.goodnight.service

/**
 * v2.2 Task 7:通知标题拼装 —— 相位 + **有的片段**(任务名 / 时钟名),用「 · 」连接:
 * 「工作中 · 写周报 · 番茄」。片段为 null/纯空白时按既有口径只说有的部分(未绑任务、
 * 时钟名解析不到都不会留下空的一段);都没有时就是原相位文案。
 *
 * 纯函数、无 Android 依赖:标题规则可以无资源直测。从 [TimerNotifications] 拆出只为守住 200 行。
 */
internal fun notifTitle(phaseText: String, taskTitle: String?, clockName: String?): String {
    val parts = listOfNotNull(
        taskTitle?.takeIf { it.isNotBlank() },
        clockName?.takeIf { it.isNotBlank() },
    )
    return when {
        parts.isEmpty() -> phaseText
        else -> parts.joinToString(separator = " · ", prefix = "$phaseText · ")
    }
}
