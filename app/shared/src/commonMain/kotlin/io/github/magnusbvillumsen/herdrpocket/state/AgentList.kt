package io.github.magnusbvillumsen.herdrpocket.state

import io.github.magnusbvillumsen.herdrpocket.api.Agent
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.api.Machine

data class MachineGroup(val machine: String, val connected: Boolean, val agents: List<Agent>)

/** Blocked first, then important, then the most recent status change. */
val agentOrder: Comparator<Agent> = compareBy<Agent>(
    { it.status != AgentStatus.Blocked },
    { !it.important },
    { -it.since },
    { it.agent },
    { it.pane },
)

/**
 * Groups agents by machine. Connected machines come before disconnected ones, each set by name;
 * a machine that only shows up on an agent counts as connected.
 */
fun groupAgents(agents: List<Agent>, machines: List<Machine>): List<MachineGroup> {
    val connected: Map<String, Boolean> = machines.associate { it.name to it.connected }
    val names: Set<String> = machines.map { it.name }.toSet() + agents.map { it.machine }
    return names
        .map { name ->
            MachineGroup(
                machine = name,
                connected = connected[name] ?: true,
                agents = agents.filter { it.machine == name }.sortedWith(agentOrder),
            )
        }
        .sortedWith(compareBy<MachineGroup>({ !it.connected }, { it.machine }))
}

/** The list as shown: starred chats first, the rest by machine, hidden ones apart. */
data class AgentSections(val starred: List<Agent>, val groups: List<MachineGroup>, val hidden: List<Agent>)

/** Hiding wins over starring, so a hidden chat can still notify without taking up the list. */
fun sections(agents: List<Agent>, machines: List<Machine>, hidden: Set<AgentKey>): AgentSections {
    val (out, shown) = agents.partition { it.key in hidden }
    val (starred, rest) = shown.partition { it.important }
    return AgentSections(
        starred = starred.sortedWith(agentOrder),
        groups = groupAgents(rest, machines),
        hidden = out.sortedWith(agentOrder),
    )
}

fun formatSince(nowSeconds: Long, since: Long): String {
    if (since <= 0) return ""
    val d = (nowSeconds - since).coerceAtLeast(0)
    return when {
        d < 60 -> "${d}s"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        else -> "${d / 86_400}d"
    }
}

fun shortenPath(path: String): String {
    val m = Regex("^/(home|Users)/[^/]+").find(path) ?: return path
    return "~" + path.substring(m.range.last + 1)
}

/** The second line of an agent row: workspace and working directory, without repeating. */
fun agentLocation(agent: Agent): String {
    val cwd = shortenPath(agent.cwd)
    val ws = agent.workspace
    return when {
        ws.isEmpty() -> cwd
        cwd.isEmpty() -> ws
        else -> "$ws · $cwd"
    }
}

fun statusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.Working -> "working"
    AgentStatus.Blocked -> "needs you"
    AgentStatus.Done -> "done"
    AgentStatus.Idle -> "idle"
    AgentStatus.Unknown -> "unknown"
}
