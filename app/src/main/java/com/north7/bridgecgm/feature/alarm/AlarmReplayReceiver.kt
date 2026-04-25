package com.north7.bridgecgm.feature.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace
import com.north7.bridgecgm.core.prefs.AppPrefs

/**
 * Exact-alarm receiver that performs interval-based glucose reminder replays.
 *
 * The receiver does not play blindly. Instead, it asks [ReminderAlertEvaluator]
 * to re-check the latest glucose reading and persistent runtime state. This keeps
 * replay behavior correct even if the user disabled the alarm, changed a
 * threshold, or the glucose value recovered before the alarm fired.
 */
class AlarmReplayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppPrefs(context).disclaimerAccepted) return
        val kind = AlarmReplayScheduler.kindFromIntent(intent)
        DebugTrace.t(DebugCategory.ALARM, "ALARM-REPLAY-RECV", "kind=$kind")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CgmBridge:AlarmReplay").apply {
            acquire(30_000L)
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderAlertEvaluator.evaluateAndTrigger(context.applicationContext)
            } catch (t: Throwable) {
                DebugTrace.e(DebugCategory.ALARM, "ALARM-REPLAY-ERR", "Receiver failed for kind=$kind", t)
            } finally {
                try { if (wl.isHeld) wl.release() } catch (_: Exception) { }
                pendingResult.finish()
            }
        }
    }
}
