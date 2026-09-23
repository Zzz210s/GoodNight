package com.goodnight.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.data.db.TaskEntity
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 卡片行高**下限** + 列表间距。行高按内容自适应([Modifier.heightIn]):大字号/字体缩放或
 * 已完成行两行内容不再被 Card 裁掉;拖动落点则按行实测高度换算,与真实行高保持一致。
 */
internal val TaskRowHeight = 64.dp
internal val TaskRowSpacing = 12.dp
private val HHmm = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
internal fun SectionLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * 进行中任务行:**长按后拖动**重排(松手按位移换算落点下标交给 [onMove]),点击进改名,
 * 勾选移入已完成,垃圾桶删除。位移不足一行则不动作(原位回弹)。
 */
@Composable
internal fun ActiveTaskRow(
    task: TaskEntity,
    index: Int,
    count: Int,
    onMove: (Int, Int) -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { TaskRowSpacing.toPx() }
    val fallbackStepPx = with(density) { (TaskRowHeight + TaskRowSpacing).toPx() }
    // 实测行高(首帧前为 0,退化到基准值);步进 = 本行高度 + 间距,行高变高时落点不漂移
    var rowHeightPx by remember(task.id) { mutableFloatStateOf(0f) }
    var dragY by remember(task.id) { mutableFloatStateOf(0f) }
    val dragging = dragY != 0f
    Card(
        onClick = onRename,
        border = if (dragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TaskRowHeight)
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .graphicsLayer {
                translationY = dragY
                if (dragging) {
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .pointerInput(task.id, index, count) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, delta -> change.consume(); dragY += delta.y },
                    onDragEnd = {
                        // 实时读实测行高后的步进:pointerInput 的 lambda 不随重测重启,靠闭包快照会一直
                        // 用首帧的基准步进(行高自适应就白改了)。dragTargetIndex 已夹取落点。
                        val step = dragStepPx(rowHeightPx, spacingPx, fallbackStepPx)
                        val target = dragTargetIndex(index, count, dragY, step)
                        dragY = 0f
                        if (target != index) onMove(index, target)
                    },
                    onDragCancel = { dragY = 0f },
                )
            },
    ) {
        TaskRowBody(task.title, checked = false, onToggle = onToggle, onDelete = onDelete)
    }
}

/** 已完成任务行:不参与拖动,勾选取消完成回到进行中;标题加删除线以示归档 */
@Composable
internal fun DoneTaskRow(task: TaskEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = TaskRowHeight)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = true, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                task.doneAt?.let {
                    val stamp = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(HHmm)
                    Text(
                        stringResource(R.string.task_done_at, stamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                PathIcon(
                    IconPaths.TRASH, size = 20.dp,
                    contentDescription = stringResource(R.string.task_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 行内容:拖动把手(视觉提示)+ 勾选 + 标题 + 删除。标题单行省略:100 字标题也不再折行裁字 */
@Composable
private fun TaskRowBody(title: String, checked: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PathIcon(
            IconPaths.MENU, size = 20.dp,
            contentDescription = stringResource(R.string.task_drag),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onDelete) {
            PathIcon(
                IconPaths.TRASH, size = 20.dp,
                contentDescription = stringResource(R.string.task_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
