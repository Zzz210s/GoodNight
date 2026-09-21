package com.goodnight.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.goodnight.R
import com.goodnight.ui.morph.IconPaths
import com.goodnight.ui.morph.PathIcon

/** v1.9.9 相位指示:仅图标(替代文本,不并列)。空闲=月牙,工作中=火焰,休息中=咖啡(IconPaths 单源)。
 * 相位文案由 contentDescription 承载(TalkBack 可读),不渲染可见文本。 */
@Composable
internal fun PhaseIndicator(phaseRes: Int) {
    val d = when (phaseRes) {
        R.string.state_work -> IconPaths.PHASE_WORK
        R.string.state_rest -> IconPaths.PHASE_REST
        else -> IconPaths.PHASE_IDLE
    }
    PathIcon(
        d = d,
        size = 18.dp,
        contentDescription = stringResource(phaseRes),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
