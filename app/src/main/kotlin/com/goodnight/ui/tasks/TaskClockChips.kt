package com.goodnight.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.data.db.ProfileEntity
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon

/** 时钟 chip 行的标识(布局测试用它取行 bounds,断言不折行/不溢出) */
internal const val TASK_CLOCK_ROW_TAG = "task_clock_row"

/**
 * 时钟 chip 的边框(设计 §5 拍板 5):**通用时钟 = 淡色边框**(outlineVariant,低对比),
 * 专属时钟 = 更实的 outline。抽成纯函数 —— Compose 测试取不到边框颜色,布局测试直接断言颜色。
 */
internal fun clockBorder(generic: Boolean, scheme: ColorScheme): BorderStroke =
    BorderStroke(1.dp, if (generic) scheme.outlineVariant else scheme.outline)

/**
 * v2.2 Task 3:卡片上的可用时钟 chip 行 —— 点 chip 即开始(任务由调用方按卡片补上);
 * 末尾恒有「+ 添加时钟」。
 *
 * **横向滚动**:一行容纳不下就滚,既不折行(卡片高度不随时钟数增长)也不撑宽卡片
 * (长任务名 + 多时钟也不会溢出)。宽度由调用方给的 [modifier] 决定,不给则占满可用宽。
 */
@Composable
internal fun TaskClockChipsRow(
    clocks: TaskClocks,
    onClockClick: (ProfileEntity) -> Unit,
    onAddClock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(TASK_CLOCK_ROW_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        clocks.all.forEach { clock ->
            ClockChip(clock, clockBorder(generic = clock.taskId == null, scheme = scheme)) { onClockClick(clock) }
        }
        AssistChip(
            onClick = onAddClock,
            label = {
                Text(
                    stringResource(R.string.task_add_clock),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            },
            leadingIcon = { PathIcon(IconPaths.PLUS, size = 16.dp, contentDescription = null) },
        )
    }
}

/** 单个时钟 chip:名字单行(行内不限宽,由 chip 行滚动容纳);边框区分专属/通用 */
@Composable
private fun ClockChip(clock: ProfileEntity, border: BorderStroke, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                clock.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        border = border,
    )
}

/**
 * 卡片底部的图例:说明淡色边框的含义(设计 §5 拍板 5)。跟着每张卡片走(固定视觉词典),
 * 用一个与通用 chip 同款的小色块 + 一句短文案,不占整行宽度也不折行。
 */
@Composable
internal fun TaskClockLegend(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 18.dp, height = 12.dp)
                .border(clockBorder(generic = true, scheme = scheme), RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.task_clock_legend),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
