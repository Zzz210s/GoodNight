package com.goodnight.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.goodnight.R
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon
import com.goodnight.ui.theme.BarAnim
import com.goodnight.ui.theme.rememberAnimationsEnabled

/** 测试钩子:时钟子行的语义 tag(与 [TASK_CLOCK_ROW_TAG] 一样只给同模块测试用) */
internal const val REPORT_CLOCK_ROW_TAG = "report_clock_row"

/** 主行与子行共用的列网格:标签 1f — 条形 1.5f — 时长/占比 1.2f — 行尾 18dp。 */
private const val LABEL_WEIGHT = 1f
private const val BAR_WEIGHT = 1.5f
private const val META_WEIGHT = 1.2f

/** 行尾固定宽度:展开箭头(或空位),恒留以保证同区块各行列宽一致。 */
private val TRAILING = 18.dp

/** 子行名字相对主行标签的层级缩进。 */
private val INDENT = 18.dp

/**
 * 「按任务」主行:标签 + 比例条 + 时长/次数/占比 + 展开箭头(仅有明细的行有,其余留同宽空位)。
 * 箭头放行尾,标签基线因此与兄弟区块(趋势、各时钟合计)同在 x=0。条形以本区最长任务为满格。
 */
@Composable
internal fun TaskBarRow(
    label: String,
    millis: Long,
    count: Int,
    percent: Int,
    max: Long,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val detailsLabel = stringResource(R.string.report_clock_details)
    Row(
        Modifier.fillMaxWidth()
            // 整行是展开热区:至少 48dp 高,并带角色/点击提示给无障碍
            .heightIn(min = 48.dp)
            .then(
                if (expandable) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = detailsLabel,
                    onClick = onToggle,
                ) else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(LABEL_WEIGHT),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.weight(BAR_WEIGHT).height(12.dp).padding(end = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        ) {
            val target = (millis.toFloat() / max).coerceIn(0f, 1f)
            val anim = rememberAnimationsEnabled()
            val fraction by animateFloatAsState(
                targetValue = target,
                animationSpec = if (anim) BarAnim else tween(0),
                label = "taskBarFraction",
            )
            Box(
                Modifier.fillMaxWidth(fraction).height(12.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
            )
        }
        Column(Modifier.weight(META_WEIGHT)) {
            Text(
                localizedDur(millis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.report_task_meta, count, percent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box(Modifier.size(TRAILING), contentAlignment = Alignment.Center) {
            if (expandable) {
                PathIcon(
                    IconPaths.CHEVRON_DOWN,
                    size = TRAILING,
                    contentDescription = detailsLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
        }
    }
}

/**
 * 时钟子行:名字缩进 [INDENT],数值列复用主行的列网格(条形位置留空),所以竖着扫
 * 时长/占比与父行同列;占比是**任务内**口径,单独文案(见 report_clock_meta)。
 *「未知时钟」= profile 行已被真删。
 */
@Composable
internal fun ClockBarRow(label: String, millis: Long, count: Int, percent: Int) {
    Row(
        Modifier.fillMaxWidth().testTag(REPORT_CLOCK_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(LABEL_WEIGHT).padding(start = INDENT),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 条形占位:不画内容,只为把后面的数值推回与父行同列
        Spacer(Modifier.weight(BAR_WEIGHT))
        Column(Modifier.weight(META_WEIGHT)) {
            Text(
                localizedDur(millis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.report_clock_meta, count, percent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(TRAILING))
    }
}
