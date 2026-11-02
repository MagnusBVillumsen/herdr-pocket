package io.github.magnusbvillumsen.herdrpocket.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun App(model: AppModel) {
    HerdrTheme {
        val snackbar = remember { SnackbarHostState() }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(model) {
            model.notices.collect { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(model) {
            // No live connection while the app is in the background; push covers that.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.store.run() }
        }
        when (val screen = model.nav.current) {
            Screen.Agents -> AgentListScreen(model, snackbar)
            is Screen.Chat -> ChatScreen(model, screen.key, snackbar)
            Screen.Settings -> SettingsScreen(model, snackbar)
        }
    }
}
