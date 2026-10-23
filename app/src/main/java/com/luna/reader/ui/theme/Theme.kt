package com.luna.reader.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = LunaPrimary,
    onPrimary = LunaOnPrimary,
    primaryContainer = LunaPrimaryContainer,
    onPrimaryContainer = LunaOnPrimaryContainer,
    secondary = LunaSecondary,
    onSecondary = LunaOnSecondary,
    secondaryContainer = LunaSecondaryContainer,
    onSecondaryContainer = LunaOnSecondaryContainer,
    tertiary = LunaTertiary,
    onTertiary = LunaOnPrimary,
    tertiaryContainer = LunaTertiaryContainer,
    background = LunaSurface,
    onBackground = LunaOnSurface,
    surface = LunaSurface,
    onSurface = LunaOnSurface,
    surfaceVariant = LunaSurfaceContainerHigh,
    onSurfaceVariant = LunaOnSurfaceVariant,
    outline = LunaOutline,
    outlineVariant = LunaOutlineVariant
)

@Composable
fun LunaTheme(content: @Composable () -> Unit) {
    val colorScheme = LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = LunaSurface.toArgb()
                window.navigationBarColor = LunaSurface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LunaTypography,
        content = content
    )
}
