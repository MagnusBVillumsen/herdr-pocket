package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.magnusbvillumsen.herdrpocket.api.HubClient
import io.github.magnusbvillumsen.herdrpocket.api.hubConfigOf
import io.github.magnusbvillumsen.herdrpocket.platform.PushState
import io.github.magnusbvillumsen.herdrpocket.platform.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: AppModel, snackbar: SnackbarHostState) {
    val saved by model.configs.config.collectAsState()
    val pushState by model.push.state.collectAsState()
    val theme by model.themes.theme.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember(saved) { mutableStateOf(saved?.url ?: "") }
    var token by remember(saved) { mutableStateOf(saved?.token ?: "") }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val candidate = hubConfigOf(url, token)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (model.nav.canGoBack) GlyphButton("←", "Back") { model.nav.back() }
                },
                title = { Text("Settings") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hub", style = MaterialTheme.typography.titleMedium)
            Text(
                "Run `herdr-pocket pair` on the hub and scan its QR code, or enter the hub URL and token below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.scanPairCode?.let { scan ->
                Button(onClick = scan, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Hub URL") },
                placeholder = { Text("https://heimdall.example.ts.net:8790") },
                singleLine = true,
                isError = url.isNotBlank() && candidate == null && token.isNotBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = candidate != null && candidate != saved,
                    onClick = {
                        val cfg = candidate ?: return@Button
                        model.pair(cfg)
                        model.notice("Saved")
                    },
                ) { Text("Save") }
                OutlinedButton(
                    enabled = candidate != null && !testing,
                    onClick = {
                        val cfg = candidate ?: return@OutlinedButton
                        scope.launch {
                            testing = true
                            val client = HubClient(cfg)
                            try {
                                val r = client.agents()
                                model.notice("Hub answered: ${r.agents.size} agents on ${r.machines.size} machines")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.notice("Hub test failed: ${e.message}")
                            } finally {
                                client.close()
                                testing = false
                            }
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test") }
            }

            Spacer(Modifier.padding(4.dp))
            HorizontalDivider()
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notify for important chats", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        pushText(pushState),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pushState is PushState.Failed || pushState == PushState.NoDistributor) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = pushState is PushState.On || pushState == PushState.Pending,
                    enabled = saved != null,
                    onCheckedChange = { on -> if (on) model.push.enable() else model.push.disable() },
                )
            }
            Text(
                "Star a chat to get a notification when it finishes or needs you. Uses UnifiedPush; install the ntfy app as the distributor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.padding(4.dp))
            HorizontalDivider()
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ThemeMode.entries) {
                    FilterChip(
                        selected = theme == mode,
                        onClick = { model.themes.saveTheme(mode) },
                        label = { Text(themeLabel(mode)) },
                    )
                }
            }
        }
    }
}

private fun themeLabel(m: ThemeMode): String = when (m) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

fun pushText(s: PushState): String = when (s) {
    PushState.Off -> "Off"
    PushState.Pending -> "Registering with the distributor…"
    is PushState.On -> "On, via ${endpointHost(s.endpoint)}"
    PushState.NoDistributor -> "No UnifiedPush distributor installed (install ntfy)"
    is PushState.Failed -> "Failed: ${s.message}"
}

private fun endpointHost(endpoint: String): String =
    endpoint.substringAfter("://").substringBefore('/').ifEmpty { endpoint }
