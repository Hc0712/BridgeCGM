package tw.yourcompany.cgmbridge.feature.keepalive

import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Schedules a recurring exact alarm that acts as a heartbeat / watchdog.
 *
 * Modeled after xDrip+ `JoH.wakeUpIntent()` + `Notifications.scheduleWakeup()`:
 * - Uses `setExactAndAllowWhileIdle()` to fire even during Doze.
 * - On Samsung devices uses `setAlarmClock()` as a workaround for Samsung-specific
 *   Doze deferrals (same as xDrip+ `buggy_samsung` path).
 * - Fires [NotificationPollReceiver] which acts as a WakeLock trampoline.
 *
 * Unlike WorkManager (min 15 min, can be deferred), AlarmManager exact alarms
 * guarantee ~5-minute heartbeats even in deep Doze.
 */
object NotificationPollScheduler {

    /** Heartbeat interval — same order as xDrip+ scheduleWakeup max (6 min). */
    const val POLL_INTERVAL_MS = 5L * 60 * 1000  // 5 minutes

    /**
     * If no CGM notification has been received within this window, the poll receiver
     * will flag a staleness warning and attempt recovery.
     * Set slightly above the longest CGM reporting interval (Dexcom = 5 min).
     */
    const val STALE_THRESHOLD_MS = 7L * 60 * 1000  // 7 minutes

    private const val REQUEST_CODE = 9901

    /**
     * Schedules the next heartbeat alarm.
     *
     * Safe to call repeatedly — the pending intent is updated (FLAG_UPDATE_CURRENT).
     */
    fun schedule(context: Context) {
        if (!AppPrefs(context).disclaimerAccepted) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = getPendingIntent(context)
        val wakeTime = System.currentTimeMillis() + POLL_INTERVAL_MS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: check canScheduleExactAlarms first
                if (alarmManager.canScheduleExactAlarms()) {
                    scheduleExact(alarmManager, wakeTime, intent)
                } else {
                    // Fallback: inexact alarm — better than nothing
                    DebugTrace.w(DebugCategory.KEEPALIVE, "POLL-SCHED", "Cannot schedule exact alarm; using inexact fallback")
                    alarmManager.set(AlarmManager.RTC_WAKEUP, wakeTime, intent)
                }
            } else {
                scheduleExact(alarmManager, wakeTime, intent)
            }
            DebugTrace.t(DebugCategory.KEEPALIVE, "POLL-SCHED", "Heartbeat scheduled in ${POLL_INTERVAL_MS / 1000}s")
        } catch (e: SecurityException) {
            DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-SCHED", "SecurityException scheduling alarm: ${e.message}")
            // Absolute fallback
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, wakeTime, intent)
            } catch (_: Exception) { /* give up */ }
        }
    }

    /** Cancels any outstanding heartbeat alarm. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { alarmManager.cancel(getPendingIntent(context)) } catch (_: Exception) {}
    }

    /**
     * Schedules the exact alarm using the appropriate API for the device.
     *
     * ### Samsung Workaround
     * Samsung's custom Doze implementation aggressively batches and defers
     * `setExactAndAllowWhileIdle()` calls, sometimes by 10+ minutes. xDrip+
     * discovered that `setAlarmClock()` bypasses this because the system treats
     * it as a user-visible alarm (e.g., a morning alarm clock) and never defers it.
     *
     * The trade-off: `setAlarmClock()` may show an alarm icon in the status bar
     * on some Samsung devices. This is acceptable for a medical data bridge.
     *
     * Reference: xDrip+ `JoH.wakeUpIntent()` — `buggy_samsung` code path
     *
     * @param alarmManager  System AlarmManager service
     * @param wakeTime      Epoch millis when the alarm should fire
     * @param intent        PendingIntent targeting [NotificationPollReceiver]
     */
    private fun scheduleExact(alarmManager: AlarmManager, wakeTime: Long, intent: PendingIntent) {
        if (isSamsungDevice()) {
            // AlarmClockInfo has the highest priority — the system will wake the device
            // and deliver the alarm even during aggressive Doze on Samsung.
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(wakeTime, null),
                intent
            )
        } else {
            // Standard exact alarm — works reliably on Pixel, OnePlus, Xiaomi, etc.
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, intent)
        }
    }

    /**
     * Detects Samsung devices by manufacturer string.
     * Samsung requires the `setAlarmClock()` workaround — see [scheduleExact].
     */
    private fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /**
     * Creates the [PendingIntent] that fires [NotificationPollReceiver].
     *
     * Uses [PendingIntent.FLAG_UPDATE_CURRENT] so that calling [schedule] repeatedly
     * simply updates the existing alarm rather than creating duplicates.
     * [PendingIntent.FLAG_IMMUTABLE] is required on API 31+.
     */
    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationPollReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
