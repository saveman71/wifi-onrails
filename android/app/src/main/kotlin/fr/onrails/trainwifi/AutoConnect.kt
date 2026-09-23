package fr.onrails.trainwifi

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import java.util.concurrent.TimeUnit

/**
 * The always-on part, designed so that nothing runs between trips:
 *
 * 1. A [ConnectivityManager.registerNetworkCallback] with a PendingIntent. The system fires
 *    [WifiWatchReceiver] whenever a Wi-Fi network appears, even if the app process is dead.
 *    That registration is one-shot: see [rearm].
 * 2. The receiver probes the portal over that network and starts [TrainWifiService] only if a
 *    train answers. At home the probe fails fast and nothing else happens.
 * 3. The service stops itself when the Wi-Fi is gone or turns out not to be a train, then re-arms.
 * 4. [BootReceiver] re-arms after a reboot, and a persisted 15-minute [WatchJobService] re-arms the
 *    watch and repeats the probe.
 *
 * Android 12+ lets a background receiver start a foreground service only if the app is exempt
 * from battery optimisations (the exemption also keeps network access during Doze). Without it,
 * [onWifiAvailable] falls back to a tappable "train detected" notification.
 */
object AutoConnect {

    private const val WATCH_REQUEST_CODE = 100
    private const val WATCH_JOB_ID = 1
    private const val WIFI_JOB_ID = 2

    /** How long after a Wi-Fi that is not a train before [WifiJobService] looks again. */
    private val WIFI_JOB_RETRY_MS = TimeUnit.MINUTES.toMillis(5)

    fun enable(context: Context) {
        Settings(context).setAutoConnectEnabled(true)
        arm(context)
        scheduleWatchJob(context)
        scheduleWifiJob(context)
        AppState.log("Auto-connect enabled: watching for Wi-Fi networks")
        AppState.update { if (it.phase == Phase.STOPPED) TrainState(phase = Phase.STANDBY) else it }
    }

    fun disable(context: Context) {
        Settings(context).setAutoConnectEnabled(false)
        disarm(context)
        cancelWatchJob(context)
        cancelWifiJob(context)
        AppState.log("Auto-connect disabled")
    }

    /** Ask the system to wake [WifiWatchReceiver] when a Wi-Fi network (validated or not) is available. */
    fun arm(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val intent = watchIntent(context)
        try {
            connectivity.registerNetworkCallback(request, intent)
            AppState.log("Wi-Fi watch armed on $intent")
        } catch (e: Exception) {
            AppState.log("Wi-Fi watch NOT armed (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    /**
     * ConnectivityService drops a PendingIntent registration about 5 s after it sends the intent, so
     * one registration covers exactly one Wi-Fi network. Joining any non-train Wi-Fi uses it up, and
     * from then on only [WatchJobService] is left. Observed on a Pixel, Android 16:
     *
     * ```
     * 07:40:15.985 REGISTER  id=62183 LISTEN WIFI
     * 07:40:16.015 (WifiWatchReceiver runs)
     * 07:40:21.249 RELEASE   id=62183
     * ```
     *
     * Registering again straight away would loop, because a registration made while a Wi-Fi is
     * already there sends the intent within milliseconds. The job does it instead, every 15 min.
     * When the job runs with no Wi-Fi around, the registration stays until the next network shows
     * up, which is the case that matters: getting on a train.
     */
    fun rearm(context: Context) {
        disarm(context)
        arm(context)
    }

    fun disarm(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        try {
            connectivity.unregisterNetworkCallback(watchIntent(context))
            AppState.log("Wi-Fi watch disarmed")
        } catch (e: IllegalArgumentException) {
            AppState.log("Wi-Fi watch was not armed, nothing to disarm")
        }
    }

    private fun watchIntent(context: Context): PendingIntent {
        val intent = Intent(context, WifiWatchReceiver::class.java).setAction(WifiWatchReceiver.ACTION_WIFI_AVAILABLE)
        // Mutable so the system can attach EXTRA_NETWORK to the delivered intent.
        return PendingIntent.getBroadcast(context, WATCH_REQUEST_CODE, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun scheduleWatchJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(WATCH_JOB_ID, ComponentName(context, WatchJobService::class.java))
            .setPeriodic(TimeUnit.MINUTES.toMillis(15))
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    private fun cancelWatchJob(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(WATCH_JOB_ID)
    }

    /**
     * One run, waiting on a Wi-Fi network. `setRequiredNetworkType` cannot be used: JobInfo.Builder
     * adds NET_CAPABILITY_VALIDATED to every type it knows, and a captive portal has none until the
     * app has activated it. A NetworkRequest is stored as written.
     */
    fun scheduleWifiJob(context: Context) {
        if (!Settings(context).autoConnectEnabled()) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val job = JobInfo.Builder(WIFI_JOB_ID, ComponentName(context, WifiJobService::class.java))
            .setRequiredNetwork(request)
            .setMinimumLatency(WIFI_JOB_RETRY_MS)
            .setPersisted(true)
            .build()
        val result = context.getSystemService(JobScheduler::class.java).schedule(job)
        AppState.log(
            if (result == JobScheduler.RESULT_SUCCESS) "Wi-Fi job scheduled, next look in ${WIFI_JOB_RETRY_MS / 60_000} min"
            else "Wi-Fi job refused by the scheduler (result $result)",
        )
    }

    private fun cancelWifiJob(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(WIFI_JOB_ID)
    }

    /**
     * Shared by the receiver and the job: probe the Wi-Fi network and start the service if it is a
     * train. Runs on a worker thread. Returns true when the service was started.
     */
    fun onWifiAvailable(context: Context, hint: Network?, source: String): Boolean {
        if (!Settings(context).autoConnectEnabled()) {
            AppState.log("$source: auto-connect is off, ignoring")
            return false
        }
        val phase = AppState.state.value.phase
        if (phase.isRunning()) {
            AppState.log("$source: already $phase, nothing to do")
            return false
        }

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = hint ?: PortalDetector.findWifiNetwork(connectivity)
        if (network == null) {
            AppState.log("$source: no Wi-Fi network, staying in standby")
            return false
        }
        AppState.log("$source: Wi-Fi $network available, probing for a train portal")
        val detection = PortalDetector.quickDetect(network)
        if (detection.api != null) return startServiceOrNotify(context, detection.api.portal)
        if (detection.hostResolved) {
            // The service retries with backoff and follows the phone onto the next Wi-Fi.
            // Giving up here leaves nothing to look at this network again.
            AppState.log("$source: portal name resolves here but the portal did not answer, starting the service anyway")
            return startServiceOrNotify(context, Portal.SNCF)
        }
        AppState.log(
            if (detection.bindingRefused) "$source: socket binding refused (VPN active?), staying in standby"
            else "$source: not a train, staying in standby",
        )
        return false
    }

    private fun startServiceOrNotify(context: Context, portal: Portal): Boolean {
        try {
            TrainWifiService.start(context)
            AppState.log("Service started for ${portal.host}")
            return true
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException unless exempt from battery optimisations.
            AppState.log("Cannot start the service from the background (${e.javaClass.simpleName}); posting a tap-to-connect notification")
            TripNotification(context).showTrainDetected(portal)
            return false
        }
    }

    fun isExemptFromBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** System dialog asking to exempt the app; needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. */
    fun requestBatteryExemptionIntent(context: Context): Intent =
        Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun Phase.isRunning(): Boolean = this != Phase.STOPPED && this != Phase.STANDBY
}
