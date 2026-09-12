package com.mremotedroid.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Sky = Color(0xFF38BDF8)
private val SkyDark = Color(0xFF0EA5E9)
private val Slate = Color(0xFF1E293B)

private val LightColors = lightColorScheme(
    primary = SkyDark,
    secondary = Slate
)

private val DarkColors = darkColorScheme(
    primary = Sky,
    secondary = Color(0xFF94A3B8)
)

@Composable
fun MRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
