package fr.onrails.trainwifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Process-wide state shared between the foreground service (writer) and the activity (reader).
 * Kept free of Android imports so the parsers and the demo data can be run on a plain JVM.
 */
object AppState {
    const val MAX_LOG_ENTRIES = 200
    const val MAX_RAW_CHARS = 800

    val state = MutableStateFlow(TrainState())

    /** Chronological, oldest first. */
    val log = MutableStateFlow<List<String>>(emptyList())

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun update(transform: (TrainState) -> TrainState) = state.update(transform)

    fun log(message: String) {
        val line = "${LocalTime.now().format(timeFormat)} $message"
        println("TrainWifi: $line") // System.out lands in logcat, useful with adb
        log.update { (it + line).takeLast(MAX_LOG_ENTRIES) }
    }

    /** Log a raw HTTP body, whitespace-collapsed and truncated. This is how parsers get fixed from inside a train. */
    fun logRaw(label: String, body: String?) {
        val text = body?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val shown = when {
            text.isEmpty() -> "<empty body>"
            text.length > MAX_RAW_CHARS -> text.take(MAX_RAW_CHARS) + " …(${text.length} chars)"
            else -> text
        }
        log("$label: $shown")
    }

    fun logText(): String = log.value.joinToString("\n")
}
