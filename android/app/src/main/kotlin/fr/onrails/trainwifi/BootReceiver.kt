package fr.onrails.trainwifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the Wi-Fi watch after a reboot or an app update, if auto-connect is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Settings(context).autoConnectEnabled()) {
                    AppState.log("${intent.action}: re-arming the Wi-Fi watch")
                    AutoConnect.enable(context) // idempotent: arm + (re)schedule the job
                }
            }
        }
    }
}
