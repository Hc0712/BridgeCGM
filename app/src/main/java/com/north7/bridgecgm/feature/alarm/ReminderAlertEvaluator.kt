package com.north7.bridgecgm.feature.alarm

import android.content.Context
import com.north7.bridgecgm.core.data.Repository
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace
import com.north7.bridgecgm.core.prefs.AppPrefs
import com.north7.bridgecgm.core.prefs.MultiSourceSettings

/**
 * Central alarm state machine for Bridge.
 *
 * This file was updated so the alarm engine now follows the multi-source product rule exactly:
 * only the selected primary input source is allowed to drive reminders.
 *
 * That means the evaluator must not look at the latest global reading when multiple vendors are
 * active, otherwise a non-primary source could trigger an alarm unexpectedly.
 */
object ReminderAlertEvaluator {

    /**
     * Evaluates the latest relevant glucose reading and updates alarm runtime state.
     *
     * Source-selection rule:
     * - if the user selected a primary input source, evaluate only that source;
     * - otherwise, fall back to the previous global-latest behavior so the app remains usable
     *   before the user makes an explicit source selection.
     */
    suspend fun evaluateAndTrigger(context: Context) {
        val prefs = AppPrefs(context)
        val repo = Repository(context)
        val multiSourceSettings = MultiSourceSettings(context)
        val primaryId = multiSourceSettings.primaryInputSourceId
        val latest = if (!primaryId.isNullOrBlank()) {
            repo.latestReadingForSource(primaryId)
        } else {
            repo.latestReadings(1).firstOrNull()
        }
        if (latest == null) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-EVAL", "no readings for primary=$primaryId")
            clearAll(context, prefs)
            return
        }

        val latestMgdl = latest.calibratedValueMgdl.toDouble()
        val now = System.currentTimeMillis()
        val rules = AlarmConfig.all(prefs)
        val winner = rules.firstOrNull { it.enabled && it.shouldTrigger(latestMgdl) }

        DebugTrace.t(
            DebugCategory.ALARM,
            "ALARM-EVAL",
            "primary=$primaryId latest=$latestMgdl winner=${winner?.kind} rules=" + rules.joinToString { rule ->
                "${rule.kind}(enabled=${rule.enabled},active=${rule.active},threshold=${rule.thresholdMgdl},intervalMin=${rule.intervalMin},durationSec=${rule.durationSec},next=${rule.nextTriggerAtMs})"
            }
        )

        if (winner == null) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-CLEAR", "no threshold crossed latest=$latestMgdl primary=$primaryId")
            clearAll(context, prefs)
            return
        }

        for (rule in rules) {
            if (rule.kind != winner.kind) {
                clearOne(context, prefs, rule)
            }
        }

        val dueNow = !winner.active || winner.nextTriggerAtMs <= 0L || now >= winner.nextTriggerAtMs
        if (!dueNow) {
            val remainingMs = winner.nextTriggerAtMs - now
            DebugTrace.t(
                DebugCategory.ALARM,
                "ALARM-SUPPRESS",
                "${winner.kind} active but not due yet remainingMs=$remainingMs latest=$latestMgdl"
            )
            AlarmNotificationController.showOrUpdateAlarmNotification(context, winner, latestMgdl)
            AlarmReplayScheduler.schedule(context, winner.kind, winner.nextTriggerAtMs)
            return
        }

        val nextAt = now + winner.intervalMs()
        AlarmConfig.persistActive(prefs, winner, true)
        AlarmConfig.persistLastTriggeredAt(prefs, winner, now)
        AlarmConfig.persistNextTriggerAt(prefs, winner, nextAt)

        DebugTrace.w(
            DebugCategory.ALARM,
            "ALARM-TRIGGER",
            "${winner.kind} trigger latest=$latestMgdl threshold=${winner.thresholdMgdl} durationSec=${winner.durationSec} nextAt=$nextAt"
        )
        AlarmNotificationController.showOrUpdateAlarmNotification(context, winner, latestMgdl)
        AlarmSoundPlayer.playByName(context, winner.soundName, winner.durationSec)
        AlarmReplayScheduler.schedule(context, winner.kind, nextAt)
    }

    /** Clears every glucose-alarm runtime state and stops any in-flight sound. */
    fun clearAll(context: Context, prefs: AppPrefs = AppPrefs(context)) {
        AlarmConfig.all(prefs).forEach { clearOne(context, prefs, it) }
        AlarmSoundPlayer.stop()
        AlarmNotificationController.cancelAll(context)
    }

    /**
     * Clears exactly one alarm kind and cancels its replay timer.
     *
     * The logging condition avoids noisy traces when nothing needs to be cleared.
     */
    fun clearOne(context: Context, prefs: AppPrefs, rule: AlarmRule) {
        if (rule.active || rule.nextTriggerAtMs > 0L || rule.lastTriggeredAtMs > 0L) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-CLEAR-ONE", "Clearing ${rule.kind}")
        }
        AlarmReplayScheduler.cancel(context, rule.kind)
        AlarmConfig.clearRuntimeState(prefs, rule)
        AlarmNotificationController.cancel(context, rule.kind)
    }
}