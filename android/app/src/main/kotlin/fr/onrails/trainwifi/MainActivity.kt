package fr.onrails.trainwifi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 42
    }

    private lateinit var settings: Settings
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var ssidEdit: EditText
    private lateinit var logView: TextView

    private var uiScope: CoroutineScope? = null
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        statusTitle = findViewById(R.id.status_title)
        statusBody = findViewById(R.id.status_body)
        ssidEdit = findViewById(R.id.ssid_edit)
        logView = findViewById(R.id.log_view)
        ssidEdit.setText(settings.ssids().joinToString("\n"))

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            withNotificationPermission {
                registerSuggestions(settings.ssids())
                TrainWifiService.start(this)
            }
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { TrainWifiService.stop(this) }
        findViewById<Button>(R.id.btn_demo).setOnClickListener {
            withNotificationPermission { TrainWifiService.demo(this) }
        }
        findViewById<Button>(R.id.btn_save_ssids).setOnClickListener {
            val list = settings.saveSsids(ssidEdit.text.toString())
            ssidEdit.setText(list.joinToString("\n"))
            AppState.log("Saved ${list.size} SSIDs")
            registerSuggestions(list)
        }
        findViewById<Button>(R.id.btn_reset_ssids).setOnClickListener {
            val list = settings.resetSsids()
            ssidEdit.setText(list.joinToString("\n"))
            AppState.log("SSID list reset to defaults")
            registerSuggestions(list)
        }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Train Wi-Fi log", AppState.logText()))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        uiScope = scope
        scope.launch { AppState.state.collect { render(it) } }
        scope.launch { AppState.log.collect { logView.text = it.asReversed().joinToString("\n") } }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    private fun withNotificationPermission(action: () -> Unit) {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!needed) {
            action()
            return
        }
        afterPermission = action
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        AppState.log(if (granted) "Notification permission granted" else "Notification permission denied, the service still runs but stays invisible")
        // Proceed either way: the service works without the notification being visible.
        afterPermission?.invoke()
        afterPermission = null
    }

    private fun registerSuggestions(ssids: List<String>) {
        val result = WifiSuggestions.apply(this, ssids)
        AppState.log(result.message)
        if (!result.ok) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
    }

    private fun render(state: TrainState) {
        statusTitle.text = state.phase.title
        val lines = mutableListOf<String>()
        if (state.message.isNotBlank()) lines += state.message
        state.portal?.let { lines += "Portal: ${it.host}" }
        state.connection?.let {
            lines += "Grant: ${if (it.active) "active" else "inactive"} (${it.description})"
            lines += Formatting.quota(it)
        }
        state.trip?.let { trip ->
            trip.destination?.let { lines += "Destination: ${Formatting.stopLine(it)}" }
            trip.nextStop?.let { lines += "Next stop: ${Formatting.stopLine(it)}" }
            trip.progressPercent?.let { lines += "Progress: $it%" }
            lines += "Stops: ${trip.stops.size}, delayed: ${trip.stops.count { it.isDelayed }}"
        }
        state.gps?.let { gps ->
            val speed = Formatting.speed(gps) ?: "no fix"
            val alt = gps.altitudeM?.let { ", ${it.toInt()} m" } ?: ""
            lines += "GPS: $speed$alt"
        }
        Formatting.wifi(state.statistics, state.barQueueEmpty)?.let { lines += it }
        if (state.updatedAtMillis > 0) {
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(state.updatedAtMillis), Parsers.PARIS)
            lines += "Updated ${Formatting.timeWithSeconds(t)}"
        }
        statusBody.text = if (lines.isEmpty()) "Press \"Enable auto-connect\" to start." else lines.joinToString("\n")
    }
}
