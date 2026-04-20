package tw.yourcompany.cgmbridge.feature.keepalive

import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.yourcompany.cgmbridge.feature.keepalive.GuardianServiceLauncher
import tw.yourcompany.cgmbridge.core.platform.NotificationAccessChecker

/**
 * Simple receiver reserved for future restart hooks.
 */
class GuardianRestartReceiver : BroadcastReceiver() {

    /**
     * Starts the guardian service when notification access is already granted.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppPrefs(context).disclaimerAccepted) return
        if (NotificationAccessChecker.isNotificationAccessGranted(context)) {
            GuardianServiceLauncher.start(context, "restart-receiver")
        }
    }
}
