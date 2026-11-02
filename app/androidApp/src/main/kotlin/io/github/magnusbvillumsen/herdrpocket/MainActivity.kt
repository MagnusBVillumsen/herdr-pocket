package io.github.magnusbvillumsen.herdrpocket

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), PushHost {
    private var onPermission: ((Boolean) -> Unit)? = null
    private val permission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermission?.invoke(granted)
            onPermission = null
        }

    override val activity: Activity get() = this

    override fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(true)
            return
        }
        onPermission = onResult
        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = herdr.model
        herdr.push.host = this

        val back = object : OnBackPressedCallback(model.nav.canGoBack) {
            override fun handleOnBackPressed() {
                model.nav.back()
            }
        }
        onBackPressedDispatcher.addCallback(this, back)
        lifecycleScope.launch {
            snapshotFlow { model.nav.canGoBack }.collect { back.isEnabled = it }
        }

        // A recreated activity has already handled the intent that started it.
        if (savedInstanceState == null) handle(intent)
        setContent { App(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (herdr.push.host === this) herdr.push.host = null
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val model = herdr.model
        val data = intent.dataString
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            model.handlePairLink(data)
            return
        }
        if (intent.action == Notifier.ACTION_OPEN_CHAT) {
            val machine = intent.getStringExtra(Notifier.EXTRA_MACHINE) ?: return
            val pane = intent.getStringExtra(Notifier.EXTRA_PANE) ?: return
            model.openChat(AgentKey(machine, pane))
        }
    }
}
