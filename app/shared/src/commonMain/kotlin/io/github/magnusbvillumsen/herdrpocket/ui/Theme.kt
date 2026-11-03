package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.platform.ThemeMode

val LocalDark = staticCompositionLocalOf { false }

@Composable
fun HerdrTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme: ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun statusColor(status: AgentStatus): Color {
    val dark = LocalDark.current
    return when (status) {
        AgentStatus.Working -> if (dark) Color(0xFF7FB3FF) else Color(0xFF1E62D0)
        AgentStatus.Blocked -> if (dark) Color(0xFFFFB066) else Color(0xFFC25400)
        AgentStatus.Done -> if (dark) Color(0xFF7BD88F) else Color(0xFF1E8A3C)
        AgentStatus.Idle, AgentStatus.Unknown -> MaterialTheme.colorScheme.outline
    }
}

val starColor: Color = Color(0xFFF2B705)
