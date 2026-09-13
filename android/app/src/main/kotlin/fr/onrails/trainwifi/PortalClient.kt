package fr.onrails.trainwifi

import android.net.Network
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client bound to one [Network].
 *
 * Every request goes through [Network.openConnection]. This is the whole reason a naive port of
 * wifi_sncf.sh fails on Android: before the portal is activated the Wi-Fi is "unvalidated", the
 * default route stays on mobile data, and wifi.sncf only resolves through the train's DNS.
 * A plain URL.openConnection() would leave the train.
 *
 * Redirects are followed by hand (max [MAX_REDIRECTS] hops) so that an https -> http hop does not
 * silently fail: HttpURLConnection refuses to follow redirects across protocols.
 */
class PortalClient(private val network: Network) {

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 6_000
        const val MAX_REDIRECTS = 3

        /** Desktop Chrome, same as the working curl call in wifi_sncf.sh. */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }

    class Response(val code: Int, val body: String, val url: String)

    class HttpException(val code: Int, url: String, body: String) :
        IOException("HTTP $code from $url: ${body.take(160)}")

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        toJson(request(url, "GET", null, headers))

    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        toJson(request(url, "POST", body.toString(), headers))

    private fun toJson(response: Response): JSONObject {
        if (response.code >= 400) throw HttpException(response.code, response.url, response.body)
        return JSONObject(response.body) // throws JSONException on HTML captive pages etc.
    }

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>, hop: Int = 0): Response {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code from $url without a Location header")
                if (hop >= MAX_REDIRECTS) throw IOException("Too many redirects starting from $url")
                val target = URL(URL(url), location).toString()
                AppState.log("HTTP $code $method ${URL(url).path} -> $target")
                // 307/308 keep method and body; everything else degrades to GET like a browser.
                return if (code == 307 || code == 308) {
                    request(target, method, body, headers, hop + 1)
                } else {
                    request(target, "GET", null, headers, hop + 1)
                }
            }

            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            AppState.logRaw("HTTP $code $method ${URL(url).path}", text)
            return Response(code, text, url)
        } finally {
            conn.disconnect()
        }
    }
}
