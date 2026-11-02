package io.github.magnusbvillumsen.herdrpocket

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.magnusbvillumsen.herdrpocket.platform.PushRegistrar
import io.github.magnusbvillumsen.herdrpocket.platform.PushState
import io.github.magnusbvillumsen.herdrpocket.ui.AppModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush

/** What [PushController] needs from the visible activity. */
interface PushHost {
    val activity: android.app.Activity
    fun requestNotificationPermission(onResult: (Boolean) -> Unit)
}

/**
 * UnifiedPush registration, and keeping the hub's list of endpoints in step with it. The
 * distributor (ntfy) hands out the endpoint; the hub POSTs notifications to it.
 */
class PushController(
    private val context: Context,
    private val configs: AndroidConfigStore,
    private val scope: CoroutineScope,
) : PushRegistrar {
    private val prefs: SharedPreferences = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<PushState> = _state.asStateFlow()
    private lateinit var model: AppModel

    var host: PushHost? = null

    private val endpoint: String? get() = prefs.getString(KEY_ENDPOINT, null)

    fun attach(model: AppModel) {
        this.model = model
        // Re-sending on start and after re-pairing is harmless and heals a hub that lost its state.
        scope.launch {
            configs.config.filterNotNull().collect { endpoint?.let { tellHub(it) } }
        }
    }

    override fun enable() {
        // The distributor picker needs an activity, and enable() is only reachable from the UI.
        val h = host ?: return
        if (UnifiedPush.getDistributors(context).isEmpty()) {
            _state.value = PushState.NoDistributor
            return
        }
        if (!needsPermission()) {
            register(h.activity)
            return
        }
        h.requestNotificationPermission { granted ->
            if (granted) register(h.activity) else _state.value = PushState.Failed("notification permission denied")
        }
    }

    override fun disable() {
        val old = endpoint
        prefs.edit { remove(KEY_ENDPOINT) }
        UnifiedPush.unregister(context, INSTANCE)
        _state.value = PushState.Off
        if (old != null) forgetAtHub(old)
    }

    fun onNewEndpoint(url: String) {
        val old = endpoint
        prefs.edit { putString(KEY_ENDPOINT, url) }
        _state.value = PushState.On(url)
        if (old != null && old != url) forgetAtHub(old)
        scope.launch { tellHub(url) }
    }

    fun onUnregistered() {
        val old = endpoint
        prefs.edit { remove(KEY_ENDPOINT) }
        _state.value = PushState.Off
        if (old != null) forgetAtHub(old)
    }

    fun onRegistrationFailed(reason: FailedReason) {
        _state.value = PushState.Failed(
            when (reason) {
                FailedReason.NETWORK -> "the distributor could not reach its server"
                FailedReason.ACTION_REQUIRED -> "open the distributor app, it needs your attention"
                FailedReason.VAPID_REQUIRED -> "the distributor requires VAPID, which the hub does not support"
                FailedReason.INTERNAL_ERROR -> "the distributor reported an internal error"
            },
        )
    }

    private fun register(activity: android.app.Activity) {
        _state.value = PushState.Pending
        val done: (Boolean) -> Unit = { ok ->
            if (ok) {
                UnifiedPush.register(context, INSTANCE, context.getString(R.string.app_name), null)
            } else {
                _state.value = PushState.Failed("no distributor chosen")
            }
        }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity, done)
    }

    private suspend fun tellHub(url: String) {
        val client = model.store.client() ?: return
        try {
            client.registerPush(url)
            if (endpoint == url) _state.value = PushState.On(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = PushState.Failed("could not register with the hub: ${e.message}")
        }
    }

    private fun forgetAtHub(url: String) {
        val client = model.store.client() ?: return
        scope.launch {
            // Best effort: the hub also drops endpoints that answer 404 or 410.
            runCatching { client.unregisterPush(url) }
        }
    }

    private fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun initialState(): PushState = endpoint?.let { PushState.On(it) } ?: PushState.Off

    private companion object {
        const val INSTANCE = "default"
        const val KEY_ENDPOINT = "endpoint"
    }
}
