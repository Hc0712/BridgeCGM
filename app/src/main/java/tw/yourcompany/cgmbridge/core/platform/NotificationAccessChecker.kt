package tw.yourcompany.cgmbridge.core.platform

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Checks whether the notification listener permission is granted.
 */
object NotificationAccessChecker {

    /**
     * Reads the secure settings value and checks whether this app's listener service is enabled.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(
            context,
            "tw.yourcompany.cgmbridge.feature.input.notification.CgmNotificationListenerService"
        )
        return enabled.contains(component.flattenToString())
    }
}
