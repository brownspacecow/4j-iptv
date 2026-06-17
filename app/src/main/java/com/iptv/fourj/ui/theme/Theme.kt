package com.iptv.fourj.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TiViMateColors = darkColorScheme(
    primary = Color(0xFF00B8E6),
    secondary = Color(0xFF65D6FF),
    background = Color(0xFF090A12),
    surface = Color(0xFF12131F),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1B1D2B),
    onSurfaceVariant = Color(0xFF9B9CAC),
    outline = Color(0xFF2A2D3D)
)

@Composable
fun FourJIptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TiViMateColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
