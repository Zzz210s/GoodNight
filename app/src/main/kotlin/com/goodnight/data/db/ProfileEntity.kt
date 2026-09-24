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

@Entity(tableName = "profile", indices = [Index("taskId"), Index("name")])
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
    /**
     * v2.2 新增列(version 4 -> 5):该时钟归属的任务(`task.id`),null = 通用时钟。
     * 无 DEFAULT:存量行升级后自动为 NULL(通用时钟),账目语义不变。
     * 删除任务时由 [com.goodnight.data.TaskRepository.deleteTask] 在同一事务内置 NULL。
     */
    val taskId: Long? = null,
    /**
     * v2.2 新增列:已停用。有历史引用的时钟被删时归档(行保留,历史/报表仍能解析其名),
     * 列表查询一律排除。defaultValue 必须与迁移 DDL 的 DEFAULT 0 逐字一致。
     */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)
