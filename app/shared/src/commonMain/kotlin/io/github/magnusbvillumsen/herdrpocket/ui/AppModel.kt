package io.github.magnusbvillumsen.herdrpocket.ui

import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.HubClient
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.api.parsePairLink
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import io.github.magnusbvillumsen.herdrpocket.platform.HiddenStore
import io.github.magnusbvillumsen.herdrpocket.platform.PushRegistrar
import io.github.magnusbvillumsen.herdrpocket.platform.ThemeStore
import io.github.magnusbvillumsen.herdrpocket.state.HubStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Everything the UI needs, wired by the platform entry point. */
class AppModel(
    val configs: ConfigStore,
    val push: PushRegistrar,
    val themes: ThemeStore,
    val hidden: HiddenStore,
    clientFor: (HubConfig) -> HubClient = { HubClient(it) },
) {
    val store: HubStore = HubStore(configs, clientFor)
    val nav: Navigator = Navigator(if (configs.config.value == null) Screen.Settings else Screen.Agents)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Starts the platform's QR scanner, whose result goes to [handlePairLink]; null where there is none. */
    var scanPairCode: (() -> Unit)? = null

    fun notice(message: String) {
        _notices.tryEmit(message)
    }

    fun pair(config: HubConfig) {
        configs.save(config)
        nav.reset(Screen.Agents)
    }

    /** Handles `herdr-pocket://pair?…`; returns false for any other link. */
    fun handlePairLink(link: String): Boolean {
        if (!link.startsWith("herdr-pocket://pair", ignoreCase = true)) return false
        val cfg = parsePairLink(link)
        if (cfg == null) {
            notice("That pairing link is missing the hub URL or token")
        } else {
            pair(cfg)
            notice("Paired with ${cfg.url}")
        }
        return true
    }

    fun openChat(key: AgentKey) {
        if (configs.config.value != null) nav.reset(Screen.Chat(key))
    }
}
