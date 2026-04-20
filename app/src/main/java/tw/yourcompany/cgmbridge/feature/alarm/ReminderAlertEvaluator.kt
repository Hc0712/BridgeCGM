package tw.yourcompany.cgmbridge.feature.alarm

import android.content.Context
import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

object ReminderAlertEvaluator {
    suspend fun evaluateAndTrigger(context: Context) {
        val prefs = AppPrefs(context)
        val latest = Repository(context).latestReadings(1).firstOrNull()
        if (latest == null) {
            DebugTrace.t(DebugCategory.ALARM, "ALARM-EVAL", "no readings")
            return
        }

        val latestMgdl = latest.CalibratedValueMgdl.toDouble()
        val now = System.currentTimeMillis()
        val rules = AlarmConfig.all(prefs)

        DebugTrace.t(
            DebugCategory.ALARM,
            "ALARM-EVAL",
            "latest=$latestMgdl rules=" + rules.joinToString { rule ->
                "${rule.kind}(enabled=${rule.enabled},threshold=${rule.thresholdMgdl},intervalMin=${rule.intervalMin},durationSec=${rule.durationSec},method=${rule.method.wireValue})"
            }
        )

        for (rule in rules) {
            if (!rule.enabled) {
                DebugTrace.v(DebugCategory.ALARM, "ALARM-SKIP") { "${rule.kind} disabled" }
                continue
            }
            if (!rule.shouldTrigger(latestMgdl)) {
                DebugTrace.v(DebugCategory.ALARM, "ALARM-SKIP") { "${rule.kind} not crossed latest=$latestMgdl threshold=${rule.thresholdMgdl}" }
                continue
            }

            val intervalMs = rule.intervalMs()
            val elapsedMs = if (rule.lastTriggeredAtMs > 0L) now - rule.lastTriggeredAtMs else Long.MAX_VALUE
            if (rule.lastTriggeredAtMs == 0L || elapsedMs >= intervalMs) {
                AlarmConfig.persistLastTriggeredAt(prefs, rule, now)
                DebugTrace.w(DebugCategory.ALARM, "ALARM-TRIGGER", "${rule.kind} trigger latest=$latestMgdl threshold=${rule.thresholdMgdl} method=${rule.method.wireValue} intervalMin=${rule.intervalMin} durationSec=${rule.durationSec}")
                AlarmSoundPlayer.playByName(context, rule.soundName, rule.durationSec)
            } else {
                DebugTrace.t(DebugCategory.ALARM, "ALARM-SUPPRESS", "${rule.kind} suppressed remainingMs=${intervalMs - elapsedMs} latest=$latestMgdl")
            }
            return
        }

        DebugTrace.t(DebugCategory.ALARM, "ALARM-EVAL", "no threshold crossed latest=$latestMgdl")
    }
}
