package fr.onrails.trainwifi

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Canned portal responses for both stop shapes so the parsers and the notification can be
 * checked without a train, and without any network at all.
 */
object DemoData {

    private val withOffset = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    private val withoutOffset = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** ISO date [minutes] from now, in Paris time, with or without the offset suffix. */
    private fun at(minutes: Long, offset: Boolean): String {
        val t = ZonedDateTime.now(Parsers.PARIS).withSecond(0).withNano(0).plusMinutes(minutes)
        return t.format(if (offset) withOffset else withoutOffset)
    }

    class Sample(
        val portal: Portal,
        val status: String,
        val statistics: String,
        val gps: String,
        val details: String,
        val bar: String,
    )

    /** wifi.sncf shape: flat stops with name/label/delay/isDelayed/theoricDate/realDate, offset dates. */
    val sncf: Sample get() = Sample(
        portal = Portal.SNCF,
        status = """{"active":true,"status_description":"identifier has existing grant","granted_bandwidth":100000,
            "remaining_data":108700,"consumed_data":915300,"next_reset":1711964975000}""",
        statistics = """{"quality":5,"devices":126}""",
        gps = """{"success":true,"fix":true,"timestamp":1711965000000,"latitude":44.93,"longitude":4.89,
            "altitude":356.63,"speed":82.7,"heading":120.5}""",
        details = """{"stops":[
            {"name":"Paris Gare de Lyon","label":"PARIS GARE DE LYON","delay":0,"isDelayed":false,
             "theoricDate":"${at(-92, true)}","realDate":"${at(-92, true)}",
             "progress":{"traveledDistance":0,"remainingDistance":0}},
            {"name":"Lyon Part-Dieu","label":"LYON PART DIEU","delay":0,"isDelayed":false,
             "theoricDate":"${at(-12, true)}","realDate":"${at(-12, true)}",
             "progress":{"traveledDistance":465000,"remainingDistance":0}},
            {"name":"Valence TGV","label":"VALENCE TGV","delay":5,"isDelayed":true,
             "theoricDate":"${at(7, true)}","realDate":"${at(12, true)}",
             "progress":{"traveledDistance":98000,"remainingDistance":6000}},
            {"name":"Grenoble","label":"GRENOBLE","delay":5,"isDelayed":true,
             "theoricDate":"${at(45, true)}","realDate":"${at(50, true)}",
             "progress":{"traveledDistance":0,"remainingDistance":50000}}
        ]}""",
        bar = """{"isBarQueueEmpty":false}""",
    )

    /** wifi.normandie.fr shape: location.name + arrival.date/realDate, offset-less dates, one null progress. */
    val normandie: Sample get() = Sample(
        portal = Portal.NORMANDIE,
        status = """{"active":true,"status_description":"ok","granted_bandwidth":51200,
            "remaining_data":409600,"consumed_data":102400,"next_reset":1711980000000}""",
        statistics = """{"quality":3,"devices":48}""",
        gps = """{"success":true,"fix":true,"timestamp":1711965000000,"latitude":49.02,"longitude":0.71,
            "altitude":98.0,"speed":44.4,"heading":270.0}""",
        details = """{"stops":[
            {"location":{"name":"Paris Saint-Lazare"},
             "arrival":{"date":"${at(-55, false)}","realDate":"${at(-55, false)}"},
             "progress":null},
            {"location":{"name":"Évreux-Normandie"},
             "arrival":{"date":"${at(-10, false)}","realDate":"${at(-10, false)}"},
             "progress":{"traveledDistance":96000,"remainingDistance":0}},
            {"location":{"name":"Bernay"},
             "arrival":{"date":"${at(17, false)}","realDate":"${at(20, false)}"},
             "progress":{"traveledDistance":30000,"remainingDistance":13000}},
            {"location":{"name":"Lisieux"},
             "arrival":{"date":"${at(33, false)}","realDate":"${at(36, false)}"},
             "progress":{"traveledDistance":0,"remainingDistance":24000}},
            {"location":{"name":"Caen"},
             "arrival":{"date":"${at(67, false)}","realDate":"${at(70, false)}"},
             "progress":{"traveledDistance":0,"remainingDistance":50000}}
        ]}""",
        bar = """{"isBarQueueEmpty":true}""",
    )

    val samples: List<Sample> get() = listOf(sncf, normandie)

    /** Run a sample through the same parsers the service uses and build the resulting state. */
    fun toState(sample: Sample): TrainState {
        val prefix = "demo ${sample.portal.host}"
        AppState.logRaw("$prefix /connection/status", sample.status)
        AppState.logRaw("$prefix /connection/statistics", sample.statistics)
        AppState.logRaw("$prefix /train/gps", sample.gps)
        AppState.logRaw("$prefix /train/details", sample.details)
        AppState.logRaw("$prefix /bar/attendance", sample.bar)
        return TrainState(
            phase = Phase.DEMO,
            portal = sample.portal,
            message = "Demo data, ${sample.portal.label} shape",
            connection = Parsers.connectionStatus(JSONObject(sample.status)),
            statistics = Parsers.statistics(JSONObject(sample.statistics)),
            gps = Parsers.gps(JSONObject(sample.gps)),
            trip = Parsers.trip(JSONObject(sample.details)),
            barQueueEmpty = Parsers.barQueueEmpty(JSONObject(sample.bar)),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }
}
