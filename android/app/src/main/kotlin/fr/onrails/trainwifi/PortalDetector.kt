package fr.onrails.trainwifi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.UnknownHostException

/** Finds out whether a Wi-Fi [Network] is a train: probes /connection/status on each portal. */
object PortalDetector {

    /**
     * [hostResolved]: a portal name resolved over this Wi-Fi even though the portal did not answer.
     * Those names only resolve through a train's own DNS (off a train the lookup fails with
     * UnknownHostException), so this is a train whose portal is slow or down. Seen on a TGV in
     * multiple unit: the first unit's portal timed out on 443, and a minute later the phone moved to
     * the other unit's Wi-Fi, where the portal answered.
     */
    class Detection(val api: PortalApi?, val bindingRefused: Boolean, val hostResolved: Boolean = false)

    fun detect(client: PortalClient): Detection {
        var bindingRefused = false
        var hostResolved = false
        for (portal in Portal.entries) {
            val api = PortalApi(client, portal)
            try {
                api.connectionStatus()
                AppState.log("Portal detected: ${portal.baseUrl}")
                return Detection(api, false, true)
            } catch (e: Exception) {
                AppState.log("No portal at ${portal.host}: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                if (e.message?.contains("EPERM") == true) bindingRefused = true
                if (e !is UnknownHostException && !bindingRefused) hostResolved = true
            }
        }
        return Detection(null, bindingRefused, hostResolved)
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
