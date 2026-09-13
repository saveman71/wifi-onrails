package fr.onrails.trainwifi

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion

/**
 * Registers one open-network suggestion per SSID. On modern Android this is the only way a normal
 * app can make the device join a network unattended: the user approves the app once, then the
 * system connects on its own whenever one of the SSIDs is in range.
 */
object WifiSuggestions {

    data class Result(val ok: Boolean, val message: String)

    fun apply(context: Context, ssids: List<String>): Result {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return Result(false, "WifiManager unavailable")

        // Remove everything we suggested before, so edits to the list take effect.
        wifi.removeNetworkSuggestions(emptyList())

        val suggestions = ssids.mapNotNull { ssid ->
            try {
                WifiNetworkSuggestion.Builder().setSsid(ssid).build() // no passphrase = open network
            } catch (e: IllegalArgumentException) {
                AppState.log("Skipping invalid SSID '$ssid': ${e.message}")
                null
            }
        }
        if (suggestions.isEmpty()) return Result(false, "No valid SSID to suggest")

        return when (val status = wifi.addNetworkSuggestions(suggestions)) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                Result(true, "Registered ${suggestions.size} Wi-Fi suggestions: ${ssids.joinToString(", ")}")

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                Result(
                    false,
                    "Wi-Fi suggestions are disallowed for this app. Allow it under " +
                        "Settings > Network & internet > Internet > Network preferences (Wi-Fi preferences) " +
                        "> Apps that can suggest networks, then press Enable again.",
                )

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
                Result(false, "Too many SSIDs for one app, shorten the list")

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL ->
                Result(false, "Wi-Fi subsystem internal error, retry (is Wi-Fi on?)")

            else ->
                Result(false, "addNetworkSuggestions failed with status $status")
        }
    }
}
