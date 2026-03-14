package io.makoion.mobileclaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun MobileClawTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        // The current shell still uses a number of light-theme-specific ink colors.
        // Keep the app on the light palette until the UI is fully semantic-color driven.
        colorScheme = LightColors,
        content = content,
    )
}
