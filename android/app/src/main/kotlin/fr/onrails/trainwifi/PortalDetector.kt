package fr.onrails.trainwifi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Finds out whether a Wi-Fi [Network] is a train: probes /connection/status on each portal. */
object PortalDetector {

    class Detection(val api: PortalApi?, val bindingRefused: Boolean)

    fun detect(client: PortalClient): Detection {
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
        return Detection(null, bindingRefused)
    }

    /** Short-timeout variant for broadcast receivers and jobs, which have a limited time budget. */
    fun quickDetect(network: Network): Detection =
        detect(PortalClient(network, connectTimeoutMs = 3_000, readTimeoutMs = 4_000))

    /** The current Wi-Fi network, validated or not, or null. */
    fun findWifiNetwork(connectivity: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}
