package com.goodnight.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.3 #6:单段专注记录(工作段结束/终止/切换时落库),供每日详情展示
 * "每段时间 开始~结束(时:分)"。跨午夜段在 00:00 切分为多段(每段同日期),
 * 故各日各时钟的段分钟合计 == 该日 daily_total(拆账口径一致)。
 */
@Entity(tableName = "focus_session")
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** 墙钟(epoch ms),本地时区;startAt/endAt 恒同一天(切分后) */
    val startAt: Long,
    val endAt: Long,
    /**
     * v2.1 Task 1:该段绑定的任务(`task.id`),null = 未绑定。
     * 旧库升级后既有段一律为 null;任务删除时由应用层事务置空(id 不留悬挂值)。
     */
    val taskId: Long? = null,
)
