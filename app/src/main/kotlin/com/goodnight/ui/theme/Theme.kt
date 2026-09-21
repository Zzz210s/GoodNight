package com.goodnight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.goodnight.R

/**
 * 应用配色包(v1.8):从 GitHub 高星配色库(nordtheme/nord、yeun/open-color、
 * Google Material Colors 等)提炼的日光色方案,统一浅色。设置页可切换。
 */
enum class ThemePack(val labelRes: Int, val primary: Color, val primaryContainer: Color) {
    EMBER(R.string.pack_ember, Color(0xFFF4511E), Color(0xFFFFDBCF)),
    GREEN(R.string.pack_green, Color(0xFF388E3C), Color(0xFFC8E6C9)),   // 浅绿(Material Green / Open Color green)
    BLUE(R.string.pack_blue, Color(0xFF1E88E5), Color(0xFFBBDEFB)),
    PURPLE(R.string.pack_purple, Color(0xFF8E24AA), Color(0xFFE1BEE7)),
    ROSE(R.string.pack_rose, Color(0xFFE91E63), Color(0xFFF8BBD0)),
    TEAL(R.string.pack_teal, Color(0xFF00897B), Color(0xFFB2DFDB)),
    NORD(R.string.pack_nord, Color(0xFF5E81AC), Color(0xFFD8DEE9)),    // nordtheme:nord
    ;

    companion object {
        fun fromName(name: String?): ThemePack =
            entries.firstOrNull { it.name == name } ?: EMBER
    }
}

private fun onContainer(container: Color): Color = lerp(container, Color.Black, 0.42f)

fun colorSchemeFor(pack: ThemePack): androidx.compose.material3.ColorScheme = lightColorScheme(
    primary = pack.primary,
    onPrimary = Color.White,
    primaryContainer = pack.primaryContainer,
    onPrimaryContainer = onContainer(pack.primaryContainer),
    secondary = pack.primary,
    onSecondary = Color.White,
    tertiary = pack.primary,
    onTertiary = Color.White,
)

/** 主题:按配色包固定浅色(不再跟随系统深浅/动态取色);其余中性色由 M3 默认补齐 */
@Composable
fun EmberTheme(pack: ThemePack = ThemePack.EMBER, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorSchemeFor(pack), content = content)
}
