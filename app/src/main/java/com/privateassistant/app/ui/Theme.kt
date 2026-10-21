package com.privateassistant.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D5E53),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EAE2),
    onPrimaryContainer = Color(0xFF153A31),
    secondary = Color(0xFF536357),
    secondaryContainer = Color(0xFFE2EBDD),
    tertiary = Color(0xFF496476),
    background = Color(0xFFF6F8F5),
    onBackground = Color(0xFF19241F),
    surface = Color(0xFFF6F8F5),
    onSurface = Color(0xFF19241F),
    surfaceVariant = Color(0xFFE7ECE6),
    onSurfaceVariant = Color(0xFF56635B),
    outline = Color(0xFF758278),
    outlineVariant = Color(0xFFD7DFD5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2D1BF),
    onPrimary = Color(0xFF06382C),
    primaryContainer = Color(0xFF214E40),
    onPrimaryContainer = Color(0xFFBDEBD9),
    secondary = Color(0xFFBCCCB6),
    secondaryContainer = Color(0xFF3D4B39),
    background = Color(0xFF101713),
    onBackground = Color(0xFFDFE6DD),
    surface = Color(0xFF101713),
    onSurface = Color(0xFFDFE6DD),
    surfaceVariant = Color(0xFF354239),
    onSurfaceVariant = Color(0xFFBBC9BD),
    outline = Color(0xFF8B9A8D),
    outlineVariant = Color(0xFF3B493F),
)

@Suppress("DEPRECATION")
@Composable
fun PrivateAssistantTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = colors.background.toArgb()
        window.navigationBarColor = colors.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
