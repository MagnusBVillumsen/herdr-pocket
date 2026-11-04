package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.api.Agent
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.api.Machine
import io.github.magnusbvillumsen.herdrpocket.state.MachineGroup
import io.github.magnusbvillumsen.herdrpocket.state.agentLocation
import io.github.magnusbvillumsen.herdrpocket.state.backoffSeconds
import io.github.magnusbvillumsen.herdrpocket.state.formatSince
import io.github.magnusbvillumsen.herdrpocket.state.groupAgents
import io.github.magnusbvillumsen.herdrpocket.state.groupWorkspaces
import io.github.magnusbvillumsen.herdrpocket.state.sections
import io.github.magnusbvillumsen.herdrpocket.state.shortenPath
import io.github.magnusbvillumsen.herdrpocket.ui.isRule
import io.github.magnusbvillumsen.herdrpocket.ui.screenLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentListTest {
    private fun agent(machine: String, pane: String, status: AgentStatus, since: Long = 0, important: Boolean = false) =
        Agent(machine, pane, "claude", status, since, important = important)

    @Test
    fun blockedThenImportantThenNewest() {
        val agents = listOf(
            agent("thor", "a", AgentStatus.Working, since = 100),
            agent("thor", "b", AgentStatus.Idle, since = 300, important = true),
            agent("thor", "c", AgentStatus.Blocked, since = 50),
            agent("thor", "d", AgentStatus.Done, since = 200),
            agent("thor", "e", AgentStatus.Blocked, since = 10, important = true),
            agent("thor", "f", AgentStatus.Working, since = 400),
        )
        val order = groupAgents(agents, listOf(Machine("thor"))).single().agents.map { it.pane }
        assertEquals(listOf("e", "c", "b", "f", "d", "a"), order)
    }

    @Test
    fun starredOnTopAndHiddenApart() {
        val agents = listOf(
            agent("thor", "a", AgentStatus.Idle),
            agent("odin", "b", AgentStatus.Working, important = true),
            agent("thor", "c", AgentStatus.Done, important = true),
            agent("odin", "d", AgentStatus.Idle),
        )
        val s = sections(agents, listOf(Machine("thor"), Machine("odin")), setOf(AgentKey("thor", "c"), AgentKey("odin", "d")))
        assertEquals(listOf("b"), s.starred.map { it.pane })
        assertEquals(listOf("odin" to emptyList(), "thor" to listOf("a")), s.groups.map { it.machine to it.agents.map { a -> a.pane } })
        assertEquals(setOf("c", "d"), s.hidden.map { it.pane }.toSet())
    }

    @Test
    fun workspacesSpanMachinesAndUnnamedComeLast() {
        val agents = listOf(
            agent("thor", "a", AgentStatus.Idle).copy(workspace = "mono"),
            agent("odin", "b", AgentStatus.Idle).copy(workspace = "mono"),
            agent("odin", "c", AgentStatus.Idle),
            agent("thor", "d", AgentStatus.Idle).copy(workspace = "Game"),
        )
        val groups = groupWorkspaces(agents)
        assertEquals(listOf("Game", "mono", ""), groups.map { it.name })
        assertEquals(setOf("a", "b"), groups[1].agents.map { it.pane }.toSet())
    }

    @Test
    fun groupsConnectedFirstThenByName() {
        val agents = listOf(
            agent("zeus", "p1", AgentStatus.Idle),
            agent("loki", "p1", AgentStatus.Working),
            agent("thor", "p1", AgentStatus.Done),
            agent("odin", "p1", AgentStatus.Idle),
        )
        val machines = listOf(Machine("thor", true), Machine("loki", false), Machine("zeus", true), Machine("freya", true))
        val groups = groupAgents(agents, machines)
        assertEquals(listOf("freya", "odin", "thor", "zeus", "loki"), groups.map { it.machine })
        assertEquals(listOf(true, true, true, true, false), groups.map { it.connected })
        assertEquals(emptyList<Agent>(), groups.first { it.machine == "freya" }.agents)
        assertEquals(listOf("p1"), groups.first { it.machine == "loki" }.agents.map { it.pane })
    }

    @Test
    fun nothingConnected() {
        assertEquals(emptyList<MachineGroup>(), groupAgents(emptyList(), emptyList()))
    }

    @Test
    fun since() {
        assertEquals("", formatSince(1000, 0))
        assertEquals("0s", formatSince(1000, 1005))
        assertEquals("59s", formatSince(1000, 941))
        assertEquals("1m", formatSince(1000, 940))
        assertEquals("59m", formatSince(10_000, 10_000 - 3599))
        assertEquals("2h", formatSince(10_000, 10_000 - 7200))
        assertEquals("3d", formatSince(1_000_000, 1_000_000 - 3 * 86_400))
    }

    @Test
    fun location() {
        assertEquals("~/dev/game", shortenPath("/home/magnus/dev/game"))
        assertEquals("~", shortenPath("/Users/magnus"))
        assertEquals("/srv/x", shortenPath("/srv/x"))
        assertEquals("game · ~/dev/game", agentLocation(Agent("m", "p", cwd = "/home/magnus/dev/game", workspace = "game")))
        assertEquals("game", agentLocation(Agent("m", "p", workspace = "game")))
        assertEquals("/srv/x", agentLocation(Agent("m", "p", cwd = "/srv/x")))
    }

    @Test
    fun backoff() {
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 30), (0..7).map { backoffSeconds(it) })
        assertEquals(30, backoffSeconds(100))
    }

    @Test
    fun screenTrimsTrailingBlankRows() {
        assertEquals(listOf("a", "", "b"), screenLines("a  \n\nb\n\n   \n"))
    }

    @Test
    fun rulesAreFullWidthLinesOnly() {
        assertTrue(isRule("─".repeat(80)))
        assertFalse(isRule("──"))
        assertFalse(isRule("── done ──────────"))
    }
}
