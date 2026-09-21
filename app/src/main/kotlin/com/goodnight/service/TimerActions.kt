package com.goodnight.service

/**
 * v1.10.12:计时服务的命令 action 与 intent extra 常量(从 [TimerService] companion 抽出,
 * 使 TimerService 保持在 200 行内;引用点由 `ACTION_X` 改为直接 `ACTION_X`)。
 */
const val ACTION_START = "com.goodnight.action.START"
const val ACTION_PAUSE = "com.goodnight.action.PAUSE"
const val ACTION_RESUME = "com.goodnight.action.RESUME"
const val ACTION_STOP = "com.goodnight.action.STOP"
const val ACTION_SKIP = "com.goodnight.action.SKIP"
const val ACTION_RESTART_PHASE = "com.goodnight.action.RESTART_PHASE"

/** v1.10.11:通知"对号"确认(清除提醒通知) */
const val ACTION_ACK = "com.goodnight.action.ACK"

const val EXTRA_PROFILE_ID = "profile_id"
const val EXTRA_WORK_MILLIS = "work_millis"
const val EXTRA_REST_MILLIS = "rest_millis"
const val EXTRA_COUNT_UP = "count_up"
