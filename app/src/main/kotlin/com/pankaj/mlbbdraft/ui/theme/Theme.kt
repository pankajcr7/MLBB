package com.pankaj.mlbbdraft.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark-first on purpose: this app is used next to a game, often in a dark room, and
 * Phase 1 renders the same colours in an overlay on top of MLBB.
 */
private val Blue = Color(0xFF6FA8FF)
private val Teal = Color(0xFF4ED2C0)
private val Amber = Color(0xFFFFC857)
private val Red = Color(0xFFFF6B6B)
private val Ink = Color(0xFF0F1117)
private val Surface = Color(0xFF171A23)
private val SurfaceHigh = Color(0xFF1F2430)

internal val AllyBlue = Color(0xFF3D7DD8)
internal val EnemyRed = Color(0xFFD8503D)
internal val Good = Teal
internal val Warn = Amber
internal val Bad = Red

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Ink,
    secondary = Teal,
    onSecondary = Ink,
    tertiary = Amber,
    background = Ink,
    onBackground = Color(0xFFE6E9F0),
    surface = Surface,
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFA9B1C4),
    error = Red,
    outline = Color(0xFF3A4152),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    secondary = Color(0xFF10796C),
    tertiary = Color(0xFF9A6B00),
)

@Composable
fun MlbbDraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
