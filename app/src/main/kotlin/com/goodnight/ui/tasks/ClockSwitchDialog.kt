package com.goodnight.ui.tasks

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.goodnight.R

/**
 * v2.2 Task 4:计时中换时钟的确认弹窗(设计 §4 拍板 1:「终止当前并开始新的?」)。
 *
 * 只有 [onConfirm] 才发命令;关闭 / 取消走 [onDismiss],必须保证零副作用
 * (未确认前不发任何命令 —— 由 [ClockSwitchPrompt] 的状态机保证,这里不做任何动作)。
 */
@Composable
internal fun ClockSwitchDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clock_switch_title)) },
        text = { Text(stringResource(R.string.clock_switch_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
