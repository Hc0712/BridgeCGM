/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package tw.yourcompany.cgmbridge.feature.keepalive

import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import tw.yourcompany.cgmbridge.feature.keepalive.NotificationPollScheduler
import tw.yourcompany.cgmbridge.feature.keepalive.GuardianServiceLauncher
import tw.yourcompany.cgmbridge.core.platform.NotificationAccessChecker
import tw.yourcompany.cgmbridge.feature.keepalive.DatabaseMaintenanceWorker
import tw.yourcompany.cgmbridge.feature.keepalive.HealthCheckWorker
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════
 * KEEP-ALIVE STARTUP ENTRY POINT — Device Boot / App Update
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Restores the entire keep-alive infrastructure after the device reboots or
 * the app is updated (which clears all pending AlarmManager alarms).
 *
 * ## Triggered by:
 * - `BOOT_COMPLETED` — device finished booting
 * - `MY_PACKAGE_REPLACED` — this app was updated (e.g., via Play Store or ADB)
 *
 * ## Actions:
 * 1. Re-enqueue WorkManager periodic tasks (HealthCheck + DB maintenance)
 * 2. Start the guardian foreground service (Layer 8) if notification access exists
 * 3. Start the AlarmManager heartbeat (Layer 3)
 *
 * ## Why this is critical:
 * AlarmManager alarms are NOT persistent across reboots. Without this receiver,
 * the heartbeat watchdog would never fire after a reboot, leaving the listener
 * unmonitored. WorkManager is persistent, but we re-enqueue with KEEP policy
 * as a safety measure.
 *
 * ## xDrip+ Reference:
 * xDrip+ uses `LibreReceiver` and other boot receivers to restart its data
 * collection pipeline on boot.
 *
 * @see BridgeCGMApplication  Startup entry point for normal app launch
 * @see SetupActivity          Startup entry point for first-time setup
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * Re-enqueues periodic work, starts the guardian service, and arms the heartbeat alarm.
     *
     * ### Thread: Main thread
     * BroadcastReceivers run on the main thread. All calls here are fast
     * (WorkManager enqueue, AlarmManager schedule, service start).
     *
     * @param context  Application context from the system
     * @param intent   Contains action: `BOOT_COMPLETED` or `MY_PACKAGE_REPLACED`
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppPrefs(context).disclaimerAccepted) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "health-check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES).build()
        )

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "database-maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(1, TimeUnit.DAYS).build()
        )

        if (NotificationAccessChecker.isNotificationAccessGranted(context)) {
            GuardianServiceLauncher.start(context, intent?.action ?: "boot")
        }

        // Start the AlarmManager heartbeat watchdog.
        NotificationPollScheduler.schedule(context)
    }
}
