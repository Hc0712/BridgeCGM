package tw.yourcompany.cgmbridge.feature.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Bridge glucose alert notification controller.
 *
 * Patch summary:
 * - the notification body now explains the quiet duration applied by Snooze;
 * - tapping the notification body no longer opens Reminder settings;
 * - a dedicated “Setting” action button now opens Reminder settings explicitly.
 *
 * Sound playback still belongs to [AlarmSoundPlayer]. The notification remains a visual control
 * surface only.
 */
object AlarmNotificationController {

    private const val CHANNEL_ID = "bridge_glucose_alerts"
    private const val CHANNEL_NAME = "Bridge glucose alerts"

    /**
     * Quiet duration applied when the user taps Snooze on the alarm notification.
     *
     * The user requested a human-readable quiet duration inside the notification body. Keeping the
     * duration in one constant guarantees that the actual snooze behavior and the displayed text do
     * not drift apart over time.
     */
    private const val SETTINGS_HANDOFF_SNOOZE_MS = 10L * 60_000L

    fun settingsHandoffSnoozeMs(): Long = SETTINGS_HANDOFF_SNOOZE_MS

    /**
     * Shows or updates the active glucose alert notification.
     *
     * This method returns early when Android currently blocks notifications for the app. That is
     * still important for diagnostics because the alarm sound may continue even when Android
     * suppresses the visual card.
     */
    fun showOrUpdateAlarmNotification(context: Context, rule: AlarmRule, latestMgdl: Double) {
        if (!canPostNotifications(context)) {
            DebugTrace.w(
                DebugCategory.ALARM,
                "ALARM-NOTIFICATION-BLOCKED",
                "kind=${rule.kind} blocked by notification permission or app notification setting"
            )
            return
        }

        ensureChannel(context)

        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            requestCode(rule.kind, 200),
            Intent(context, AlarmNotificationActionReceiver::class.java).apply {
                action = AlarmNotificationActionReceiver.ACTION_SNOOZE
                putExtra(AlarmNotificationActionReceiver.EXTRA_KIND, rule.kind.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(rule.kind, 250),
            Intent(context, AlarmNotificationActionReceiver::class.java).apply {
                action = AlarmNotificationActionReceiver.ACTION_OPEN_SETTINGS
                putExtra(AlarmNotificationActionReceiver.EXTRA_KIND, rule.kind.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getBroadcast(
            context,
            requestCode(rule.kind, 300),
            Intent(context, AlarmNotificationActionReceiver::class.java).apply {
                action = AlarmNotificationActionReceiver.ACTION_DISMISS_AND_SNOOZE
                putExtra(AlarmNotificationActionReceiver.EXTRA_KIND, rule.kind.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = bodyFor(rule.kind, latestMgdl)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titleFor(rule.kind))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.alarm_notification_action_snooze), snoozeIntent)
            .addAction(android.R.drawable.ic_menu_manage, context.getString(R.string.alarm_notification_action_setting), settingIntent)
            .setDeleteIntent(dismissIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)

        try {
            NotificationManagerCompat.from(context).notify(notificationId(rule.kind), builder.build())
            DebugTrace.t(
                DebugCategory.ALARM,
                "ALARM-NOTIFICATION-SHOWN",
                "kind=${rule.kind} latest=$latestMgdl"
            )
        } catch (se: SecurityException) {
            DebugTrace.w(
                DebugCategory.ALARM,
                "ALARM-NOTIFICATION-SECURITY",
                "kind=${rule.kind} notify failed",
                se
            )
        }
    }

    /** Cancels the notification for one specific alarm kind. */
    fun cancel(context: Context, kind: AlarmKind) {
        NotificationManagerCompat.from(context).cancel(notificationId(kind))
    }

    /** Cancels every glucose alarm notification owned by this helper. */
    fun cancelAll(context: Context) {
        AlarmKind.values().forEach { cancel(context, it) }
    }

    /**
     * Returns true only when both Android 13 runtime permission and app notification settings allow
     * posting notifications.
     */
    fun canPostNotifications(context: Context): Boolean {
        val runtimeAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeAllowed && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Returns a stable notification id per alarm kind so updates replace the previous card. */
    private fun notificationId(kind: AlarmKind): Int = when (kind) {
        AlarmKind.HIGH -> 42001
        AlarmKind.LOW -> 42002
        AlarmKind.URGENT_LOW -> 42003
    }

    /** Returns distinct request codes to avoid PendingIntent collisions between alarm kinds. */
    private fun requestCode(kind: AlarmKind, offset: Int): Int = notificationId(kind) + offset

    /** Human-readable title shown in the status bar and the expanded notification. */
    private fun titleFor(kind: AlarmKind): String = when (kind) {
        AlarmKind.HIGH -> "High glucose alert"
        AlarmKind.LOW -> "Low glucose alert"
        AlarmKind.URGENT_LOW -> "Urgent low glucose alert"
    }

    /**
     * Builds the notification body.
     *
     * The new wording intentionally avoids suggesting that the card itself opens Reminder settings,
     * because that tap action was removed by requirement 2-2. The body focuses only on the current
     * glucose value and the Snooze quiet duration.
     */
    private fun bodyFor(kind: AlarmKind, latestMgdl: Double): String {
        val rounded = latestMgdl.toInt()
        val quietMinutes = (SETTINGS_HANDOFF_SNOOZE_MS / 60_000L).toInt()
        return when (kind) {
            AlarmKind.HIGH,
            AlarmKind.LOW,
            AlarmKind.URGENT_LOW -> contextBody(rounded, quietMinutes)
        }
    }

    /** Small formatting helper used by [bodyFor] to keep all alarm kinds on the same wording. */
    private fun contextBody(latestMgdl: Int, quietMinutes: Int): String {
        return "Current glucose $latestMgdl mg/dL. Click the snooze button for $quietMinutes minutes quiet."
    }
}
