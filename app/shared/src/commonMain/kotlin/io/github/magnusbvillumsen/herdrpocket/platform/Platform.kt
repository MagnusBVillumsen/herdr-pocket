package io.github.magnusbvillumsen.herdrpocket.platform

import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import kotlinx.coroutines.flow.StateFlow

/** Where the hub URL and token live; SharedPreferences on Android. */
interface ConfigStore {
    val config: StateFlow<HubConfig?>
    fun save(config: HubConfig)
}

enum class ThemeMode { System, Light, Dark }

enum class GroupBy { Machine, Workspace }

/** How the app looks, kept apart from the hub config. */
interface ViewPrefs {
    val theme: StateFlow<ThemeMode>
    val groupBy: StateFlow<GroupBy>
    fun saveTheme(mode: ThemeMode)
    fun saveGroupBy(by: GroupBy)
}

/**
 * Chats hidden from the list, on this phone only. Pane ids are reused once a
 * pane closes, so [retain] drops keys the hub no longer lists.
 */
interface HiddenStore {
    val hidden: StateFlow<Set<AgentKey>>
    fun setHidden(key: AgentKey, hide: Boolean)
    fun retain(keys: Set<AgentKey>)
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
