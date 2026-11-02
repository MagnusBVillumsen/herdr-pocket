package io.github.magnusbvillumsen.herdrpocket.state

import io.github.magnusbvillumsen.herdrpocket.api.Agent
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.AgentsResponse
import io.github.magnusbvillumsen.herdrpocket.api.HubClient
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.api.Machine
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

sealed interface Connection {
    data object NotPaired : Connection
    data object Connecting : Connection
    data object Live : Connection
    data class Retrying(val message: String, val inSeconds: Int) : Connection
}

data class HubState(
    val agents: List<Agent> = emptyList(),
    val machines: List<Machine> = emptyList(),
    val connection: Connection = Connection.Connecting,
    val loaded: Boolean = false,
) {
    val groups: List<MachineGroup> get() = groupAgents(agents, machines)
    fun agent(key: AgentKey): Agent? = agents.firstOrNull { it.key == key }
    fun machineConnected(name: String): Boolean = machines.firstOrNull { it.name == name }?.connected ?: true
}

/** 1 s doubling up to 30 s, like the node's reconnect. */
fun backoffSeconds(attempt: Int): Int = if (attempt >= 5) 30 else minOf(30, 1 shl attempt)

/**
 * Keeps the agent list live: a fresh `/api/agents`, then `/api/events` until it drops, with
 * backoff. Owns one [HubClient] per saved config so screens can share it.
 */
class HubStore(
    private val configs: ConfigStore,
    private val clientFor: (HubConfig) -> HubClient = { HubClient(it) },
) {
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    private var current: Pair<HubConfig, HubClient>? = null

    /** The client for the saved config, or null before pairing. */
    fun client(): HubClient? {
        val cfg = configs.config.value ?: return null
        current?.let { (c, client) -> if (c == cfg) return client else client.close() }
        return clientFor(cfg).also { current = cfg to it }
    }

    /** Runs until cancelled; call it while the app is visible. */
    suspend fun run() {
        configs.config.collectLatest { cfg ->
            if (cfg == null) {
                _state.value = HubState(connection = Connection.NotPaired)
                return@collectLatest
            }
            _state.update { it.copy(connection = Connection.Connecting) }
            val client = client() ?: return@collectLatest
            var attempt = 0
            while (true) {
                try {
                    apply(client.agents())
                    attempt = 0
                    client.events().collect { apply(it) }
                    // A clean close (hub restart, proxy timeout): the list is still current, so stay
                    // quiet and reconnect; a hub that is really gone fails the next attempt.
                    delay(1_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val wait = backoffSeconds(attempt++)
                    _state.update { it.copy(connection = Connection.Retrying(e.message ?: "connection failed", wait)) }
                    delay(wait * 1_000L)
                    _state.update { it.copy(connection = Connection.Connecting) }
                }
            }
        }
    }

    /** Flips the star immediately and asks the hub; the next event confirms or the flag is put back. */
    suspend fun setImportant(key: AgentKey, important: Boolean) {
        markImportant(key, important)
        val client = client() ?: return
        try {
            client.setImportant(key.machine, key.pane, important)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            markImportant(key, !important)
            throw e
        }
    }

    private fun markImportant(key: AgentKey, important: Boolean) = _state.update { s ->
        s.copy(agents = s.agents.map { if (it.key == key) it.copy(important = important) else it })
    }

    private fun apply(r: AgentsResponse) = _state.update {
        it.copy(agents = r.agents, machines = r.machines, connection = Connection.Live, loaded = true)
    }
}
