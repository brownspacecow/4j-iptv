package com.iptv.fourj.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TiViMateColors = darkColorScheme(
    primary = Color(0xFF00B4D8),
    secondary = Color(0xFF90E0EF),
    background = Color(0xFF0F0F1A),
    surface = Color(0xFF1A1A2E),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF252540),
    onSurfaceVariant = Color(0xFFA0A0B0),
    outline = Color(0xFF3A3A50)
)

@Composable
fun FourJIptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TiViMateColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
