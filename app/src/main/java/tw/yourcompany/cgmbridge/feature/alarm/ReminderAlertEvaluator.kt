package tw.yourcompany.cgmbridge.feature.alarm

import android.content.Context
import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

/**
 * Central alarm state machine for Bridge.
 *
 * This implementation intentionally separates three concerns that were formerly
 * mixed together:
 *  1. Decide which alarm kind, if any, currently owns the glucose state.
 *  2. Maintain persistent runtime state so interval-based replays survive
 *     process death / reboot.
 *  3. Trigger or suppress sound playback strictly according to the values shown
 *     on the Reminder Setting screen.
 *
 * Priority order matches the existing Bridge behavior and common CGM practice:
 * urgent low wins over low, and low wins over high. Only one glucose alarm kind
 * can be active at a time.
 */
object ReminderAlertEvaluator {

    /**
     * Evaluates the latest stored glucose reading and updates alarm runtime state.
     *
     * Call this in two situations:
     *  - immediately after a new BG reading is inserted, so a newly crossed
     *    threshold alarms right away;
     *  - when a replay alarm fires, so the engine can decide whether to repeat,
     *    suppress, or clear the active state after recovery.
     */
    suspend fun evaluateAndTrigger(context: Context) {
        val prefs = AppPrefs(context)
        val latest = Repository(context).latestReadings(1).firstOrNull()
        if (latest == null) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-EVAL", "no readings")
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
            "latest=$latestMgdl winner=${winner?.kind} rules=" + rules.joinToString { rule ->
                "${rule.kind}(enabled=${rule.enabled},active=${rule.active},threshold=${rule.thresholdMgdl},intervalMin=${rule.intervalMin},durationSec=${rule.durationSec},next=${rule.nextTriggerAtMs})"
            }
        )

        if (winner == null) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-CLEAR", "no threshold crossed latest=$latestMgdl")
            clearAll(context, prefs)
            return
        }

        // Keep only the winning alarm active. This prevents low + urgent-low from
        // both replaying for the same reading window.
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
        AlarmSoundPlayer.playByName(context, winner.soundName, winner.durationSec)
        AlarmReplayScheduler.schedule(context, winner.kind, nextAt)
    }

    /** Clears every glucose-alarm runtime state and stops any in-flight sound. */
    fun clearAll(context: Context, prefs: AppPrefs = AppPrefs(context)) {
        AlarmConfig.all(prefs).forEach { clearOne(context, prefs, it) }
        AlarmSoundPlayer.stop()
    }

    /** Clears exactly one alarm kind and cancels its replay timer. */
    fun clearOne(context: Context, prefs: AppPrefs, rule: AlarmRule) {
        if (rule.active || rule.nextTriggerAtMs > 0L || rule.lastTriggeredAtMs > 0L) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-CLEAR-ONE", "Clearing ${rule.kind}")
        }
        AlarmReplayScheduler.cancel(context, rule.kind)
        AlarmConfig.clearRuntimeState(prefs, rule)
    }
}
