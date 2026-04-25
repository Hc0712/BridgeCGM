package com.north7.bridgecgm.feature.keepalive

import com.north7.bridgecgm.core.prefs.AppPrefs
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace

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
