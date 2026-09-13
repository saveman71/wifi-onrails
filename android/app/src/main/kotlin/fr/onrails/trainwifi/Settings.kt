package fr.onrails.trainwifi

import android.content.Context

/** Persisted user settings. Only the SSID list for now. */
class Settings(context: Context) {

    companion object {
        /**
         * Collected from public sources, not all verified on board. Editable in the app so the list
         * can be fixed on a train without a rebuild.
         */
        val DEFAULT_SSIDS = listOf(
            "_SNCF_WIFI_INOUI",
            "_SNCF_WIFI_INTERCITES",
            "WIFI_INTERCITES",
            "OUIFI",
            "_WIFI_LYRIA",
            "_WIFI_NORMANDIE",
        )
        private const val PREFS = "trainwifi"
        private const val KEY_SSIDS = "ssids" // newline separated, keeps order
        private const val KEY_ADVANCED = "advanced_expanded"
        private const val KEY_LOCK_SCREEN = "lock_screen"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ssids(): List<String> {
        val stored = prefs.getString(KEY_SSIDS, null) ?: return DEFAULT_SSIDS
        return parse(stored)
    }

    fun saveSsids(text: String): List<String> {
        val list = parse(text)
        prefs.edit().putString(KEY_SSIDS, list.joinToString("\n")).apply()
        return list
    }

    fun resetSsids(): List<String> {
        prefs.edit().remove(KEY_SSIDS).apply()
        return DEFAULT_SSIDS
    }

    /** Post the notification on a default-importance (still silent) channel so it stays on the lock screen. */
    fun keepOnLockScreen(): Boolean = prefs.getBoolean(KEY_LOCK_SCREEN, true)

    fun setKeepOnLockScreen(keep: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_SCREEN, keep).apply()
    }

    fun advancedExpanded(): Boolean = prefs.getBoolean(KEY_ADVANCED, false)

    fun setAdvancedExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_ADVANCED, expanded).apply()
    }

    private fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
