package com.goodnight.ui.settings

import com.goodnight.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode

// 自 SettingsScreen 拆出(200 行规则):编辑/新建共用的配置输入对话框。
// internal 而非 private:SettingsScreen.kt 跨文件调用,app 模块内可见即止。[error] 为非空时
// 在输入项下方提示仓库侧拒绝的原因(与对话框内的重名预校验互为兜底)。
//
// v2.2 Task 5:[scopes] 非空时多一行「归属」选择器(通用 / 某任务)—— 受控组件:选中的作用域
// 由调用方持有,因为重名预校验的 [existing] 必须就是**目标作用域**的时钟集合(指针 2)。
// [scopes] 缺省为 null:任务卡片上的「+ 添加时钟」已由卡片定好归属,不再多问一次。
@Composable
internal fun ProfileEditDialog(
    initial: ProfileEntity?,
    existing: List<ProfileEntity>,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, work: Int, rest: Int, mode: Int) -> Unit,
    error: String? = null,
    scopes: List<ClockScope>? = null,
    scopeTaskId: Long? = null,
    onScopeChange: (Long?) -> Unit = {},
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var work by remember { mutableStateOf((initial?.workMinutes ?: 25).toString()) }
    var rest by remember { mutableStateOf((initial?.restMinutes ?: 5).toString()) }
    // Task 7 / #10:新建缺省倒计时;编辑预填当前 profile 模式
    var mode by remember { mutableStateOf(initial?.mode ?: ProfileMode.COUNTDOWN) }
    // 重名只算**同一作用域**内的时钟(v2.2 起「不同任务下同名」合法):[existing] 已由调用方
    // 按目标作用域筛好,判定口径与 ProfileRepository.byNameInScope 一致
    val trimmed = name.trim()
    val nameTaken = existing.any { it.name == trimmed && (initial == null || it.id != initial.id) }
    val valid = name.isNotBlank() && !nameTaken &&
        work.toIntOrNull() in 1..180 && rest.toIntOrNull() in 1..60
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name_label)) })
                Text(stringResource(R.string.mode_label), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow {
                    modeOptions.forEachIndexed { index, (value, labelRes) ->
                        SegmentedButton(
                            selected = mode == value,
                            onClick = { mode = value },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modeOptions.size),
                        ) {
                            Text(stringResource(labelRes))
                        }
                    }
                }
                OutlinedTextField(value = work, onValueChange = { work = it }, label = { Text(stringResource(R.string.work_minutes)) })
                OutlinedTextField(value = rest, onValueChange = { rest = it }, label = { Text(stringResource(R.string.rest_minutes)) })
                scopes?.let { options -> ScopePicker(options, scopeTaskId, onScopeChange) }
                // v2.2 Task 3:仓库侧拒绝(重名预校验的数据源瞬时为空时)也要有可见提示
                error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(name.trim(), work.toInt(), rest.toInt(), mode) },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 模式选择选项(顺序 = SegmentedButton 位序,与 ProfileMode 常量解耦) */
private val modeOptions = listOf(ProfileMode.COUNTDOWN to R.string.mode_countdown, ProfileMode.COUNTUP to R.string.mode_countup)

/**
 * 归属选择器:一行可横滚的 FilterChip(任务可能很多,折行会把对话框撑得很高)。
 * 选中「通用」= taskId null,即设计里的通用时钟层。
 */
@Composable
private fun ScopePicker(options: List<ClockScope>, selected: Long?, onChange: (Long?) -> Unit) {
    Text(stringResource(R.string.clock_scope_label), style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { scope ->
            FilterChip(
                selected = scope.taskId == selected,
                onClick = { onChange(scope.taskId) },
                label = {
                    Text(
                        scope.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
