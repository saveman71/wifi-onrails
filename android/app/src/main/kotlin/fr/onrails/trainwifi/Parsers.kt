package fr.onrails.trainwifi

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/**
 * Tolerant parsers for the portal JSON. Every accessor is optional because the two
 * portals disagree on field names and because fields come and go between firmware versions.
 * Pure Kotlin + org.json, so it can be exercised off-device.
 */
object Parsers {
    val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    fun connectionStatus(json: JSONObject): ConnectionStatus = ConnectionStatus(
        active = json.optBoolean("active", false),
        description = json.str("status_description") ?: "",
        grantedBandwidthKb = json.optLong("granted_bandwidth", 0L),
        remainingDataKb = json.optLong("remaining_data", 0L),
        consumedDataKb = json.optLong("consumed_data", 0L),
        nextResetMillis = json.optLong("next_reset", 0L),
    )

    /** POST .../activate/auto -> { travel: { status: { active, status_description } } } */
    fun activationActive(json: JSONObject): Boolean {
        val status = json.optJSONObject("travel")?.optJSONObject("status")
        // Some firmware answers with the plain status object instead of the travel wrapper.
        return (status ?: json).optBoolean("active", false)
    }

    fun statistics(json: JSONObject): Statistics = Statistics(
        quality = json.optInt("quality", -1),
        devices = json.optInt("devices", -1),
    )

    fun gps(json: JSONObject): Gps {
        val speedMs = json.num("speed")
        return Gps(
            fix = json.optBoolean("fix", speedMs != null),
            speedKmh = speedMs?.let { it * 3.6 },
            altitudeM = json.num("altitude"),
            latitude = json.num("latitude"),
            longitude = json.num("longitude"),
        )
    }

    fun barQueueEmpty(json: JSONObject): Boolean = json.optBoolean("isBarQueueEmpty", false)

    fun trip(json: JSONObject): Trip {
        val array: JSONArray = json.optJSONArray("stops") ?: JSONArray()
        val stops = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(::stop)

        var traveled = 0.0
        var remaining = 0.0
        var withProgress = 0
        for (s in stops) {
            if (s.traveledDistance != null && s.remainingDistance != null) {
                traveled += s.traveledDistance
                remaining += s.remainingDistance
                withProgress++
            }
        }
        val total = traveled + remaining
        val percent = if (total > 0.0) (traveled / total * 100.0).roundToInt().coerceIn(0, 100) else null
        return Trip(
            stops = stops,
            progressPercent = percent,
            traveledDistance = if (withProgress > 0) traveled else null,
            remainingDistance = if (withProgress > 0) remaining else null,
        )
    }

    /**
     * wifi.sncf:          name, label, delay, isDelayed, theoricDate, realDate
     * wifi.normandie.fr:  location.name, arrival.date, arrival.realDate
     * both:               progress: { traveledDistance, remainingDistance } (nullable)
     */
    fun stop(obj: JSONObject): Stop {
        val location = obj.optJSONObject("location")
        val arrival = obj.optJSONObject("arrival")
        val progress = obj.optJSONObject("progress")

        val name = obj.str("name")
            ?: obj.str("label")
            ?: location?.str("name")
            ?: location?.str("label")
            ?: "?"

        val theoric = parseDate(obj.str("theoricDate") ?: arrival?.str("date") ?: arrival?.str("theoricDate"))
        val real = parseDate(obj.str("realDate") ?: arrival?.str("realDate"))

        val delay: Int? = when {
            obj.has("delay") && !obj.isNull("delay") -> obj.optInt("delay")
            arrival != null && arrival.has("delay") && !arrival.isNull("delay") -> arrival.optInt("delay")
            theoric != null && real != null -> Duration.between(theoric, real).toMinutes().toInt()
            else -> null
        }

        return Stop(
            name = name,
            theoric = theoric,
            real = real,
            delayMinutes = delay,
            traveledDistance = progress?.num("traveledDistance"),
            remainingDistance = progress?.num("remainingDistance"),
        )
    }

    /**
     * ISO 8601, sometimes with an offset ("2024-04-01T13:13:00+02:00", "...Z"),
     * sometimes without ("2024-04-01T13:13:00"). Offset-less values are taken as Paris local time.
     */
    fun parseDate(raw: String?): ZonedDateTime? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).atZoneSameInstant(PARIS)
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(raw).atZone(PARIS)
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    /** optString that treats JSON null and "" as absent instead of returning the string "null". */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /** optDouble that returns null instead of NaN for missing or non-numeric values. */
    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key)
        return if (value.isNaN()) null else value
    }
}
