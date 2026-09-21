package com.goodnight.data.db

import androidx.room.Entity

@Entity(tableName = "daily_total", primaryKeys = ["date", "profileId"])
data class DailyTotalEntity(
    val date: String,        // yyyy-MM-dd, 本地时区
    val profileId: Long,
    val workMillis: Long,
    val updatedAt: Long,
)
