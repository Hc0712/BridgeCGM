/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package tw.yourcompany.cgmbridge.feature.alarm

import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

enum class AlarmKind {
    HIGH,
    LOW,
    URGENT_LOW
}

enum class AlarmMethod(val wireValue: String) {
    SOUND("sound");

    companion object {
        fun fromStored(value: String?): AlarmMethod {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.wireValue == normalized } ?: SOUND
        }
    }
}

data class AlarmRule(
    val kind: AlarmKind,
    val enabled: Boolean,
    val thresholdMgdl: Double,
    val method: AlarmMethod,
    val intervalMin: Int,
    val durationSec: Int,
    val soundName: String,
    val lastTriggeredAtMs: Long
) {
    fun shouldTrigger(latestMgdl: Double): Boolean = when (kind) {
        AlarmKind.HIGH -> latestMgdl >= thresholdMgdl
        AlarmKind.LOW, AlarmKind.URGENT_LOW -> latestMgdl <= thresholdMgdl
    }

    fun intervalMs(): Long = intervalMin.coerceAtLeast(1) * 60_000L
}

object AlarmConfig {
    fun high(prefs: AppPrefs): AlarmRule = AlarmRule(
        kind = AlarmKind.HIGH,
        enabled = prefs.alarmHighEnabled,
        thresholdMgdl = prefs.dHighBlood,
        method = AlarmMethod.fromStored(prefs.alarmHighMethod),
        intervalMin = prefs.alarmHighIntervalMin,
        durationSec = prefs.alarmHighDurationSec,
        soundName = "high",
        lastTriggeredAtMs = prefs.lastHighAlarmMs
    )

    fun low(prefs: AppPrefs): AlarmRule = AlarmRule(
        kind = AlarmKind.LOW,
        enabled = prefs.alarmLowEnabled,
        thresholdMgdl = prefs.dLowBlood,
        method = AlarmMethod.SOUND,
        intervalMin = prefs.alarmLowIntervalMin,
        durationSec = prefs.alarmLowDurationSec,
        soundName = "low",
        lastTriggeredAtMs = prefs.lastLowAlarmMs
    )

    fun urgentLow(prefs: AppPrefs): AlarmRule = AlarmRule(
        kind = AlarmKind.URGENT_LOW,
        enabled = prefs.alarmUrgentLowEnabled,
        thresholdMgdl = prefs.dUrgentLowBlood,
        method = AlarmMethod.SOUND,
        intervalMin = prefs.alarmUrgentLowIntervalMin,
        durationSec = prefs.alarmUrgentLowDurationSec,
        soundName = "urgent",
        lastTriggeredAtMs = prefs.lastUrgentLowAlarmMs
    )

    fun all(prefs: AppPrefs): List<AlarmRule> = listOf(
        urgentLow(prefs),
        low(prefs),
        high(prefs)
    )

    fun persistLastTriggeredAt(prefs: AppPrefs, rule: AlarmRule, timestampMs: Long) {
        when (rule.kind) {
            AlarmKind.HIGH -> prefs.lastHighAlarmMs = timestampMs
            AlarmKind.LOW -> prefs.lastLowAlarmMs = timestampMs
            AlarmKind.URGENT_LOW -> prefs.lastUrgentLowAlarmMs = timestampMs
        }
    }
}
