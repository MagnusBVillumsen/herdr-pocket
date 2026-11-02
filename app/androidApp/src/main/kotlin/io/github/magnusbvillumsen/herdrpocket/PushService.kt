package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.push.parsePushPayload
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.PushService as UnifiedPushService

class PushService : UnifiedPushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        herdr.push.onNewEndpoint(endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // The hub sends plain JSON rather than RFC 8291 Web Push, so message.decrypted is false here.
        val payload = parsePushPayload(message.content.decodeToString()) ?: return
        Notifier.show(this, payload)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        herdr.push.onRegistrationFailed(reason)
    }

    override fun onUnregistered(instance: String) {
        herdr.push.onUnregistered()
    }
}
