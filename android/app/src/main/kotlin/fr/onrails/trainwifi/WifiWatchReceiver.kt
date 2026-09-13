package fr.onrails.trainwifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import kotlin.concurrent.thread

/** Fired by the system (via the PendingIntent armed in [AutoConnect]) when a Wi-Fi network appears. */
class WifiWatchReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WIFI_AVAILABLE = "fr.onrails.trainwifi.action.WIFI_AVAILABLE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIFI_AVAILABLE) return
        val network: Network? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        }
        val pending = goAsync() // the probe takes a few seconds; keep the receiver alive meanwhile
        val appContext = context.applicationContext
        thread(name = "wifi-watch") {
            try {
                AutoConnect.onWifiAvailable(appContext, network, "Wi-Fi watch")
            } finally {
                pending.finish()
            }
        }
    }
}
