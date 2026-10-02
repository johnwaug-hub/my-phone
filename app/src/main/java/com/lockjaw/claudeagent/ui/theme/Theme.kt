// ui/theme/Theme.kt
package com.lockjaw.claudeagent.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFFF6B35)
val OrangeDim = Color(0xFFCC5520)
val Background = Color(0xFF0A0A0A)
val Surface = Color(0xFF111111)
val SurfaceVariant = Color(0xFF1A1A1A)
val Border = Color(0xFF2A2A2A)
val TextPrimary = Color(0xFFEEEEEE)
val TextSecondary = Color(0xFF888888)
val TextDim = Color(0xFF444444)
val Green = Color(0xFF44FF88)
val Red = Color(0xFFFF4444)

private val AgentColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = OrangeDim,
    secondary = Color(0xFF888888),
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Red,
    outline = Border
)

@Composable
fun ClaudeAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgentColorScheme,
        content = content
    )
}
