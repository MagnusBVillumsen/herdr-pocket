package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.magnusbvillumsen.herdrpocket.api.Agent
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.state.Connection
import io.github.magnusbvillumsen.herdrpocket.state.MachineGroup
import io.github.magnusbvillumsen.herdrpocket.state.agentLocation
import io.github.magnusbvillumsen.herdrpocket.state.formatSince
import io.github.magnusbvillumsen.herdrpocket.state.statusLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

fun nowSeconds(): Long = Clock.System.now().epochSeconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(model: AppModel, snackbar: SnackbarHostState) {
    val state by model.store.state.collectAsState()
    val scope = rememberCoroutineScope()
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
        val groups = state.groups
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            groups.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No machines have connected to the hub yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                for (g in groups) {
                    item(key = "machine:${g.machine}") { MachineHeader(g) }
                    items(g.agents, key = { "agent:${it.machine}/${it.pane}" }) { a ->
                        AgentRow(
                            agent = a,
                            now = now,
                            dimmed = !g.connected,
                            onOpen = { model.nav.open(Screen.Chat(a.key)) },
                            onStar = { toggle(a) },
                        )
                    }
                }
            }
        }
    }
}

private fun connectionText(c: Connection): String = when (c) {
    Connection.NotPaired -> "not paired"
    Connection.Connecting -> "connecting…"
    Connection.Live -> "live"
    is Connection.Retrying -> "offline, retrying in ${c.inSeconds}s: ${c.message}"
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
private fun AgentRow(agent: Agent, now: Long, dimmed: Boolean, onOpen: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f)
            .clickable(onClick = onOpen)
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
                agentLocation(agent),
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
