package com.vplayer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Accent = Color(0xFFFF5C8A)
val AccentPurple = Color(0xFF7B5CFF)
val Ok = Color(0xFF3ECF8E)
val Bad = Color(0xFFFF5A5A)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0x33FF5C8A),
    onPrimaryContainer = Accent,
    secondary = AccentPurple,
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0D0F14),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1D2029),
    onSurfaceVariant = Color(0xFF888999),
    surfaceContainer = Color(0xFF151821),
    surfaceContainerHigh = Color(0xFF181B23),
    surfaceContainerLow = Color(0xFF12141B),
    outline = Color(0xFF2A2D36),
    outlineVariant = Color(0xFF1D2029),
    error = Bad,
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0x22FF5C8A),
    onPrimaryContainer = Color(0xFFC2185B),
    secondary = AccentPurple,
    background = Color(0xFFF6F7FA),
    surface = Color(0xFFF6F7FA),
    surfaceVariant = Color(0xFFE9EBF1),
    onSurfaceVariant = Color(0xFF666877),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerLow = Color.White,
    outline = Color(0xFFD5D8E0),
    outlineVariant = Color(0xFFE6E8EE),
    error = Bad,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** 封面占位渐变（与设计稿一致） */
val PosterGradients = listOf(
    listOf(Color(0xFF3A4CC2), Color(0xFF151A3D)),
    listOf(Color(0xFFC2513A), Color(0xFF3D1715)),
    listOf(Color(0xFF2FA58A), Color(0xFF11302A)),
    listOf(Color(0xFFB33AA2), Color(0xFF2D1238)),
    listOf(Color(0xFFC9A23A), Color(0xFF3D3115)),
    listOf(Color(0xFF3A8EC9), Color(0xFF102A3D)),
)

fun posterBrush(seed: String): Brush =
    Brush.linearGradient(PosterGradients[(seed.hashCode() and 0x7fffffff) % PosterGradients.size])

val HeroBrush = Brush.linearGradient(listOf(Accent, AccentPurple, Color(0xFF2B2E8A)))

@Composable
fun AppTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, content = content)
}
