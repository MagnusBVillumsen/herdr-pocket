package io.github.magnusbvillumsen.herdrpocket

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import io.github.magnusbvillumsen.herdrpocket.platform.ThemeMode
import io.github.magnusbvillumsen.herdrpocket.platform.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidConfigStore(context: Context) : ConfigStore, ThemeStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    override val config: StateFlow<HubConfig?> = _config.asStateFlow()
    private val _theme = MutableStateFlow(
        ThemeMode.entries.find { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System,
    )
    override val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

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
    }
}
