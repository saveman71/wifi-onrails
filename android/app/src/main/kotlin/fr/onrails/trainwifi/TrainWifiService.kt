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

        const val POLL_INTERVAL_MS = 15_000L
        const val NO_PORTAL_RETRY_MIN_MS = 15_000L
        const val NO_PORTAL_RETRY_MAX_MS = 120_000L
        const val VPN_RETRY_MS = 15_000L

        const val VPN_HINT = "Android refuses to bind this app's sockets to the Wi-Fi while a " +
            "non-bypassable VPN (key icon in the status bar) is active. Turn the VPN off, or exclude " +
            "Train Wi-Fi from it, and the portal will be probed again within 15 s."
        const val DEMO_INTERVAL_MS = 10_000L
        const val FAILURES_BEFORE_REDETECT = 3

        fun start(context: Context) = context.startForegroundService(intent(context, ACTION_START))
        fun demo(context: Context) = context.startForegroundService(intent(context, ACTION_DEMO))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        private fun intent(context: Context, action: String) =
            Intent(context, TrainWifiService::class.java).setAction(action)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var connectivity: ConnectivityManager
    private lateinit var notification: TripNotification

    // Touched only on the main thread (callbacks are delivered on the main looper).
    private var wifiNetwork: Network? = null
    private var worker: Job? = null
    private var demoMode = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == wifiNetwork) return
            AppState.log("Wi-Fi network available: $network")
            wifiNetwork = network
            if (!demoMode) startPolling(network)
        }

        override fun onLost(network: Network) {
            if (network != wifiNetwork) return
            AppState.log("Wi-Fi network lost: $network")
            wifiNetwork = null
            if (!demoMode) {
                worker?.cancel()
                publish(TrainState(phase = Phase.WAITING_WIFI, message = "Wi-Fi lost"))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        notification = TripNotification(this)
        notification.createChannel()

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
                AppState.log("Stopped by user")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DEMO -> startDemo()
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
        AppState.update { TrainState(phase = Phase.STOPPED) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppState.log("Service destroyed")
        super.onDestroy()
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
        var failures = 0
        var noPortalDelay = NO_PORTAL_RETRY_MIN_MS

        publish(TrainState(phase = Phase.PROBING, message = "Probing wifi.sncf then wifi.normandie.fr"))

        while (coroutineContext.isActive) {
            try {
                if (api == null) {
                    val detection = detectPortal(client)
                    api = detection.api
                    if (api == null) {
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

    private class Detection(val api: PortalApi?, val bindingRefused: Boolean)

    private fun detectPortal(client: PortalClient): Detection {
        var bindingRefused = false
        for (portal in Portal.entries) {
            val api = PortalApi(client, portal)
            try {
                api.connectionStatus()
                AppState.log("Portal detected: ${portal.baseUrl}")
                return Detection(api, false)
            } catch (e: Exception) {
                AppState.log("No portal at ${portal.host}: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                if (e.message?.contains("EPERM") == true) bindingRefused = true
            }
        }
        if (bindingRefused) AppState.log("Socket binding refused (EPERM): a VPN is routing this app. $VPN_HINT")
        return Detection(null, bindingRefused)
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
