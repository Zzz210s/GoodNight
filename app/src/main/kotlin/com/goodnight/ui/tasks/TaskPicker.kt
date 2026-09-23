package com.goodnight.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.data.db.TaskEntity
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon

/**
 * v2.1 Task 7:计时页当前任务 chip。未绑定显示 [R.string.task_unbound]。
 *
 * 空闲(无工作段)时禁点:此时没有可绑定的段(引擎 `setTask` 无快照即 no-op),
 * 点了只会白拉起一次前台服务、并留下"选了却没生效"的错觉。
 *
 * 宽度由调用方决定:chip 在卡片内容流内与徽标同排、与大数字不同排(见 [com.goodnight.ui.home.TimerCard]),
 * 故不再限宽 —— 名字多长都由调用方给的可用宽 + 省略号收敛,不依赖任何"屏宽/字号/数字位数"假设。
 */
@Composable
internal fun TaskChip(
    taskTitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                taskTitle ?: stringResource(R.string.task_unbound),
                // labelLarge(14sp):chip 不再限宽,无需为迁就限宽降字号(可读性/可点性优先)
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
    )
}

/**
 * v2.1 Task 7:任务选择器 —— 任务列表 + 「不绑定」一项(当前绑定打勾)。
 * 选中即回调(调用方发 SET_TASK 命令);选择器开关由调用方按 VM 状态挂载。
 *
 * [tasks] 应含当前绑定任务(即便它已不在进行中列表里,如已完成任务),否则会出现
 * "chip 显示某任务、列表里却哪一项都不打勾"的误导;[selectedId] 应传**能解析到实体**的
 * 绑定 id(任务已被删时为 null)—— 与 chip 的文案口径一致,此时「不绑定」打勾。
 */
@Composable
internal fun TaskPicker(
    tasks: List<TaskEntity>,
    selectedId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_picker_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (tasks.isEmpty()) {
                    Text(
                        stringResource(R.string.tasks_empty_active),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tasks.forEach { t -> PickerRow(t.title, t.id == selectedId) { onPick(t.id) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                PickerRow(stringResource(R.string.task_picker_unbind), selectedId == null) { onPick(null) }
            }
        },
        // Material 约定:取消/关闭这类非主操作放 dismissButton;M3 的 AlertDialog 签名要求
        // confirmButton 非空,本弹窗没有主操作故留空槽(不渲染任何按钮)
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickerRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) {
            PathIcon(
                d = IconPaths.CHECK,
                size = 18.dp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
