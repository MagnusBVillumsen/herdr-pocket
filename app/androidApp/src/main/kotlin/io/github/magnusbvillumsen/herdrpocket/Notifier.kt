package io.github.magnusbvillumsen.herdrpocket

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.push.PushKind
import io.github.magnusbvillumsen.herdrpocket.push.PushPayload
import io.github.magnusbvillumsen.herdrpocket.push.notificationText

object Notifier {
    private const val CHANNEL_NEEDS_YOU = "needs_you"
    private const val CHANNEL_FINISHED = "finished"
    const val ACTION_OPEN_CHAT = "io.github.magnusbvillumsen.herdrpocket.OPEN_CHAT"
    const val EXTRA_MACHINE = "machine"
    const val EXTRA_PANE = "pane"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NEEDS_YOU, context.getString(R.string.channel_needs_you), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_needs_you_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FINISHED, context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_finished_desc) },
        )
    }

    fun show(context: Context, payload: PushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = notificationText(payload)
        // One notification per chat, so a newer event replaces the older one.
        val id = payload.key.hashCode()
        val open = PendingIntent.getActivity(
            context,
            id,
            openChatIntent(context, payload.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, if (text.kind == PushKind.NeedsYou) CHANNEL_NEEDS_YOU else CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (text.kind == PushKind.NeedsYou) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun openChatIntent(context: Context, key: AgentKey): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_MACHINE, key.machine)
            .putExtra(EXTRA_PANE, key.pane)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
