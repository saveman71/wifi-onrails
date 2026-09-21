package fr.onrails.trainwifi

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/**
 * Every 15 minutes while auto-connect is enabled: put the Wi-Fi watch back in place (it only lasts
 * one network, see [AutoConnect.rearm]) and, if a Wi-Fi is present and the service is not running,
 * check it. Cheap: one failed request at most when not on a train.
 */
class WatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "wifi-watch-job") {
            try {
                AutoConnect.rearm(applicationContext)
                AutoConnect.onWifiAvailable(applicationContext, null, "Periodic check")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
