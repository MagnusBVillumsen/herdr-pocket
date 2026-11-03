package io.github.magnusbvillumsen.herdrpocket.platform

import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import kotlinx.coroutines.flow.StateFlow

/** Where the hub URL and token live; SharedPreferences on Android. */
interface ConfigStore {
    val config: StateFlow<HubConfig?>
    fun save(config: HubConfig)
}

enum class ThemeMode { System, Light, Dark }

/** The app's light or dark choice, kept apart from the hub config. */
interface ThemeStore {
    val theme: StateFlow<ThemeMode>
    fun saveTheme(mode: ThemeMode)
}

sealed interface PushState {
    data object Off : PushState
    data object Pending : PushState
    data class On(val endpoint: String) : PushState
    data object NoDistributor : PushState
    data class Failed(val message: String) : PushState
}

/** Push notifications for important chats; UnifiedPush on Android. */
interface PushRegistrar {
    val state: StateFlow<PushState>
    fun enable()
    fun disable()
}
