package fr.onrails.trainwifi

import org.json.JSONObject

/** The reverse-engineered on-board API, one instance per detected portal. */
class PortalApi(private val client: PortalClient, val portal: Portal) {

    fun connectionStatus(): ConnectionStatus =
        Parsers.connectionStatus(client.getJson(portal.url("/router/api/connection/status")))

    /**
     * The call that clears the captive portal. Headers mirror the working curl call in
     * wifi_sncf.sh; the payload is the one observed on wifi.sncf and is unverified on Normandie.
     */
    fun activate(): Boolean {
        val body = JSONObject().put("without21NetConnection", false)
        val headers = mapOf(
            "Origin" to portal.baseUrl,
            "Referer" to portal.url("/en/internet/login"),
        )
        val json = client.postJson(portal.url("/router/api/connection/activate/auto"), body, headers)
        return Parsers.activationActive(json)
    }

    fun statistics(): Statistics =
        Parsers.statistics(client.getJson(portal.url("/router/api/connection/statistics")))

    fun gps(): Gps =
        Parsers.gps(client.getJson(portal.url("/router/api/train/gps")))

    fun trainDetails(): Trip =
        Parsers.trip(client.getJson(portal.url("/router/api/train/details")))

    fun barQueueEmpty(): Boolean =
        Parsers.barQueueEmpty(client.getJson(portal.url("/router/api/bar/attendance")))

    fun path(): List<LatLon> =
        Parsers.path(client.getJson(portal.url("/router/api/train/graph")))
}
