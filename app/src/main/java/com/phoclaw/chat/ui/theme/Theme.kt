package com.phoclaw.chat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Cyan = Color(0xFF00A8B5)
private val CyanLight = Color(0xFF7FE3EA)
private val Ink = Color(0xFF10161A)
private val Surface = Color(0xFF151C21)

private val DarkScheme = darkColorScheme(
    primary = CyanLight,
    onPrimary = Ink,
    primaryContainer = Color(0xFF004F56),
    onPrimaryContainer = CyanLight,
    secondary = Color(0xFF9FD3D8),
    background = Ink,
    onBackground = Color(0xFFE3E8EA),
    surface = Surface,
    onSurface = Color(0xFFE3E8EA),
    surfaceVariant = Color(0xFF242E34),
    onSurfaceVariant = Color(0xFFB8C4C9),
    error = Color(0xFFFF8A80)
)

private val LightScheme = lightColorScheme(
    primary = Cyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6EDF1),
    onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF4A6266),
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4EBEE),
    error = Color(0xFFB3261E)
)

@Composable
fun PhoClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
