package com.north7.bridgecgm.feature.keepalive

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.north7.bridgecgm.core.data.Repository
import com.north7.bridgecgm.feature.input.notification.CgmNotificationListenerService
import com.north7.bridgecgm.core.prefs.AppPrefs
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace
import com.north7.bridgecgm.core.platform.NotificationAccessChecker

/**
 * ═══════════════════════════════════════════════════════════════════════
 * KEEP-ALIVE LAYER 5 — Secondary Watchdog (WorkManager)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Periodic worker that checks permission state, refreshes the guardian service,
 * verifies the NotificationListenerService is still receiving, and re-arms the
 * AlarmManager heartbeat.
 *
 * ## Why Layer 5 exists alongside Layer 3/4:
 * - **Layer 3/4 (AlarmManager)**: Primary watchdog, fires every 5 min even in Doze.
 *   But AlarmManager alarms can be lost (reboot, app update, OS bugs).
 * - **Layer 5 (WorkManager)**: Secondary safety net, fires every ~15 min.
 *   WorkManager is more resilient (persists across reboots) but less timely
 *   (OS can defer by hours). Its main job is to RE-ARM the AlarmManager
 *   heartbeat in case it was lost.
 *
 * ## Staleness threshold: 15 minutes
 * Layer 4 uses 7 min (catches issues quickly). Layer 5 uses 15 min (avoids
 * redundant recovery attempts if Layer 4 already handled it).
 *
 * ## xDrip+ Reference:
 * - `MissedReadingService.java` — checks for stale data and triggers recovery
 *   (periodically scheduled via AlarmManager in xDrip+, not WorkManager)
 *
 * @see NotificationPollScheduler  Layer 3: AlarmManager heartbeat that this re-arms
 * @see NotificationPollReceiver   Layer 4: Primary staleness checker (7 min threshold)
 */
class HealthCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * Executes the health check. Called by WorkManager approximately every 15 minutes.
     *
     * ### Steps:
     * 1. Read notification access permission state
     * 2. Read liveness timestamp from SharedPreferences
     * 3. Log health status to the event log (visible in debug UI)
     * 4. If listener is stale (>15 min without a CGM notification):
     *    - Log warning
     *    - Call `requestRebind()` to reconnect the listener
     * 5. Re-arm the AlarmManager heartbeat (in case it was lost)
     *
     * ### Thread: WorkManager background thread (Dispatchers.Default)
     * CoroutineWorker runs on a background thread, so DB writes are safe here.
     *
     * @return [Result.success] always — we don't want WorkManager to retry or backoff
     */
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val prefs = AppPrefs(applicationContext)
        if (!prefs.disclaimerAccepted) return Result.success()
        val access = NotificationAccessChecker.isNotificationAccessGranted(applicationContext)
        val lastNotif = prefs.lastNotificationTimestampMs
        val sinceMs = if (lastNotif > 0) System.currentTimeMillis() - lastNotif else -1L
        val sinceStr = if (sinceMs > 0) "${sinceMs / 60000}m" else "never"

        repo.log(
            "D", "HealthCheck",
            "notification_access=$access lastNotif=${sinceStr} ago"
        )

        if (access) {
            GuardianServiceLauncher.start(applicationContext, "health-check")

            // If no notification in >15 minutes, request rebind as recovery
            if (sinceMs > 15L * 60 * 1000) {
                DebugTrace.e(
                    DebugCategory.KEEPALIVE,
                    "HC-STALE",
                    "No CGM notification for $sinceStr! Requesting rebind."
                )
                repo.log("W", "HealthCheck", "Listener stale ($sinceStr). Rebinding.")
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(
                            applicationContext,
                            CgmNotificationListenerService::class.java
                        )
                    )
                } catch (e: Exception) {
                    DebugTrace.e(
                        DebugCategory.KEEPALIVE,
                        "HC-REBIND",
                        "requestRebind failed: ${e.message}",
                        e
                    )
                }
            }
        }

        // Re-arm the AlarmManager heartbeat (in case it was lost)
        NotificationPollScheduler.schedule(applicationContext)

        return Result.success()
    }
}
