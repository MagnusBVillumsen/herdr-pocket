package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.magnusbvillumsen.herdrpocket.api.Agent
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.platform.GroupBy
import io.github.magnusbvillumsen.herdrpocket.state.Connection
import io.github.magnusbvillumsen.herdrpocket.state.MachineGroup
import io.github.magnusbvillumsen.herdrpocket.state.agentLocation
import io.github.magnusbvillumsen.herdrpocket.state.formatSince
import io.github.magnusbvillumsen.herdrpocket.state.sections
import io.github.magnusbvillumsen.herdrpocket.state.statusLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

fun nowSeconds(): Long = Clock.System.now().epochSeconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(model: AppModel, snackbar: SnackbarHostState) {
    val state by model.store.state.collectAsState()
    val hidden by model.hidden.hidden.collectAsState()
    val groupBy by model.prefs.groupBy.collectAsState()
    var showHidden by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.loaded, state.agents) {
        if (state.loaded) model.hidden.retain(state.agents.map { it.key }.toSet())
    }
    var now by remember { mutableLongStateOf(nowSeconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = nowSeconds()
        }
    }
    val toggle: (Agent) -> Unit = { a ->
        scope.launch {
            try {
                model.store.setImportant(a.key, !a.important)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                model.notice("Could not change the star: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("herdr-pocket")
                        Text(
                            connectionText(state.connection),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.connection is Connection.Retrying) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = { GlyphButton("⚙", "Settings") { model.nav.open(Screen.Settings) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = sections(state.agents, state.machines, hidden)
        val row: @Composable (Agent, Boolean, Boolean) -> Unit = { a, dimmed, withMachine ->
            AgentRow(
                agent = a,
                now = now,
                dimmed = dimmed,
                withMachine = withMachine,
                isHidden = a.key in hidden,
                onOpen = { model.nav.open(Screen.Chat(a.key)) },
                onStar = { toggle(a) },
                onHide = { model.hidden.setHidden(a.key, a.key !in hidden) },
            )
        }
        when {
            !state.loaded && state.connection is Connection.Retrying -> Unreachable(
                url = model.configs.config.value?.url.orEmpty(),
                error = (state.connection as Connection.Retrying).message,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSettings = { model.nav.open(Screen.Settings) },
            )
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            s.groups.isEmpty() && s.starred.isEmpty() && s.hidden.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No machines have connected to the hub yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item(key = "group-by") {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (by in GroupBy.entries) {
                            FilterChip(
                                selected = groupBy == by,
                                onClick = { model.prefs.saveGroupBy(by) },
                                label = { Text(by.name) },
                            )
                        }
                    }
                }
                if (s.starred.isNotEmpty()) {
                    item(key = "starred") { SectionHeader("Starred", starColor) }
                    items(s.starred, key = { "starred:${it.machine}/${it.pane}" }) { a ->
                        row(a, !state.machineConnected(a.machine), true)
                    }
                }
                if (groupBy == GroupBy.Machine) {
                    for (g in s.groups) {
                        item(key = "machine:${g.machine}") { MachineHeader(g) }
                        items(g.agents, key = { "agent:${it.machine}/${it.pane}" }) { a -> row(a, !g.connected, false) }
                    }
                } else {
                    for (w in s.workspaces) {
                        item(key = "workspace:${w.name}") {
                            SectionHeader(w.name.ifEmpty { "No workspace" }, MaterialTheme.colorScheme.primary)
                        }
                        items(w.agents, key = { "agent:${it.machine}/${it.pane}" }) { a ->
                            row(a, !state.machineConnected(a.machine), true)
                        }
                    }
                }
                if (s.hidden.isNotEmpty()) {
                    item(key = "hidden") {
                        TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.padding(8.dp)) {
                            Text(if (showHidden) "Hide ${s.hidden.size} hidden" else "Show ${s.hidden.size} hidden")
                        }
                    }
                    if (showHidden) {
                        items(s.hidden, key = { "hidden:${it.machine}/${it.pane}" }) { a -> row(a, true, true) }
                    }
                }
                item(key = "hint") {
                    Text(
                        "Long-press a chat to hide it. ★ notifies you when it finishes or needs you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** The usual cause on a tailnet is the phone's Tailscale being off, so say so. */
@Composable
private fun Unreachable(url: String, error: String, modifier: Modifier, onSettings: () -> Unit) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't reach the hub", style = MaterialTheme.typography.titleMedium)
        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(
            "Check that Tailscale is connected on this phone and that the URL is right. Retrying on its own.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onSettings) { Text("Settings") }
    }
}

private fun connectionText(c: Connection): String = when (c) {
    Connection.NotPaired -> "not paired"
    Connection.Connecting -> "connecting…"
    Connection.Live -> "live"
    is Connection.Retrying -> "offline, retrying in ${c.inSeconds}s: ${c.message}"
}

@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun MachineHeader(g: MachineGroup) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                g.machine,
                style = MaterialTheme.typography.titleSmall,
                color = if (g.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            if (!g.connected) {
                Spacer(Modifier.width(8.dp))
                Text("disconnected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (g.agents.isEmpty()) {
            Text("no agents", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    HorizontalDivider()
}

@Composable
private fun AgentRow(
    agent: Agent,
    now: Long,
    dimmed: Boolean,
    withMachine: Boolean,
    isHidden: Boolean,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    onHide: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (isHidden) "Unhide" else "Hide") },
                onClick = {
                    menu = false
                    onHide()
                },
            )
            DropdownMenuItem(
                text = { Text(if (agent.important) "Unstar" else "Star") },
                onClick = {
                    menu = false
                    onStar()
                },
            )
        }
        AgentRowContent(agent, now, dimmed, withMachine, onOpen, { menu = true }, onStar)
    }
}

@Composable
private fun AgentRowContent(
    agent: Agent,
    now: Long,
    dimmed: Boolean,
    withMachine: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onStar: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(agent.status)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.agent.ifEmpty { agent.pane },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (agent.title.isNotEmpty()) {
                    Text(
                        "  ${agent.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (withMachine) "${agent.machine} · ${agentLocation(agent)}" else agentLocation(agent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                statusLabel(agent.status),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(agent.status),
                fontWeight = if (agent.status == AgentStatus.Blocked) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                formatSince(now, agent.since),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StarButton(agent.important, onStar)
    }
}
