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
)
