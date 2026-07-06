package com.roomvibe.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    // Brand accent: orange on a dark theme (replaces the old blue)
    primary = Color(0xFFFF7A1A),
    onPrimary = Color(0xFF241300),
    primaryContainer = Color(0xFF3A2410),
    onPrimaryContainer = Color(0xFFFFE1C6),
    secondary = Color(0xFFFFA968),
    onSecondary = Color(0xFF2A1400),
    secondaryContainer = Color(0xFF2A2D31),
    onSecondaryContainer = Color(0xFFECEFF1),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF1B1D21),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF2A2D31),
    onSurfaceVariant = Color(0xFFC4C7CA),
    outline = Color(0xFF44474B),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF3A0000)
)

@Composable
fun ThermoLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
