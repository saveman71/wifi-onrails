package fr.onrails.trainwifi

import java.time.Duration
import java.time.ZonedDateTime

/** The two on-board portals. Same API shape, different hostnames. */
enum class Portal(val baseUrl: String, val label: String) {
    SNCF("https://wifi.sncf", "SNCF (TGV INOUI, Intercités)"),
    NORMANDIE("https://wifi.normandie.fr", "Normandie");

    val host: String get() = baseUrl.removePrefix("https://")
    fun url(path: String): String = baseUrl + path
}

enum class Phase(val title: String) {
    STOPPED("Auto-connect disabled"),
    STANDBY("Standby, watching for train Wi-Fi"),
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
    /** Station coordinates when the portal provides them (field names unverified, parsed tolerantly). */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val eta: ZonedDateTime? get() = real ?: theoric
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val isDelayed: Boolean get() = (delayMinutes ?: 0) > 0
}

data class Trip(
    val stops: List<Stop>,
    val progressPercent: Int?,
    /** Sum of traveledDistance over stops with progress data, in metres (as observed on wifi.sncf). */
    val traveledMetres: Double?,
    /** Sum of remainingDistance over stops with progress data, in metres. */
    val remainingMetres: Double?,
) {
    val destination: Stop? get() = stops.lastOrNull()

    /**
     * Index of the next stop: the first one whose ETA is still ahead. Observed on board, the per-stop
     * `progress` object does not mean "distance left to this stop", so time is the primary signal;
     * remainingDistance > 0 is only the fallback when no stop carries a date. -1 when unknown or when
     * every stop is behind us.
     */
    fun nextStopIndex(now: ZonedDateTime = ZonedDateTime.now()): Int {
        if (stops.any { it.eta != null }) return stops.indexOfFirst { it.eta?.isAfter(now) == true }
        return stops.indexOfFirst { (it.remainingDistance ?: 0.0) > 0.0 }
    }

    val nextStop: Stop? get() = stops.getOrNull(nextStopIndex())

    /** Stops from the next one to the destination. All stops when the position is unknown. */
    val remainingStops: List<Stop>
        get() {
            val index = nextStopIndex()
            return if (index < 0) stops else stops.drop(index)
        }

    /** 0..1 position of the train between the previous stop and the next one (time based). */
    fun segmentFraction(now: ZonedDateTime = ZonedDateTime.now()): Float {
        val index = nextStopIndex(now)
        if (index <= 0) return 0f
        val previous = stops[index - 1].eta
        val next = stops[index].eta
        if (previous != null && next != null && next.isAfter(previous)) {
            val total = Duration.between(previous, next).toMillis().toFloat()
            val done = Duration.between(previous, now).toMillis().toFloat()
            return (done / total).coerceIn(0f, 1f)
        }
        val stop = stops[index]
        val traveled = stop.traveledDistance ?: return 0.5f
        val remaining = stop.remainingDistance ?: return 0.5f
        return if (traveled + remaining > 0.0) (traveled / (traveled + remaining)).toFloat() else 0.5f
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
