package com.goodnight.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.goodnight.R
import com.goodnight.data.db.ProfileEntity
import com.goodnight.ui.settings.ProfileEditDialog

/**
 * v2.2 Task 3:「+ 添加时钟」弹窗 —— 在该任务下新建。
 *
 * 复用时钟管理页的 [ProfileEditDialog](名称/模式/工作/休息分钟 + 重名校验,全应用仅此一份输入 UI),
 * 只把重名预校验的范围收窄到**该任务的同作用域时钟**(即 [scoped]):作用域内唯一的口径见
 * [com.goodnight.data.ProfileRepository.create] —— taskId 相同才算同一作用域,
 * 通用时钟与任务专属时钟可以同名(通用是另一层作用域,不能据此挡住新建)。
 */
@Composable
internal fun TaskClockDialog(
    scoped: List<ProfileEntity>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, work: Int, rest: Int, mode: Int) -> Unit,
    error: TaskClockError? = null,
) {
    ProfileEditDialog(
        initial = null,
        existing = scoped,
        title = stringResource(R.string.new_clock),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        // 预校验(dialog 侧)与真正的唯一校验(仓库内)可能看到不同的数据源瞬态:
        // 仓库拒绝时必须把原因说出来,不能只留个不动的弹窗
        error = error?.let { stringResource(R.string.task_clock_name_taken) },
    )
}
