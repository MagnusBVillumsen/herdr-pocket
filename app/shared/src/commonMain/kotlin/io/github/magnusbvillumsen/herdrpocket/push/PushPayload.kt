package io.github.magnusbvillumsen.herdrpocket.push

import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.api.HubJson
import kotlinx.serialization.Serializable

@Serializable
data class PushPayload(
    val machine: String,
    val pane: String,
    val agent: String = "",
    val status: AgentStatus = AgentStatus.Unknown,
    val title: String = "",
    val event: String = "",
) {
    val key: AgentKey get() = AgentKey(machine, pane)
}

enum class PushKind { Finished, NeedsYou }

data class NotificationText(val kind: PushKind, val title: String, val body: String)

fun parsePushPayload(text: String): PushPayload? =
    runCatching { HubJson.decodeFromString(PushPayload.serializer(), text) }.getOrNull()

fun pushKind(p: PushPayload): PushKind = when {
    p.event == "finished" -> PushKind.Finished
    // The protocol names only "finished" explicitly; anything else signals a blocked agent.
    p.status == AgentStatus.Blocked -> PushKind.NeedsYou
    p.status == AgentStatus.Done || p.status == AgentStatus.Idle -> PushKind.Finished
    else -> PushKind.NeedsYou
}

/** "thor · claude finished: Fix the build", or "thor · claude needs you" when there is no title. */
fun notificationText(p: PushPayload): NotificationText {
    val kind = pushKind(p)
    val who = listOf(p.machine, p.agent.ifEmpty { p.pane }).filter { it.isNotEmpty() }.joinToString(" · ")
    val what = if (kind == PushKind.Finished) "finished" else "needs you"
    val head = "$who $what"
    return NotificationText(
        kind = kind,
        title = if (p.title.isEmpty()) head else "$head: ${p.title}",
        body = p.pane,
    )
}
