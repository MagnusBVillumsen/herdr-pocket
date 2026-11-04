package io.github.magnusbvillumsen.herdrpocket

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import io.github.magnusbvillumsen.herdrpocket.platform.GroupBy
import io.github.magnusbvillumsen.herdrpocket.platform.HiddenStore
import io.github.magnusbvillumsen.herdrpocket.platform.ThemeMode
import io.github.magnusbvillumsen.herdrpocket.platform.ViewPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidConfigStore(context: Context) : ConfigStore, ViewPrefs, HiddenStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    override val config: StateFlow<HubConfig?> = _config.asStateFlow()
    private val _theme = MutableStateFlow(
        ThemeMode.entries.find { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System,
    )
    override val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    private val _groupBy = MutableStateFlow(
        GroupBy.entries.find { it.name == prefs.getString(KEY_GROUP_BY, null) } ?: GroupBy.Machine,
    )
    override val groupBy: StateFlow<GroupBy> = _groupBy.asStateFlow()

    override fun saveGroupBy(by: GroupBy) {
        prefs.edit { putString(KEY_GROUP_BY, by.name) }
        _groupBy.value = by
    }

    private val _hidden = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().mapNotNull(::keyOf).toSet(),
    )
    override val hidden: StateFlow<Set<AgentKey>> = _hidden.asStateFlow()

    override fun setHidden(key: AgentKey, hide: Boolean) = saveHidden(if (hide) _hidden.value + key else _hidden.value - key)

    override fun retain(keys: Set<AgentKey>) {
        val kept = _hidden.value intersect keys
        if (kept != _hidden.value) saveHidden(kept)
    }

    private fun saveHidden(keys: Set<AgentKey>) {
        prefs.edit { putStringSet(KEY_HIDDEN, keys.map { "${it.machine}\n${it.pane}" }.toSet()) }
        _hidden.value = keys
    }

    private fun keyOf(s: String): AgentKey? {
        val (machine, pane) = s.split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        return AgentKey(machine, pane)
    }

    override fun saveTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _theme.value = mode
    }

    override fun save(config: HubConfig) {
        prefs.edit {
            putString(KEY_URL, config.url)
            putString(KEY_TOKEN, config.token)
        }
        _config.value = config
    }

    private fun read(): HubConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return HubConfig(url, token)
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_THEME = "theme"
        const val KEY_HIDDEN = "hidden"
        const val KEY_GROUP_BY = "group_by"
    }
}
