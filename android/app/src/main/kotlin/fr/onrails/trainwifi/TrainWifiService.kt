package fr.onrails.trainwifi

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Foreground service that owns the notification, the Wi-Fi [ConnectivityManager.NetworkCallback]
 * and the poll loop. Nothing else in the app talks to the train.
 */
class TrainWifiService : Service() {

    companion object {
        const val ACTION_START = "fr.onrails.trainwifi.action.START"
        const val ACTION_STOP = "fr.onrails.trainwifi.action.STOP"
        const val ACTION_DEMO = "fr.onrails.trainwifi.action.DEMO"
        const val ACTION_REFRESH = "fr.onrails.trainwifi.action.REFRESH"

        const val POLL_INTERVAL_MS = 15_000L
        const val NO_PORTAL_RETRY_MIN_MS = 15_000L
        const val NO_PORTAL_RETRY_MAX_MS = 120_000L
        const val VPN_RETRY_MS = 15_000L

        /** Wi-Fi gone (left the train, or a long tunnel): wait this long before going to standby. */
        const val WIFI_LOST_GRACE_MS = 2 * 60_000L

        /** Wi-Fi present but no portal answering for this long: not a train, go to standby. */
        const val NO_PORTAL_GIVE_UP_MS = 10 * 60_000L

        const val VPN_HINT = "Android refuses to bind this app's sockets to the Wi-Fi while a " +
            "non-bypassable VPN (key icon in the status bar) is active. Turn the VPN off, or exclude " +
            "Train Wi-Fi from it, and the portal will be probed again within 15 s."
        const val DEMO_INTERVAL_MS = 10_000L
        const val FAILURES_BEFORE_REDETECT = 3

        fun start(context: Context) = context.startForegroundService(intent(context, ACTION_START))
        fun demo(context: Context) = context.startForegroundService(intent(context, ACTION_DEMO))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        /** Re-post the notification (e.g. after a settings change). Only meaningful while running. */
        fun refresh(context: Context) {
            if (AppState.state.value.phase != Phase.STOPPED) context.startService(intent(context, ACTION_REFRESH))
        }

        private fun intent(context: Context, action: String) =
            Intent(context, TrainWifiService::class.java).setAction(action)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var connectivity: ConnectivityManager
    private lateinit var notification: TripNotification

    // Touched only on the main thread (callbacks are delivered on the main looper).
    private var wifiNetwork: Network? = null
    private var worker: Job? = null
    private var lostTimer: Job? = null
    private var demoMode = false
    private lateinit var settings: Settings

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == wifiNetwork) return
            AppState.log("Wi-Fi network available: $network")
            lostTimer?.cancel()
            wifiNetwork = network
            if (!demoMode) startPolling(network)
        }

        override fun onLost(network: Network) {
            if (network != wifiNetwork) return
            AppState.log("Wi-Fi network lost: $network")
            wifiNetwork = null
            if (!demoMode) {
                worker?.cancel()
                publish(TrainState(phase = Phase.WAITING_WIFI, message = "Wi-Fi lost, standby in ${WIFI_LOST_GRACE_MS / 60_000} min unless it comes back"))
                lostTimer?.cancel()
                lostTimer = scope.launch {
                    delay(WIFI_LOST_GRACE_MS)
                    standby("Wi-Fi did not come back")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        settings = Settings(this)
        notification = TripNotification(this)
        notification.createChannels()
        notification.cancelTrainDetected()

        // TRANSPORT_WIFI only. Deliberately no NET_CAPABILITY_VALIDATED: the captive-portal
        // network is exactly the unvalidated one we need to see.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivity.registerNetworkCallback(request, wifiCallback, Handler(Looper.getMainLooper()))
        AppState.log("Service created, watching for Wi-Fi networks")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go foreground first, whatever the action, to stay within the FGS start window.
        goForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                // Stop means stop for good: otherwise the watcher would restart us on the next poll.
                AppState.log("Stopped by user")
                AutoConnect.disable(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DEMO -> startDemo()
            ACTION_REFRESH -> Unit // goForeground() above already re-posted the notification on the right channel
            else -> {
                demoMode = false
                val network = wifiNetwork
                if (network != null) {
                    startPolling(network)
                } else {
                    worker?.cancel()
                    publish(TrainState(phase = Phase.WAITING_WIFI, message = "Auto-connect enabled"))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        connectivity.unregisterNetworkCallback(wifiCallback)
        val armed = settings.autoConnectEnabled()
        AppState.update { TrainState(phase = if (armed) Phase.STANDBY else Phase.STOPPED) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (armed) AutoConnect.arm(this) // wake us again on the next Wi-Fi network
        AppState.log(if (armed) "Service stopped, standby (watch armed)" else "Service stopped")
        super.onDestroy()
    }

    /** Leave the foreground; the armed watch (see onDestroy) brings us back on the next train. */
    private fun standby(reason: String) {
        AppState.log("Standby: $reason")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val current = AppState.state.value
        val shown = if (current.phase == Phase.STOPPED) TrainState(phase = Phase.WAITING_WIFI) else current
        val n = notification.build(shown)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TripNotification.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TripNotification.NOTIFICATION_ID, n)
        }
    }

    private fun publish(state: TrainState) {
        AppState.state.value = state
        notification.show(state)
    }

    private fun startPolling(network: Network) {
        worker?.cancel()
        worker = scope.launch { pollLoop(network) }
    }

    private fun startDemo() {
        demoMode = true
        worker?.cancel()
        worker = scope.launch {
            AppState.log("Demo mode: cycling canned wifi.sncf and wifi.normandie.fr responses")
            var i = 0
            while (isActive) {
                publish(DemoData.toState(DemoData.samples[i % DemoData.samples.size]))
                i++
                delay(DEMO_INTERVAL_MS)
            }
        }
    }

    /**
     * 1. Probe both portals over the bound network until one answers /connection/status.
     * 2. If the grant is not active, POST activate/auto, then tell Android the network works.
     * 3. Poll status, statistics, gps, train details and bar attendance every 15 s.
     */
    private suspend fun pollLoop(network: Network) {
        val client = PortalClient(network)
        var api: PortalApi? = null
        var reportedValid = false
        var dumped = false
        var failures = 0
        var noPortalDelay = NO_PORTAL_RETRY_MIN_MS
        var noPortalSince = 0L

        publish(TrainState(phase = Phase.PROBING, message = "Probing wifi.sncf then wifi.normandie.fr"))

        while (coroutineContext.isActive) {
            try {
                if (api == null) {
                    val detection = PortalDetector.detect(client)
                    api = detection.api
                    if (api == null) {
                        if (noPortalSince == 0L) noPortalSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - noPortalSince > NO_PORTAL_GIVE_UP_MS && !demoMode) {
                            standby("no train portal on this Wi-Fi for ${NO_PORTAL_GIVE_UP_MS / 60_000} min")
                            return
                        }
                        if (detection.bindingRefused || isVpnActive()) {
                            // netd returns EPERM for Network.bindSocket when a secure VPN applies to
                            // this UID. Nothing to do but wait for the VPN to go away, so poll fast.
                            publish(TrainState(phase = Phase.VPN_BLOCKED, message = VPN_HINT))
                            delay(VPN_RETRY_MS)
                        } else {
                            publish(TrainState(phase = Phase.NO_PORTAL, message = "Retrying in ${noPortalDelay / 1000} s"))
                            delay(noPortalDelay)
                            noPortalDelay = min(noPortalDelay * 2, NO_PORTAL_RETRY_MAX_MS)
                        }
                        continue
                    }
                    noPortalDelay = NO_PORTAL_RETRY_MIN_MS
                }
                noPortalSince = 0L
                val portal: PortalApi = api

                var status = portal.connectionStatus()
                AppState.log("Status: active=${status.active} (${status.description})")

                if (!status.active) {
                    publish(AppState.state.value.copy(phase = Phase.ACTIVATING, portal = portal.portal, connection = status))
                    val ok = portal.activate()
                    AppState.log(if (ok) "Activation accepted" else "Activation answered but grant still inactive")
                    status = portal.connectionStatus()
                }

                if (status.active && !reportedValid) {
                    // Android marked the Wi-Fi as a captive portal. Ask it to revalidate now, otherwise
                    // suggested networks that never validate get deprioritised.
                    connectivity.reportNetworkConnectivity(network, true)
                    reportedValid = true
                    AppState.log("Reported network connectivity to Android")
                }

                if (!dumped) {
                    dumped = true
                    PortalDump.write(this@TrainWifiService, client, portal.portal)
                }

                val statistics = fetch("statistics") { portal.statistics() }
                val gps = fetch("gps") { portal.gps() }
                val trip = fetch("train details") { portal.trainDetails() }
                val bar = fetch("bar attendance") { portal.barQueueEmpty() }

                publish(
                    TrainState(
                        phase = Phase.ACTIVE,
                        portal = portal.portal,
                        message = if (status.active) "Internet granted" else status.description,
                        connection = status,
                        statistics = statistics,
                        gps = gps,
                        trip = trip,
                        barQueueEmpty = bar,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                AppState.log("Poll failed (${e.javaClass.simpleName}): ${e.message}")
                if (failures >= FAILURES_BEFORE_REDETECT) {
                    AppState.log("Too many failures, will re-detect the portal")
                    api = null
                    reportedValid = false
                    failures = 0
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** True when the network Android would use for this app is a VPN. */
    private fun isVpnActive(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Best effort: a failing secondary endpoint must not take the whole poll down. */
    private inline fun <T> fetch(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            AppState.log("Could not fetch $what (${e.javaClass.simpleName}): ${e.message}")
            null
        }
}
