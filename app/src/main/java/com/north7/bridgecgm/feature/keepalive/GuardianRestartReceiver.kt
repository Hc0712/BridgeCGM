package com.north7.bridgecgm.feature.keepalive

import com.north7.bridgecgm.core.prefs.AppPrefs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.north7.bridgecgm.core.platform.NotificationAccessChecker

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
