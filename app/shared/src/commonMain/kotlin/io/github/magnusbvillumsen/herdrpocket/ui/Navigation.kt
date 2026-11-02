package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey

sealed interface Screen {
    data object Agents : Screen
    data class Chat(val key: AgentKey) : Screen
    data object Settings : Screen
}

/** A tiny back stack; the platform's back button calls [back]. */
class Navigator(start: Screen = Screen.Agents) {
    var stack: List<Screen> by mutableStateOf(listOf(start))
        private set

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun open(screen: Screen) {
        if (current != screen) stack = stack + screen
    }

    /** Opens [screen] directly above the agent list, as a notification tap or a pairing link does. */
    fun reset(screen: Screen) {
        stack = if (screen == Screen.Agents) listOf(Screen.Agents) else listOf(Screen.Agents, screen)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        return true
    }
}
