package io.github.magnusbvillumsen.herdrpocket

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidConfigStore(context: Context) : ConfigStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    override val config: StateFlow<HubConfig?> = _config.asStateFlow()

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
    }
}
