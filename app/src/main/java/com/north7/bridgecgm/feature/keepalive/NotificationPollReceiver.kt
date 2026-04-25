package com.north7.bridgecgm.feature.keepalive

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.north7.bridgecgm.feature.alarm.ReminderAlertEvaluator
import com.north7.bridgecgm.core.prefs.AppPrefs
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace
import com.north7.bridgecgm.core.platform.NotificationAccessChecker
import com.north7.bridgecgm.feature.input.notification.CgmNotificationListenerService

/**
 * ═══════════════════════════════════════════════════════════════════════
 * KEEP-ALIVE LAYER 4 — WakeLock Trampoline + Liveness Watchdog
 * ═══════════════════════════════════════════════════════════════════════
 *
 * BroadcastReceiver fired every 5 minutes by [NotificationPollScheduler] (Layer 3).
 * This is the "muscle" of the keep-alive system — it performs the actual health
 * checks and recovery actions.
 *
 * ## Execution Flow (6 steps)
 * ```
 * AlarmManager fires → onReceive()
 *   1. Acquire PARTIAL_WAKE_LOCK (prevent CPU sleep)
 *   2. Check notification access permission
 *   3. Check liveness: is lastNotificationTimestampMs stale (>7 min)?
 *      └─ YES → requestRebind() to reconnect the listener
 *   4. Ensure guardian foreground service is alive
 *   5. Re-arm the next heartbeat alarm (self-sustaining loop)
 *   6. Release WakeLock
 * ```
 *
 * ## WakeLock Trampoline Pattern
 * A BroadcastReceiver's `onReceive()` runs on the main thread and has a strict
 * ~10-second time limit. After `onReceive()` returns, the system may immediately
 * put the CPU back to sleep. The WakeLock prevents this during our checks.
 * `goAsync()` extends the receiver's lifetime beyond the default 10s limit.
 *
 * ## xDrip+ Reference
 * - `WakeLockTrampoline.java` (line ~46) — acquires WakeLock in onReceive()
 * - `MissedReadingService.java` (line ~100) — checks for stale data
 * - `DoNothingService.java` (line ~180) — failover timer restarts services
 *
 * @see NotificationPollScheduler  Layer 3: schedules the alarm that fires this receiver
 * @see AppPrefs.lastNotificationTimestampMs  Liveness timestamp written by the listener
 */
class NotificationPollReceiver : BroadcastReceiver() {

    /**
     * Heartbeat handler — called every 5 minutes by the AlarmManager exact alarm.
     *
     * ### Thread: Main thread (UI thread)
     * BroadcastReceivers always run on the main thread. The work here is lightweight
     * (SharedPreferences reads + system service calls), so this is safe. Heavy work
     * like DB queries must NOT be done here.
     *
     * ### WakeLock lifecycle:
     * ```
     * acquire(30s) → checks → release in finally block
     * ```
     * The 30-second timeout is a safety net — if the finally block somehow fails,
     * the WakeLock auto-releases after 30s to avoid battery drain.
     *
     * @param context  Application context from the system
     * @param intent   The intent that triggered this receiver (from AlarmManager)
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppPrefs(context).disclaimerAccepted) return
        // ── 1. Acquire WakeLock (like xDrip+ WakeLockTrampoline line 46) ──
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CgmBridge:PollHeartbeat"
        ).apply { acquire(30_000L) }  // 30s max — enough for checks

        // Use goAsync() to extend BroadcastReceiver lifetime beyond default 10s
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPrefs(context)
                val now = System.currentTimeMillis()
                val lastNotif = prefs.lastNotificationTimestampMs
                val sinceLastMs = if (lastNotif > 0) now - lastNotif else -1L
                val sinceLastMinStr = if (sinceLastMs > 0) {
                    "${sinceLastMs / 60000}m${(sinceLastMs % 60000) / 1000}s"
                } else {
                    "never"
                }

            DebugTrace.t(DebugCategory.KEEPALIVE, "POLL-BEAT", "Heartbeat fired. lastNotif=${sinceLastMinStr} ago")

            // ── 2. Check notification access permission ──
            val hasAccess = NotificationAccessChecker.isNotificationAccessGranted(context)
            if (!hasAccess) {
                DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-NO-ACCESS", "Notification access revoked! Cannot recover programmatically.")
                // Still re-arm — the user might re-enable it later
            }

            // ── 3. Check liveness — is the listener still receiving? ──
            if (hasAccess && sinceLastMs > NotificationPollScheduler.STALE_THRESHOLD_MS) {
                DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-STALE", "No CGM notification for ${sinceLastMinStr}! Requesting rebind.")
                // Request the system to rebind the NotificationListenerService.
                // Same as what our onListenerDisconnected does, but triggered externally.
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, CgmNotificationListenerService::class.java)
                    )
                    DebugTrace.t(DebugCategory.KEEPALIVE, "POLL-REBIND", "requestRebind() called successfully")
                } catch (e: Exception) {
                    DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-REBIND", "requestRebind() failed: ${e.message}")
                }
            }

            // ── 4. Ensure guardian foreground service is alive ──
            if (hasAccess) {
                    try {
                        GuardianServiceLauncher.start(context, "poll-heartbeat")
                    } catch (e: Exception) {
                        DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-FGS", "Failed to start guardian: ${e.message}")
                    }
                }

                try {
                    ReminderAlertEvaluator.evaluateAndTrigger(context)
                } catch (e: Exception) {
                    DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-ALARM", "Reminder evaluation failed: ${e.message}")
                }

            // ── 5. Re-arm the next heartbeat ──
            NotificationPollScheduler.schedule(context)
            } catch (e: Exception) {
                DebugTrace.e(DebugCategory.KEEPALIVE, "POLL-ERR", "Heartbeat error: ${e.message}", e)
            } finally {
            // ── 6. Release WakeLock ──
            try {
                    if (wl.isHeld) wl.release()
                } catch (_: Exception) {
                }
                pendingResult.finish()
            }
        }
    }
}
