package tw.yourcompany.cgmbridge.feature.keepalive

import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Helper object that starts the guardian service.
 */
object GuardianServiceLauncher {

    /**
     * Starts the foreground guardian service and records a trace line.
     */
    fun start(context: Context, reason: String) {
        if (!AppPrefs(context).disclaimerAccepted) return
        DebugTrace.t(DebugCategory.KEEPALIVE, "FGS-LAUNCH", "Start guardian: $reason")
        ContextCompat.startForegroundService(
            context,
            Intent(context, CgmGuardianForegroundService::class.java)
        )
    }
}
