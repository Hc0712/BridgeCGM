package tw.yourcompany.cgmbridge.feature.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.prefs.MultiSourceSettings

/**
 * Handles the primary-source staleness rule for the multi-source architecture.
 *
 * The selected primary source is special because it drives calibration, alarms, and the mini
 * graph. If it stops updating, the user should be told exactly once per stale episode while the
 * app continues to store and plot all other non-primary sources on the main graph.
 */
class PrimarySourceStalenessNotifier(
    private val context: Context,
    private val settings: MultiSourceSettings,
    private val repo: Repository
) {
    companion object {
        const val STALE_PRIMARY_MS: Long = 30L * 60L * 1000L
        private const val CHANNEL_ID = "primary_source_status"
        private const val NOTIFICATION_ID = 31001
    }

    /**
     * Checks whether the selected primary source is stale and sends a one-shot warning if needed.
     *
     * The method intentionally reads [MultiSourceSettings.primaryInputSourceId] instead of the
     * older output-name alias so the code matches the product specification and stays aligned with
     * the Settings screen.
     */
    suspend fun checkAndNotifyIfStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val primaryId = settings.primaryInputSourceId ?: return false
        val latest = repo.latestReadingForSource(primaryId) ?: return false
        val stale = nowMs - latest.timestampMs > STALE_PRIMARY_MS
        if (stale && !settings.primaryStaleNotificationActive) {
            postNotification(latest.timestampMs, nowMs)
            settings.primaryStaleNotificationActive = true
            settings.lastPrimaryStaleNotificationAtMs = nowMs
        } else if (!stale && settings.primaryStaleNotificationActive) {
            settings.primaryStaleNotificationActive = false
        }
        return stale
    }

    /**
     * Clears the stale episode when a fresh primary reading is accepted by the processor.
     *
     * Only the sticky episode flag is reset. The timestamp remains available for diagnostics.
     */
    fun markPrimaryFresh() {
        settings.primaryStaleNotificationActive = false
    }

    /** Creates and posts the user-facing stale-primary notification. */
    private fun postNotification(lastTimestampMs: Long, nowMs: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Primary Source Status", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val mins = ((nowMs - lastTimestampMs) / 60000L).coerceAtLeast(0L)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Primary source has not updated for ${mins} minutes. Other raw sources will continue plotting.")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}