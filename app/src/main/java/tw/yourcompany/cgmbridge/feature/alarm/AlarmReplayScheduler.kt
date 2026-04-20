package tw.yourcompany.cgmbridge.feature.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

/**
 * Schedules exact replay alarms for glucose reminders.
 *
 * The keep-alive heartbeat runs every 5 minutes, which is too coarse for user
 * settings such as a 2-minute reminder interval. This scheduler solves that gap
 * by creating a dedicated exact alarm per glucose alert kind.
 */
object AlarmReplayScheduler {

    private const val EXTRA_KIND = "alarm_kind"

    /** Arms or re-arms the exact replay alarm for one alarm kind. */
    fun schedule(context: Context, kind: AlarmKind, whenMs: Long) {
        if (whenMs <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pendingIntent(context, kind)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, intent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, whenMs, intent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, intent)
            }
            DebugTrace.t(DebugCategory.ALARM, "ALARM-REPLAY-SCHED", "kind=$kind whenMs=$whenMs")
        } catch (e: SecurityException) {
            DebugTrace.e(DebugCategory.ALARM, "ALARM-REPLAY-SCHED", "Failed to schedule $kind", e)
            try { alarmManager.set(AlarmManager.RTC_WAKEUP, whenMs, intent) } catch (_: Exception) { }
        }
    }

    /** Cancels the replay alarm for one alarm kind. */
    fun cancel(context: Context, kind: AlarmKind) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { alarmManager.cancel(pendingIntent(context, kind)) } catch (_: Exception) { }
        DebugTrace.t(DebugCategory.ALARM, "ALARM-REPLAY-CANCEL", "kind=$kind")
    }

    /** Rebuilds replay alarms from persisted state after reboot/app restart. */
    fun rescheduleFromPrefs(context: Context) {
        val prefs = AppPrefs(context)
        val rules = AlarmConfig.all(prefs)
        val now = System.currentTimeMillis()
        for (rule in rules) {
            if (!rule.enabled || !rule.active) {
                cancel(context, rule.kind)
                continue
            }
            val target = maxOf(now, rule.nextTriggerAtMs.takeIf { it > 0L } ?: (rule.lastTriggeredAtMs + rule.intervalMs()))
            AlarmConfig.persistNextTriggerAt(prefs, rule, target)
            schedule(context, rule.kind, target)
        }
    }

    private fun pendingIntent(context: Context, kind: AlarmKind): PendingIntent {
        val intent = Intent(context, AlarmReplayReceiver::class.java).putExtra(EXTRA_KIND, kind.name)
        return PendingIntent.getBroadcast(
            context,
            requestCode(kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(kind: AlarmKind): Int = when (kind) {
        AlarmKind.HIGH -> 9301
        AlarmKind.LOW -> 9302
        AlarmKind.URGENT_LOW -> 9303
    }

    /** Decodes the alarm kind from the replay receiver intent. */
    fun kindFromIntent(intent: Intent?): AlarmKind? = intent?.getStringExtra(EXTRA_KIND)?.let {
        try { AlarmKind.valueOf(it) } catch (_: IllegalArgumentException) { null }
    }
}
