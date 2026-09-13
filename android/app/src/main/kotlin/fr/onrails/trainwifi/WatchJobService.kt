package fr.onrails.trainwifi

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/**
 * Safety net, every 15 minutes while auto-connect is enabled: if a Wi-Fi network is present and
 * the service is not running, probe it. Catches the case where the portal was unreachable at the
 * moment the Wi-Fi connected. Cheap: one failed request at most when not on a train.
 */
class WatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "wifi-watch-job") {
            try {
                AutoConnect.onWifiAvailable(applicationContext, null, "Periodic check")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
