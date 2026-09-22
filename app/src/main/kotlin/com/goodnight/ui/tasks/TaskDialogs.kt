package com.goodnight.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.data.TaskRepository

/**
 * 新建/改名共用输入弹窗。拒绝(空/纯空白/超 [TaskRepository.MAX_TITLE])时就地提示并留在弹窗里;
 * 是否关闭由调用方按 [TaskListViewModel.inputErrorFor] 判定(与 VM 同一口径)。
 */
@Composable
internal fun TaskInputDialog(
    titleRes: Int,
    initial: String,
    error: TaskInputError?,
    onClearError: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onClearError() },
                    singleLine = true,
                    isError = error != null,
                    label = { Text(stringResource(R.string.task_title_hint)) },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (error != null) {
                    Text(
                        stringResource(
                            if (error == TaskInputError.BLANK) R.string.task_title_blank
                            else R.string.task_title_too_long,
                            TaskRepository.MAX_TITLE,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 删除确认:文案含该任务已记录的分钟数(时间账保留为未绑定,不丢数据) */
@Composable
internal fun TaskDeleteDialog(prompt: TaskDeletePrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_delete_title)) },
        text = { Text(stringResource(R.string.task_delete_confirm, prompt.minutes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
