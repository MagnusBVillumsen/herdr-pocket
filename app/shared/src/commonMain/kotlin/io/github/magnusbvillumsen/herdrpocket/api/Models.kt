package io.github.magnusbvillumsen.herdrpocket.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class AgentStatus {
    @SerialName("idle") Idle,
    @SerialName("working") Working,
    @SerialName("blocked") Blocked,
    @SerialName("done") Done,
    @SerialName("unknown") Unknown,
}

@Serializable
data class Agent(
    val machine: String,
    val pane: String,
    val agent: String = "",
    // A status newer than this app falls back to Unknown via coerceInputValues.
    val status: AgentStatus = AgentStatus.Unknown,
    val since: Long = 0,
    val title: String = "",
    val cwd: String = "",
    val workspace: String = "",
    val important: Boolean = false,
) {
    val key: AgentKey get() = AgentKey(machine, pane)
}

@Serializable
data class AgentKey(val machine: String, val pane: String)

@Serializable
data class Machine(val name: String, val connected: Boolean = true)

@Serializable
data class AgentsResponse(
    val agents: List<Agent> = emptyList(),
    val machines: List<Machine> = emptyList(),
)

@Serializable
data class ScreenResponse(val text: String = "")

@Serializable
data class PromptRequest(val text: String)

@Serializable
data class KeysRequest(val keys: List<String>)

@Serializable
data class ImportantRequest(val important: Boolean)

@Serializable
data class PushRequest(val endpoint: String)

@Serializable
data class ErrorResponse(val error: String)

val HubJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
