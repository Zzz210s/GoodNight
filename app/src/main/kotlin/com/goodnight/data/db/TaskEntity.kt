package com.goodnight.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v2.1 Task 1:任务表。每个工作段可绑定一个任务(`focus_session.taskId`,可空),
 * 统计由「时长」升级为「投入结构」。
 *
 * `done`/`doneAt` 成对:未完成时 `doneAt = null`,归档时写入完成时刻;
 * 已完成列表按 `doneAt` 倒序。`sortOrder` 为手工排序位(新任务取 maxSortOrder + 1 追加到末尾)。
 * 索引 `(done, sortOrder)` 同时服务两个列表查询(未完成按 sortOrder,已完成按 doneAt)。
 */
@Entity(tableName = "task", indices = [Index(value = ["done", "sortOrder"])])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /**
     * 完成标记。defaultValue 必须与迁移 DDL 的 `DEFAULT 0` 一致:
     * Room 打开已迁移库时逐列校验默认值,不匹配会抛 "Migration didn't properly handle"。
     */
    @ColumnInfo(defaultValue = "0") val done: Boolean = false,
    val createdAt: Long,
    val doneAt: Long? = null,
    val sortOrder: Long,
)
