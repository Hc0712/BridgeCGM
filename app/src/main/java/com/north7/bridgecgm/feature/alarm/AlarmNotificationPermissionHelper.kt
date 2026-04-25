package com.north7.bridgecgm.feature.alarm

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.north7.bridgecgm.R

/**
 * Shared helper for the app-notification permission flow used by glucose alarms.
 *
 * Why this helper exists:
 * - the original project showed the dialog from the Reminder setting screen;
 * - the new requirement moves that permission step into SetupActivity so the user can grant all
 *   required permissions from one place;
 * - keeping the dialog creation in one helper prevents future wording drift between screens.
 *
 * Scope of this helper:
 * - it only opens Android's official app-notification settings page;
 * - it does not request notification-listener access;
 * - it does not change alarm enable/disable state.
 */
object AlarmNotificationPermissionHelper {

    /**
     * Shows the exact reminder-notification dialog requested by the patch.
     *
     * The dialog is intentionally user-initiated. That keeps the Reminder setting screen focused on
     * alarm values and sound behavior, while SetupActivity becomes the single place for permission
     * onboarding.
     */
    fun showEnableNotificationDialog(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.alarm_notification_permission_title)
            .setMessage(R.string.alarm_notification_permission_message)
            .setPositiveButton(R.string.alarm_notification_permission_open_settings) { _, _ ->
                openAppNotificationSettings(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Opens Android's app-specific notification settings page for this application.
     *
     * Android has changed extras across API levels, so both the modern and legacy extra names are
     * included. Using the official settings screen is safer than deep-linking into vendor-specific
     * components and keeps the flow compatible across different devices.
     */
    fun openAppNotificationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
