package fr.onrails.trainwifi

import android.content.Context
import java.io.File

/**
 * Saves the raw body of the portal endpoints the app does not parse yet, so they can be read off
 * the phone after a trip:
 *
 * ```
 * adb pull /sdcard/Android/data/fr.onrails.trainwifi/files/portal
 * ```
 *
 * Runs once per service start, on the first successful poll, over the same network as the rest of
 * the polling. Nothing but the portal is contacted.
 */
object PortalDump {

    /** Path -> file name. Paths are relative to the portal base URL. */
    private val PATHS = mapOf(
        "/router/api/train/graph" to "train-graph.json",
        "/router/api/poi" to "poi.json",
        "/router/api/iframe" to "iframe.json",
        "/router/api/media/wordings?language=en" to "wordings-en.json",
        "/journey/meta.json" to "journey-meta.json",
        "/co2/meta.json" to "co2-meta.json",
        "/karto/style-dark.json" to "style-dark.json",
        "/karto/style-light.json" to "style-light.json",
        "/maps/sprites/dark/dark@2x.json" to "sprites-dark.json",
        "/stationsConnections/wordings/en.json" to "stations-wordings-en.json",
    )

    fun write(context: Context, client: PortalClient, portal: Portal) {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "portal")
        directory.mkdirs()
        for ((path, name) in PATHS) {
            try {
                val response = client.get(portal.url(path))
                File(directory, name).writeText(response.body)
                AppState.log("Dumped $path -> $name (HTTP ${response.code}, ${response.body.length} chars)")
            } catch (e: Exception) {
                AppState.log("Could not dump $path (${e.javaClass.simpleName}): ${e.message}")
            }
        }
        AppState.log("Portal dump written to ${directory.absolutePath}")
    }
}
