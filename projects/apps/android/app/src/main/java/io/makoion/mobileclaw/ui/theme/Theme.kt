package io.makoion.mobileclaw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = ClawGreen,
    secondary = ClawGold,
    background = ClawSand,
    surface = ClawSurface,
    onPrimary = ClawSurface,
    onSecondary = ClawInk,
    onBackground = ClawInk,
    onSurface = ClawInk,
    onSurfaceVariant = ClawInk.copy(alpha = 0.72f),
    outline = ClawMist,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF58C3A9),
    secondary = Color(0xFFE0B15A),
    background = Color(0xFF11130F),
    surface = Color(0xFF171914),
    onPrimary = Color(0xFF04110D),
    onSecondary = Color(0xFF261A02),
    onBackground = Color(0xFFF1F2EA),
    onSurface = Color(0xFFF1F2EA),
    onSurfaceVariant = Color(0xFFC7CCBF),
)

@Composable
fun MobileClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
