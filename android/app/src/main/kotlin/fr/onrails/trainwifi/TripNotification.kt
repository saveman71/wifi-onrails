package fr.onrails.trainwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import android.widget.RemoteViews

/**
 * Ongoing, silent status notification. With trip data it uses custom RemoteViews inside
 * [Notification.DecoratedCustomViewStyle]: destination + ETA pill + brand progress bar collapsed,
 * plus the remaining stops and the connection summary when expanded.
 */
class TripNotification(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "trip_status"
        const val NOTIFICATION_ID = 1
        private const val MAX_STOP_ROWS = 5
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

        // Plain title/text are kept for accessibility, Wear and launchers that ignore custom views.
        val subline = subline(state, trip)
        builder.setContentTitle("→ ${Formatting.stopLine(destination)}")
            .setContentText(subline)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView(trip, destination, subline))
            .setCustomBigContentView(expandedView(state, trip, destination, subline))
        return builder.build()
    }

    /** "79% · 277 km/h · next: Lyon Part Dieu 18:58" */
    private fun subline(state: TrainState, trip: Trip): String {
        val parts = mutableListOf<String>()
        trip.progressPercent?.let { parts += "$it%" }
        Formatting.speed(state.gps)?.let { parts += it }
        val next = trip.nextStop
        if (next != null && next != trip.destination) parts += "next: ${next.name} ${Formatting.time(next.eta)}"
        if (state.phase == Phase.DEMO) parts += "demo"
        return parts.joinToString(" · ")
    }

    private fun header(views: RemoteViews, trip: Trip, destination: Stop, subline: String) {
        views.setTextViewText(R.id.n_title, destination.name)
        views.setTextViewText(R.id.n_eta, Formatting.time(destination.eta))
        views.setTextViewText(R.id.n_sub, subline)
        val percent = trip.progressPercent
        if (percent != null) {
            views.setProgressBar(R.id.n_progress, 100, percent, false)
            views.setViewVisibility(R.id.n_progress, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.n_progress, View.GONE)
        }
    }

    private fun collapsedView(trip: Trip, destination: Stop, subline: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_collapsed)
        header(views, trip, destination, subline)
        return views
    }

    private fun expandedView(state: TrainState, trip: Trip, destination: Stop, subline: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_expanded)
        header(views, trip, destination, subline)

        views.removeAllViews(R.id.n_stops)
        val remaining = trip.remainingStops
        for (stop in remaining.take(MAX_STOP_ROWS)) {
            val row = RemoteViews(context.packageName, R.layout.notification_stop_row)
            row.setTextViewText(R.id.n_row_time, Formatting.time(stop.eta))
            row.setTextViewText(R.id.n_row_name, stop.name)
            row.setImageViewResource(R.id.n_row_dot, if (stop == destination) R.drawable.dot_filled else R.drawable.dot_ring)
            val delay = stop.delayMinutes ?: 0
            row.setTextViewText(R.id.n_row_delay, if (delay > 0) "+$delay min" else "")
            views.addView(R.id.n_stops, row)
        }
        if (remaining.size > MAX_STOP_ROWS) {
            val more = RemoteViews(context.packageName, R.layout.notification_stop_row)
            more.setTextViewText(R.id.n_row_time, "")
            more.setViewVisibility(R.id.n_row_dot, View.INVISIBLE)
            more.setTextViewText(R.id.n_row_name, "… ${remaining.size - MAX_STOP_ROWS} more stops")
            more.setTextViewText(R.id.n_row_delay, "")
            views.addView(R.id.n_stops, more)
        }

        val footer = mutableListOf<String>()
        state.connection?.let { footer += "${Formatting.mb(it.remainingMb)} left of ${Formatting.mb(it.totalMb)}" }
        Formatting.wifi(state.statistics, state.barQueueEmpty)?.let { footer += it }
        state.portal?.let { footer += it.host + if (state.phase == Phase.DEMO) " (demo)" else "" }
        views.setTextViewText(R.id.n_footer, footer.joinToString(" · "))
        views.setViewVisibility(R.id.n_footer, if (footer.isEmpty()) View.GONE else View.VISIBLE)
        return views
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
