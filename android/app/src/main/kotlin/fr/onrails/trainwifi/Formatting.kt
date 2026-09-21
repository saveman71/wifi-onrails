package fr.onrails.trainwifi

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Small, shared text helpers used by both the notification and the activity. */
object Formatting {
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")
    private val hourMinuteSecond = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun time(t: ZonedDateTime?): String = t?.withZoneSameInstant(Parsers.PARIS)?.format(hourMinute) ?: "--:--"

    fun timeWithSeconds(t: ZonedDateTime): String = t.withZoneSameInstant(Parsers.PARIS).format(hourMinuteSecond)

    /** "+5 min" or "" when on time / unknown. */
    fun delay(stop: Stop): String {
        val d = stop.delayMinutes ?: return ""
        return if (d > 0) " (+$d min)" else ""
    }

    /** "Grenoble 13:18 (+5 min)" */
    fun stopLine(stop: Stop): String = "${stop.name} ${time(stop.eta)}${delay(stop)}"

    fun speed(gps: Gps?): String? {
        if (gps == null || !gps.fix) return null
        val kmh = gps.speedKmh ?: return null
        return "${kmh.toInt()} km/h"
    }

    /** Metres in, "316 km" out. */
    fun km(metres: Double): String = "${(metres / 1000.0).roundToInt()} km"

    /**
     * Splits "293 km/h" into the figure and its unit. The portal sets the figure in Avenir Black
     * and the unit small and upright beside it, both in the same burgundy.
     */
    fun figureAndUnit(text: String?): Pair<String, String> {
        if (text == null) return "–" to ""
        val space = text.indexOf(' ')
        return if (space < 0) text to "" else text.take(space) to text.substring(space + 1)
    }

    /** Whole minutes from now to [t], or null when unknown or already past. */
    fun minutesUntil(t: ZonedDateTime?): Long? {
        if (t == null) return null
        val minutes = Duration.between(ZonedDateTime.now(), t).toMinutes()
        return if (minutes >= 0) minutes else null
    }

    fun mb(value: Double): String = String.format(Locale.ROOT, "%.0f MB", value)

    fun quota(c: ConnectionStatus): String =
        "Data: ${mb(c.remainingMb)} left of ${mb(c.totalMb)}, ${String.format(Locale.ROOT, "%.1f", c.bandwidthMbPerSec)} MB/s"

    fun wifi(s: Statistics?, barQueueEmpty: Boolean?): String? {
        val parts = mutableListOf<String>()
        if (s != null) {
            if (s.quality >= 0) parts += "Wi-Fi quality ${s.quality}/5"
            if (s.devices >= 0) parts += "${s.devices} devices"
        }
        if (barQueueEmpty != null) parts += if (barQueueEmpty) "bar queue empty" else "bar queue busy"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
