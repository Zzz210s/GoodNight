package com.goodnight.ui.tasks

import com.goodnight.data.db.ProfileEntity

/**
 * v2.2 Task 3:一张任务卡片上的时钟 —— 该任务**专属**(taskId = 本任务)与**通用**(taskId = null)
 * 两组。UI 据此给通用时钟加淡色边框并在卡片底部配图例(设计 §5 拍板 5);
 * [all] 保持仓库给的顺序(专属在前、通用在后,各自 createdAt/id 升序)。
 *
 * 单独成文件只为 [TaskListViewModel] 守住 200 行约束;语义上与它同属任务页。
 */
data class TaskClocks(
    val specific: List<ProfileEntity> = emptyList(),
    val generic: List<ProfileEntity> = emptyList(),
) {
    val all: List<ProfileEntity> get() = specific + generic
}

/** 拆两组:可用集合里 taskId != null 的是专属,== null 的是通用 */
internal fun splitClocks(list: List<ProfileEntity>) = TaskClocks(
    specific = list.filter { it.taskId != null },
    generic = list.filter { it.taskId == null },
)
