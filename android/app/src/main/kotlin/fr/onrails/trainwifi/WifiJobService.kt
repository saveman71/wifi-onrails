package fr.onrails.trainwifi

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/**
 * Waits on a Wi-Fi network instead of on the clock. Scheduled with a `NetworkRequest` that asks
 * only for TRANSPORT_WIFI, so unlike `setRequiredNetworkType` it does not want NET_CAPABILITY_
 * VALIDATED, which a captive portal never has until the app has activated it.
 *
 * While the phone is off Wi-Fi this costs nothing and runs the moment a network shows up, which is
 * the case that matters: getting on a train. On a Wi-Fi that is not a train the constraint stays
 * met, so [AutoConnect.scheduleWifiJob] puts the next run some minutes out.
 */
class WifiJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "wifi-job") {
            try {
                val started = AutoConnect.onWifiAvailable(applicationContext, null, "Wi-Fi job")
                // The service re-schedules this on its way out, so only do it when it did not start.
                if (!started) AutoConnect.scheduleWifiJob(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
