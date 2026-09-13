package fr.onrails.trainwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/** Builds the ongoing, silent status notification from a [TrainState]. */
class TripNotification(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "trip_status"
        const val NOTIFICATION_ID = 1
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW, // silent
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun show(state: TrainState) {
        manager.notify(NOTIFICATION_ID, build(state))
    }

    fun build(state: TrainState): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), flags,
        )
        val stopService = PendingIntent.getService(
            context, 1, Intent(context, TrainWifiService::class.java).setAction(TrainWifiService.ACTION_STOP), flags,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_train)
            .setColor(context.getColor(R.color.brand_red))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stop),
                    context.getString(R.string.btn_stop),
                    stopService,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Otherwise Android 12+ may hold the FGS notification back for up to 10 s.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val trip = state.trip
        val destination = trip?.destination
        if (trip == null || destination == null) {
            return idle(builder, state).build()
        }

        // Title: "→ Grenoble 13:18 (+5 min)"
        builder.setContentTitle("→ ${Formatting.stopLine(destination)}")

        // Text: "91% · 298 km/h · next: Valence 12:40"
        val parts = mutableListOf<String>()
        trip.progressPercent?.let { parts += "$it%" }
        Formatting.speed(state.gps)?.let { parts += it }
        trip.nextStop?.let { parts += "next: ${it.name} ${Formatting.time(it.eta)}" }
        if (state.phase == Phase.DEMO) parts += "demo"
        builder.setContentText(parts.joinToString(" · "))

        trip.progressPercent?.let { builder.setProgress(100, it, false) }

        // Expanded: remaining stops with delay, then quota and Wi-Fi quality.
        val lines = mutableListOf<String>()
        trip.remainingStops.forEach { lines += Formatting.stopLine(it) }
        lines += ""
        state.connection?.let { lines += Formatting.quota(it) }
        Formatting.wifi(state.statistics, state.barQueueEmpty)?.let { lines += it }
        state.portal?.let { lines += "Portal ${it.host}" + if (state.phase == Phase.DEMO) " (demo data)" else "" }
        builder.setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n").trim()))

        return builder.build()
    }

    private fun idle(builder: Notification.Builder, state: TrainState): Notification.Builder {
        val title = when (state.phase) {
            Phase.ACTIVE -> "Connected to ${state.portal?.host ?: "train Wi-Fi"}"
            Phase.ACTIVATING -> Phase.ACTIVATING.title
            Phase.VPN_BLOCKED -> Phase.VPN_BLOCKED.title
            else -> context.getString(R.string.notification_waiting)
        }
        builder.setContentTitle(title)
        val text = listOfNotNull(
            state.message.takeIf { it.isNotBlank() },
            state.connection?.description?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (text.isNotEmpty()) builder.setContentText(text)
        return builder
    }
}
