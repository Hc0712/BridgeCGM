package tw.yourcompany.cgmbridge.feature.alarm

import android.content.Context
import android.content.Intent

/**
 * Builds the Intent used to open [AlarmSettingsActivity] from the glucose alert notification.
 *
 * Why this helper exists:
 * - Android 12+ blocks notification trampolines, which means a notification action should
 *   launch the target Activity directly instead of first waking a BroadcastReceiver that then
 *   calls startActivity().
 * - The same launch flags are needed from both the direct notification PendingIntent path and
 *   the legacy receiver fallback path, so keeping them in one helper avoids future drift.
 * - The flags are intentionally conservative and Activity-only. The implementation does not use
 *   any overlay window type or special permission, which keeps behavior aligned with Android's
 *   supported task and back-stack model.
 */
object AlarmSettingsLaunchIntentFactory {

    /**
     * Extra flag used by [AlarmSettingsActivity] to refresh its window presentation when the
     * existing singleTask instance is brought back to the foreground by a notification tap.
     */
    const val EXTRA_FROM_NOTIFICATION_ACTION = "from_notification_action"

    /**
     * Extra that preserves which alarm kind opened Reminder settings.
     *
     * Today the screen does not branch on this value, but storing it keeps the launch contract
     * explicit and leaves room for future UX improvements such as scrolling to the matching
     * section automatically.
     */
    const val EXTRA_ALARM_KIND = "alarm_kind"

    /**
     * Returns a fully configured Intent that is safe to use from a notification action.
     *
     * Flag rationale:
     * - NEW_TASK is required because notification actions are launched outside an existing
     *   foreground Activity context.
     * - CLEAR_TOP asks Android to reuse the existing task if Reminder settings is already in
     *   that task's history.
     * - SINGLE_TOP works together with AlarmSettingsActivity.launchMode=singleTask so repeated
     *   taps route to onNewIntent() instead of creating duplicates.
     */
    fun create(context: Context, kind: AlarmKind? = null): Intent {
        return Intent(context, AlarmSettingsActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_FROM_NOTIFICATION_ACTION, true)
            kind?.let { putExtra(EXTRA_ALARM_KIND, it.name) }
        }
    }
}
