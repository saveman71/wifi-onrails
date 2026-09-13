package fr.onrails.trainwifi

import java.time.ZonedDateTime

/** The two on-board portals. Same API shape, different hostnames. */
enum class Portal(val baseUrl: String, val label: String) {
    SNCF("https://wifi.sncf", "SNCF (TGV INOUI, Intercités)"),
    NORMANDIE("https://wifi.normandie.fr", "Normandie");

    val host: String get() = baseUrl.removePrefix("https://")
    fun url(path: String): String = baseUrl + path
}

enum class Phase(val title: String) {
    STOPPED("Stopped"),
    WAITING_WIFI("Waiting for train Wi-Fi"),
    PROBING("Wi-Fi connected, looking for a train portal"),
    NO_PORTAL("Wi-Fi connected, no train portal found"),
    VPN_BLOCKED("A VPN blocks access to the train Wi-Fi"),
    ACTIVATING("Activating train Wi-Fi"),
    ACTIVE("On board"),
    DEMO("Demo trip"),
}

/** GET /router/api/connection/status. Data volumes are in kB. */
data class ConnectionStatus(
    val active: Boolean,
    val description: String,
    val grantedBandwidthKb: Long,
    val remainingDataKb: Long,
    val consumedDataKb: Long,
    val nextResetMillis: Long,
) {
    /** Same rendering as wifi_sncf.sh: granted_bandwidth / 1024 / 10 MB/s. */
    val bandwidthMbPerSec: Double get() = grantedBandwidthKb / 1024.0 / 10.0
    val remainingMb: Double get() = remainingDataKb / 1024.0
    val consumedMb: Double get() = consumedDataKb / 1024.0
    val totalMb: Double get() = remainingMb + consumedMb
}

/** GET /router/api/connection/statistics */
data class Statistics(val quality: Int, val devices: Int)

/** GET /router/api/train/gps. Speed already converted to km/h. */
data class Gps(
    val fix: Boolean,
    val speedKmh: Double?,
    val altitudeM: Double?,
    val latitude: Double?,
    val longitude: Double?,
)

/** One entry of GET /router/api/train/details -> stops[]. Normalised over both portal shapes. */
data class Stop(
    val name: String,
    val theoric: ZonedDateTime?,
    val real: ZonedDateTime?,
    /** Minutes. From the `delay` field when present, else real - theoric. */
    val delayMinutes: Int?,
    val traveledDistance: Double?,
    val remainingDistance: Double?,
) {
    val eta: ZonedDateTime? get() = real ?: theoric
    val isDelayed: Boolean get() = (delayMinutes ?: 0) > 0
}

data class Trip(val stops: List<Stop>, val progressPercent: Int?) {
    val destination: Stop? get() = stops.lastOrNull()

    /** First stop that still has distance to cover. */
    val nextStop: Stop? get() = stops.firstOrNull { (it.remainingDistance ?: 0.0) > 0.0 }

    /** Stops from the next one to the destination. Falls back to all stops when no progress data is available. */
    val remainingStops: List<Stop>
        get() {
            val index = stops.indexOfFirst { (it.remainingDistance ?: 0.0) > 0.0 }
            return if (index < 0) stops else stops.drop(index)
        }
}

/** Everything the UI and the notification need. Written by the service, read by everyone else. */
data class TrainState(
    val phase: Phase = Phase.STOPPED,
    val portal: Portal? = null,
    val message: String = "",
    val connection: ConnectionStatus? = null,
    val statistics: Statistics? = null,
    val gps: Gps? = null,
    val trip: Trip? = null,
    val barQueueEmpty: Boolean? = null,
    val updatedAtMillis: Long = 0L,
)
