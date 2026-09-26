package com.fourj.iptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val FourJColorScheme = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF00325B),
    secondary = Color(0xFF9CCAFF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE4E9F0),
    surface = Color(0xFF141B24),
    onSurface = Color(0xFFE4E9F0),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0909),
)

/**
 * Layout scale for the current device.
 *
 * A layout that works on a phone is unreadable across a room, and one that works on a TV is
 * comically oversized on a phone. Rather than maintain two designs, the same components read this
 * and adjust their sizes.
 */
@Immutable
data class UiScale(
    val isTelevision: Boolean,
    val isTablet: Boolean,
) {
    val channelRowHeightDp: Int get() = if (isTelevision) 76 else 64
    val categoryTabHeightDp: Int get() = if (isTelevision) 56 else 44
    val titleScale: Float get() = if (isTelevision) 1.25f else 1f
    val bodyScale: Float get() = if (isTelevision) 1.15f else 1f
    val logoSizeDp: Int get() = if (isTelevision) 64 else 48
    val horizontalMarginDp: Int get() = if (isTelevision) 48 else 16
}

val LocalUiScale = staticCompositionLocalOf { UiScale(isTelevision = false, isTablet = false) }

@Composable
fun FourJTheme(
    uiScale: UiScale = UiScale(isTelevision = false, isTablet = false),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalUiScale provides uiScale) {
        MaterialTheme(colorScheme = FourJColorScheme, content = content)
    }
}
