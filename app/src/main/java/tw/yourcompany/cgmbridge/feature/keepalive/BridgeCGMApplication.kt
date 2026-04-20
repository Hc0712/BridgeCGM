/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package tw.yourcompany.cgmbridge.feature.keepalive

import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import tw.yourcompany.cgmbridge.core.config.FeatureFlags
import tw.yourcompany.cgmbridge.feature.keepalive.NotificationPollScheduler
import tw.yourcompany.cgmbridge.feature.alarm.AlarmReplayScheduler
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.feature.keepalive.DatabaseMaintenanceWorker
import tw.yourcompany.cgmbridge.feature.keepalive.HealthCheckWorker
import java.util.concurrent.TimeUnit

/**
 * Application entry point — one of the 3 startup entry points for the keep-alive system.
 *
 * This class is instantiated by the Android framework when ANY component of the app
 * starts (Activity, Service, BroadcastReceiver, or ContentProvider). It schedules:
 *
 * 1. **HealthCheckWorker** (Layer 5) — WorkManager periodic task, every 15 min
 * 2. **DatabaseMaintenanceWorker** — Purges old data, daily
 * 3. **NotificationPollScheduler** (Layer 3) — AlarmManager heartbeat, every 5 min
 *
 * ## Keep-Alive Startup Entry Points:
 * ```
 * App launch   → BridgeCGMApplication.onCreate()    ← YOU ARE HERE
 * Device boot  → BootReceiver.onReceive()
 * Setup done   → SetupActivity.completeSetup()
 * ```
 * All three call `NotificationPollScheduler.schedule()` to ensure the heartbeat
 * alarm is always registered, regardless of how the app process was started.
 */
class BridgeCGMApplication : Application() {

    /**
     * Called by the Android framework when the process starts.
     *
     * ### Execution order:
     * 1. Log feature flags for diagnostics
     * 2. Enqueue WorkManager periodic tasks (KEEP policy = don't duplicate)
     * 3. Start the AlarmManager heartbeat (Layer 3)
     *
     * This runs before any Activity or Service `onCreate()`.
     */
    override fun onCreate() {
        super.onCreate()
        val prefs = AppPrefs(this)
        if (!prefs.disclaimerAccepted) {
            DebugTrace.t(DebugCategory.KEEPALIVE, "APP-INIT", "Disclaimer not accepted yet; skipping startup work")
            return
        }

        // Log current flag states so you can confirm in logcat at startup
        DebugTrace.t(DebugCategory.KEEPALIVE, "APP-INIT", FeatureFlags.summary())

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "health-check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES).build()
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "database-maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(1, TimeUnit.DAYS).build()
        )

        // Start the AlarmManager-based watchdog heartbeat.
        // This fires every 5 minutes (even in Doze) to verify the
        // NotificationListenerService is still alive.
        // Reference: xDrip+ Notifications.scheduleWakeup() + DoNothingService.setFailOverTimer()
        NotificationPollScheduler.schedule(this)

        // Rebuild any active glucose reminder replay alarms that were persisted
        // before the process was killed or the app was restarted.
        AlarmReplayScheduler.rescheduleFromPrefs(this)
    }
}
