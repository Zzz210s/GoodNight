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

/** v2.1 Task 7:绑定/解绑当前工作段的任务(计时页 chip);null 用哨兵 [NO_TASK_ID] 表达 */
const val ACTION_SET_TASK = "com.goodnight.action.SET_TASK"

/**
 * v2.2 Task 4:计时中换时钟 = 一条命令内「终止当前段 + 按新时钟开始」(设计 §4 拍板 1)。
 * 载荷与 [ACTION_START] 同形;不新增切点类型,引擎动作为 `reset()` 后 `start()`。
 */
const val ACTION_SWITCH_CLOCK = "com.goodnight.action.SWITCH_CLOCK"

/** v1.10.11:通知"对号"确认(清除提醒通知) */
const val ACTION_ACK = "com.goodnight.action.ACK"

const val EXTRA_PROFILE_ID = "profile_id"
const val EXTRA_WORK_MILLIS = "work_millis"
const val EXTRA_REST_MILLIS = "rest_millis"
const val EXTRA_COUNT_UP = "count_up"

/** 任务 id 的 intent extra;任务 id 自增且从 1 起,故 -1 可安全表示「不绑定」 */
const val EXTRA_TASK_ID = "task_id"
const val NO_TASK_ID = -1L
