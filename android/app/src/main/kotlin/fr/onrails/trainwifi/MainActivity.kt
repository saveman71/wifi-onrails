package fr.onrails.trainwifi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
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

    private lateinit var phaseChip: TextView
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroEta: TextView
    private lateinit var heroSub: TextView
    private lateinit var progressRow: View
    private lateinit var tripProgress: ProgressBar
    private lateinit var tripPercent: TextView
    private lateinit var statsRow: View
    private lateinit var statSpeed: TextView
    private lateinit var statTraveled: TextView
    private lateinit var statRemaining: TextView
    private lateinit var routeCard: View
    private lateinit var timeline: TimelineView
    private lateinit var connectionCard: View
    private lateinit var wifiQuality: TextView
    private lateinit var connectionLine1: TextView
    private lateinit var dataProgress: ProgressBar
    private lateinit var connectionLine2: TextView
    private lateinit var statusMessage: TextView
    private lateinit var advancedToggle: TextView
    private lateinit var advancedSection: View
    private lateinit var ssidEdit: EditText
    private lateinit var logView: TextView

    private var uiScope: CoroutineScope? = null
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        bindViews()

        ssidEdit.setText(settings.ssids().joinToString("\n"))
        setAdvancedVisible(settings.advancedExpanded())

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
        val lockSwitch = findViewById<Switch>(R.id.switch_lockscreen)
        lockSwitch.isChecked = settings.keepOnLockScreen()
        lockSwitch.setOnCheckedChangeListener { _, checked ->
            settings.setKeepOnLockScreen(checked)
            AppState.log(if (checked) "Notification kept on lock screen" else "Notification set to silent channel")
            TrainWifiService.refresh(this)
        }
        advancedToggle.setOnClickListener {
            val show = advancedSection.visibility != View.VISIBLE
            setAdvancedVisible(show)
            settings.setAdvancedExpanded(show)
        }
    }

    private fun bindViews() {
        phaseChip = findViewById(R.id.phase_chip)
        heroKicker = findViewById(R.id.hero_kicker)
        heroTitle = findViewById(R.id.hero_title)
        heroEta = findViewById(R.id.hero_eta)
        heroSub = findViewById(R.id.hero_sub)
        progressRow = findViewById(R.id.progress_row)
        tripProgress = findViewById(R.id.trip_progress)
        tripPercent = findViewById(R.id.trip_percent)
        statsRow = findViewById(R.id.stats_row)
        statSpeed = findViewById(R.id.stat_speed)
        statTraveled = findViewById(R.id.stat_traveled)
        statRemaining = findViewById(R.id.stat_remaining)
        routeCard = findViewById(R.id.route_card)
        timeline = findViewById(R.id.timeline)
        connectionCard = findViewById(R.id.connection_card)
        wifiQuality = findViewById(R.id.wifi_quality)
        connectionLine1 = findViewById(R.id.connection_line1)
        dataProgress = findViewById(R.id.data_progress)
        connectionLine2 = findViewById(R.id.connection_line2)
        statusMessage = findViewById(R.id.status_message)
        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedSection = findViewById(R.id.advanced_section)
        ssidEdit = findViewById(R.id.ssid_edit)
        logView = findViewById(R.id.log_view)
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

    private fun setAdvancedVisible(visible: Boolean) {
        advancedSection.visibility = if (visible) View.VISIBLE else View.GONE
        advancedToggle.text = (if (visible) "▾ " else "▸ ") + getString(R.string.label_advanced)
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

    // ---- Rendering -------------------------------------------------------------------------

    private fun render(state: TrainState) {
        phaseChip.text = chipLabel(state.phase)
        renderHero(state)
        renderStats(state)
        renderConnection(state)
        statusMessage.text = when {
            state.phase == Phase.STOPPED -> getString(R.string.hint_idle)
            state.updatedAtMillis > 0 -> {
                val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(state.updatedAtMillis), Parsers.PARIS)
                "Updated ${Formatting.timeWithSeconds(t)}" + (state.portal?.let { ", ${it.host}" } ?: "")
            }
            else -> state.message
        }
    }

    private fun chipLabel(phase: Phase): String = when (phase) {
        Phase.STOPPED -> "Off"
        Phase.WAITING_WIFI -> "Waiting"
        Phase.PROBING -> "Probing"
        Phase.NO_PORTAL -> "No portal"
        Phase.VPN_BLOCKED -> "VPN"
        Phase.ACTIVATING -> "Activating"
        Phase.ACTIVE -> "On board"
        Phase.DEMO -> "Demo"
    }

    private fun renderHero(state: TrainState) {
        val trip = state.trip
        val destination = trip?.destination
        if (trip == null || destination == null) {
            heroKicker.text = "Status"
            heroTitle.text = state.phase.title
            heroEta.visibility = View.GONE
            heroSub.text = state.message
            heroSub.visibility = if (state.message.isBlank()) View.GONE else View.VISIBLE
            progressRow.visibility = View.GONE
            return
        }

        val next = trip.nextStop
        heroKicker.text = if (next != null && next != destination) "Next stop ${next.name}, ${Formatting.time(next.eta)}" else "Arrival"
        heroTitle.text = destination.name
        heroEta.text = Formatting.time(destination.eta)
        heroEta.visibility = View.VISIBLE

        val parts = mutableListOf<String>()
        Formatting.minutesUntil(destination.eta)?.let { parts += "Arrival in $it min" }
        val delay = destination.delayMinutes
        parts += when {
            delay == null -> ""
            delay > 0 -> "+$delay min, planned ${Formatting.time(destination.theoric)}"
            else -> "on time"
        }
        heroSub.text = parts.filter { it.isNotEmpty() }.joinToString(", ")
        heroSub.visibility = if (heroSub.text.isBlank()) View.GONE else View.VISIBLE

        val percent = trip.progressPercent
        if (percent != null) {
            tripProgress.progress = percent
            tripPercent.text = "$percent%"
            progressRow.visibility = View.VISIBLE
        } else {
            progressRow.visibility = View.GONE
        }
    }

    private fun renderStats(state: TrainState) {
        val trip = state.trip
        if (trip == null) {
            statsRow.visibility = View.GONE
            routeCard.visibility = View.GONE
            return
        }
        statSpeed.text = Formatting.speed(state.gps) ?: "–"
        statTraveled.text = trip.traveledMetres?.let { Formatting.km(it) } ?: "–"
        statRemaining.text = trip.remainingMetres?.let { Formatting.km(it) } ?: "–"
        statsRow.visibility = View.VISIBLE

        timeline.setTrip(trip)
        routeCard.visibility = if (trip.stops.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderConnection(state: TrainState) {
        val connection = state.connection
        val stats = state.statistics
        if (connection == null && stats == null) {
            connectionCard.visibility = View.GONE
            return
        }
        connectionCard.visibility = View.VISIBLE
        wifiQuality.text = qualityDots(stats?.quality ?: -1)

        if (connection != null) {
            connectionLine1.text = if (connection.active) {
                "Internet granted, ${Formatting.mb(connection.remainingMb)} left of ${Formatting.mb(connection.totalMb)}"
            } else {
                "Grant inactive: ${connection.description}"
            }
            val total = connection.totalMb
            dataProgress.progress = if (total > 0) (connection.consumedMb / total * 100).toInt().coerceIn(0, 100) else 0
            dataProgress.visibility = View.VISIBLE
        } else {
            connectionLine1.text = ""
            dataProgress.visibility = View.GONE
        }

        val line2 = mutableListOf<String>()
        connection?.let { line2 += "${String.format(java.util.Locale.ROOT, "%.1f", it.bandwidthMbPerSec)} MB/s" }
        stats?.let { if (it.devices >= 0) line2 += "${it.devices} devices" }
        state.barQueueEmpty?.let { line2 += if (it) "bar queue empty" else "bar queue busy" }
        connectionLine2.text = line2.joinToString(" · ")
    }

    /** Five dots, the first `quality` in brand colour. */
    private fun qualityDots(quality: Int): CharSequence {
        if (quality < 0) return ""
        val brand = getColor(R.color.brand_red)
        val track = getColor(R.color.track)
        val builder = SpannableStringBuilder()
        for (i in 1..5) {
            val start = builder.length
            builder.append("●")
            builder.setSpan(ForegroundColorSpan(if (i <= quality) brand else track), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }
}
