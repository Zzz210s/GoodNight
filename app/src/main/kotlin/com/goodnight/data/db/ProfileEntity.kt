package com.goodnight.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 计时模式(DAO/DB 存 Int):COUNTDOWN = 倒计时(既有默认),COUNTUP = 正计时 */
object ProfileMode {
    const val COUNTDOWN = 0
    const val COUNTUP = 1
}

@Entity(tableName = "profile", indices = [Index(value = ["name"], unique = true)])
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val workMinutes: Int,
    val restMinutes: Int,
    val createdAt: Long,
    /**
     * v2 迁移新增列(version 1 -> 2,ALTER ... DEFAULT 0 逐行补 0,非破坏)。
     * defaultValue 必须与迁移 DDL 一致:Room 打开已迁移库时会逐列校验默认值,
     * 实体缺省与 SQL 缺省不匹配会抛 "Migration didn't properly handle"。
     */
    @ColumnInfo(defaultValue = "0") val mode: Int = ProfileMode.COUNTDOWN,
)
