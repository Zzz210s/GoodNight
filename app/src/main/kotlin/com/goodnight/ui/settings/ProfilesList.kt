package com.goodnight.ui.settings

import com.goodnight.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode

/**
 * v2.2 Task 5:时钟管理页的列表体 —— 「通用」段 + 各任务专属段(段头是任务标题)。
 *
 * 从 [ProfilesScreen] 拆出(200 行规则):屏幕文件只管对话框状态与删除流程,列表只负责画。
 * [LazyListScope] 扩展而非 @Composable 函数:段头 + 各段行要在**同一个 LazyColumn**里,
 * 拆成嵌套 Column 的话长列表会失去懒加载。
 */
internal fun LazyListScope.clockSectionItems(
    sections: List<ClockSection>,
    deleteMode: Boolean,
    selectedIds: Set<Long>,
    runningActiveId: Long?,
    onEdit: (ProfileEntity) -> Unit,
    onToggle: (Long) -> Unit,
) {
    sections.forEach { section ->
        // 空段不画段头:没通用时钟时列表不该顶着「通用时钟」四个字直接接任务段。
        // （两段都空时由调用方画「还没有时钟」空态）
        if (section.clocks.isEmpty()) return@forEach
        item(key = "section_${section.taskId}") { SectionHeader(section) }
        items(section.clocks, key = { it.id }) { p ->
            ClockCard(
                p = p,
                deleteMode = deleteMode,
                selected = p.id in selectedIds,
                runningActive = runningActiveId == p.id,
                onClick = { if (deleteMode) onToggle(p.id) else onEdit(p) },
            )
        }
    }
}

/** 段头:通用段用资源文案,专属段直接用任务标题(重命名后跟着变) */
@Composable
private fun SectionHeader(section: ClockSection) {
    Text(
        if (section.taskId == null) stringResource(R.string.clock_section_generic) else section.title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 单个时钟卡片。运行中的时钟两种模式下都不可点(选中/编辑都会把用户带进一条改不了的路);
 * 「至少保留 1 个」的门控在删除流程里(见 [planDeletion])—— 归档不受它限制,所以不再按
 * 「列表只剩一个」把卡片整体禁掉。
 */
@Composable
private fun ClockCard(
    p: ProfileEntity,
    deleteMode: Boolean,
    selected: Boolean,
    runningActive: Boolean,
    onClick: () -> Unit,
) {
    val canTouch = !runningActive
    Card(
        onClick = onClick,
        enabled = canTouch,
        border = if (deleteMode && selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall)
            val durBase = stringResource(R.string.durations_workrest, p.workMinutes, p.restMinutes)
            val durationText = if (p.mode == ProfileMode.COUNTUP)
                durBase + stringResource(R.string.mode_tag, stringResource(R.string.mode_countup)) else durBase
            Text(durationText, style = MaterialTheme.typography.bodyMedium)
            if (deleteMode) {
                Text(
                    if (selected) stringResource(R.string.selected_tag) else stringResource(R.string.tap_select_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (runningActive) {
                Text(stringResource(R.string.running_locked), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
