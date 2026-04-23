package tw.yourcompany.cgmbridge.feature.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

/**
 * Handles action buttons from the Bridge glucose alert notification.
 *
 * This patched receiver now separates the two user intents clearly:
 * - Snooze: stop sound immediately and keep the app quiet for a fixed handoff window.
 * - Setting: open the Reminder setting screen without forcing a snooze.
 *
 * That behavior matches the task requirement to remove the old “snooze and open settings”
 * coupling so the notification can offer both actions independently. Android 12+ can block
 * notification trampolines, so the main notification path now launches the Activity directly and
 * this receiver keeps only a documented fallback implementation.
 */
class AlarmNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val kind = intent.getStringExtra(EXTRA_KIND)?.let {
            try {
                AlarmKind.valueOf(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        when (action) {
            ACTION_SNOOZE -> {
                val safeKind = kind ?: return
                AlarmSoundPlayer.stop()
                armQuietHandoffWindow(context, safeKind)
                AlarmNotificationController.cancel(context, safeKind)
                DebugTrace.t(DebugCategory.ALARM, "ALARM-NOTIFICATION-SNOOZE", "kind=$safeKind")
            }

            ACTION_DISMISS_AND_SNOOZE -> {
                val safeKind = kind ?: return
                AlarmSoundPlayer.stop()
                armQuietHandoffWindow(context, safeKind)
                AlarmNotificationController.cancel(context, safeKind)
                DebugTrace.t(DebugCategory.ALARM, "ALARM-NOTIFICATION-DISMISS", "kind=$safeKind")
            }

            ACTION_OPEN_SETTINGS -> {
                openReminderSettings(context, kind)
                DebugTrace.t(DebugCategory.ALARM, "ALARM-NOTIFICATION-SETTING", "opened reminder settings")
            }
        }
    }

    /**
     * Applies a temporary quiet window after Snooze or notification dismissal.
     *
     * The replay scheduler is re-armed for the end of that window so the current glucose alert does
     * not immediately restart after the user interacts with the notification.
     */
    private fun armQuietHandoffWindow(context: Context, kind: AlarmKind) {
        val prefs = AppPrefs(context)
        val rule = when (kind) {
            AlarmKind.HIGH -> AlarmConfig.high(prefs)
            AlarmKind.LOW -> AlarmConfig.low(prefs)
            AlarmKind.URGENT_LOW -> AlarmConfig.urgentLow(prefs)
        }
        val now = System.currentTimeMillis()
        val nextAt = now + AlarmNotificationController.settingsHandoffSnoozeMs()
        AlarmConfig.persistActive(prefs, rule, true)
        AlarmConfig.persistLastTriggeredAt(prefs, rule, now)
        AlarmConfig.persistNextTriggerAt(prefs, rule, nextAt)
        AlarmReplayScheduler.schedule(context, kind, nextAt)
    }

    /**
     * Opens the Reminder setting screen from the notification receiver fallback path.
     *
     * The primary fix launches Reminder settings directly from the notification via
     * PendingIntent.getActivity(). This receiver path is kept only as a compatibility fallback for
     * any older PendingIntent or internal caller that still routes through ACTION_OPEN_SETTINGS.
     * Reusing the shared intent factory keeps both code paths aligned.
     */
    private fun openReminderSettings(context: Context, kind: AlarmKind?) {
        context.startActivity(AlarmSettingsLaunchIntentFactory.create(context, kind))
    }

    companion object {
        const val EXTRA_KIND = "alarm_kind"
        const val ACTION_SNOOZE = "tw.yourcompany.cgmbridge.action.ALARM_SNOOZE"
        const val ACTION_DISMISS_AND_SNOOZE = "tw.yourcompany.cgmbridge.action.ALARM_DISMISS_AND_SNOOZE"
        const val ACTION_OPEN_SETTINGS = "tw.yourcompany.cgmbridge.action.ALARM_OPEN_SETTINGS"
    }
}
