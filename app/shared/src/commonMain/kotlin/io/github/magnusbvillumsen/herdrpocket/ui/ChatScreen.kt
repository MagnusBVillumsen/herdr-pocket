package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.HubClient
import io.github.magnusbvillumsen.herdrpocket.state.statusLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Label shown on the button, and the key name passed to `herdr pane send-keys`. */
val quickKeys: List<Pair<String, String>> = listOf(
    "Enter" to "Enter",
    "Esc" to "esc",
    "1" to "1",
    "2" to "2",
    "3" to "3",
    "y" to "y",
    "n" to "n",
    "↑" to "up",
    "↓" to "down",
)

const val SCREEN_LINES: Int = 300
const val SCREEN_REFRESH_MS: Long = 2_000

/** Screen text as lines, without the blank rows a terminal leaves below the cursor. */
fun screenLines(text: String): List<String> = text.trimEnd().lines().map { it.trimEnd() }

/**
 * A full-width rule such as Claude Code's `────` around its prompt. Drawn as a
 * divider, since at phone width the terminal-wide line wraps into several.
 */
fun isRule(line: String): Boolean = line.length >= 8 && line.all { it in RULE_CHARS }

private const val RULE_CHARS = "─━═╌╍┄┅┈┉-_"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(model: AppModel, key: AgentKey, snackbar: SnackbarHostState) {
    val state by model.store.state.collectAsState()
    val agent = state.agent(key)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lines by remember(key) { mutableStateOf<List<String>?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var input by remember(key) { mutableStateOf("") }
    var sending by remember(key) { mutableStateOf(false) }
    val refresh = remember(key) { Channel<Unit>(Channel.CONFLATED) }

    LaunchedEffect(key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val client = model.store.client() ?: break
                try {
                    lines = screenLines(client.screen(key.machine, key.pane, SCREEN_LINES))
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                }
                withTimeoutOrNull(SCREEN_REFRESH_MS) { refresh.receive() }
            }
        }
    }

    fun act(what: String, block: suspend (HubClient) -> Unit) {
        val client = model.store.client() ?: return
        scope.launch {
            sending = true
            try {
                block(client)
                refresh.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.notice("Could not $what: ${e.message}")
            } finally {
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { GlyphButton("←", "Back") { model.nav.back() } },
                title = {
                    Column {
                        Text(
                            agent?.title?.ifEmpty { null } ?: agent?.agent?.ifEmpty { null } ?: key.pane,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOfNotNull(key.machine, agent?.agent?.ifEmpty { null }, agent?.let { statusLabel(it.status) })
                        Text(
                            sub.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = agent?.let { statusColor(it.status) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (agent != null) {
                        StarButton(agent.important) {
                            scope.launch {
                                try {
                                    model.store.setImportant(key, !agent.important)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    model.notice("Could not change the star: ${e.message}")
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val shown = lines
                if (shown == null) {
                    if (error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    // Reversed so the list is anchored to the newest line and stays at the bottom.
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        reverseLayout = true,
                    ) {
                        itemsIndexed(shown.asReversed()) { _, line ->
                            if (isRule(line)) {
                                HorizontalDivider(Modifier.padding(vertical = 7.dp))
                            } else {
                                Text(
                                    line.ifEmpty { " " },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((label, k) in quickKeys) {
                    OutlinedButton(
                        onClick = { act("send $label") { it.keys(key.machine, key.pane, listOf(k)) } },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Text(label)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val send = {
                    val text = input.trimEnd()
                    if (text.isNotBlank()) {
                        act("send the prompt") {
                            it.prompt(key.machine, key.pane, text)
                            input = ""
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Prompt") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Button(onClick = send, enabled = input.isNotBlank() && !sending) { Text("Send") }
            }
        }
    }
}
