/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package com.north7.bridgecgm.feature.alarm

import com.north7.bridgecgm.core.prefs.AppPrefs

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
    val lastTriggeredAtMs: Long,
    val active: Boolean,
    val nextTriggerAtMs: Long
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
        lastTriggeredAtMs = prefs.lastHighAlarmMs,
        active = prefs.highAlarmActive,
        nextTriggerAtMs = prefs.nextHighAlarmAtMs
    )

    fun low(prefs: AppPrefs): AlarmRule = AlarmRule(
        kind = AlarmKind.LOW,
        enabled = prefs.alarmLowEnabled,
        thresholdMgdl = prefs.dLowBlood,
        method = AlarmMethod.SOUND,
        intervalMin = prefs.alarmLowIntervalMin,
        durationSec = prefs.alarmLowDurationSec,
        soundName = "low",
        lastTriggeredAtMs = prefs.lastLowAlarmMs,
        active = prefs.lowAlarmActive,
        nextTriggerAtMs = prefs.nextLowAlarmAtMs
    )

    fun urgentLow(prefs: AppPrefs): AlarmRule = AlarmRule(
        kind = AlarmKind.URGENT_LOW,
        enabled = prefs.alarmUrgentLowEnabled,
        thresholdMgdl = prefs.dUrgentLowBlood,
        method = AlarmMethod.SOUND,
        intervalMin = prefs.alarmUrgentLowIntervalMin,
        durationSec = prefs.alarmUrgentLowDurationSec,
        soundName = "urgent",
        lastTriggeredAtMs = prefs.lastUrgentLowAlarmMs,
        active = prefs.urgentLowAlarmActive,
        nextTriggerAtMs = prefs.nextUrgentLowAlarmAtMs
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

    /** Persists whether this alarm kind is currently active. */
    fun persistActive(prefs: AppPrefs, rule: AlarmRule, active: Boolean) {
        when (rule.kind) {
            AlarmKind.HIGH -> prefs.highAlarmActive = active
            AlarmKind.LOW -> prefs.lowAlarmActive = active
            AlarmKind.URGENT_LOW -> prefs.urgentLowAlarmActive = active
        }
    }

    /** Persists the exact wall-clock time when this alarm should replay next. */
    fun persistNextTriggerAt(prefs: AppPrefs, rule: AlarmRule, timestampMs: Long) {
        when (rule.kind) {
            AlarmKind.HIGH -> prefs.nextHighAlarmAtMs = timestampMs
            AlarmKind.LOW -> prefs.nextLowAlarmAtMs = timestampMs
            AlarmKind.URGENT_LOW -> prefs.nextUrgentLowAlarmAtMs = timestampMs
        }
    }

    /**
     * Clears all runtime state for this alarm kind.
     *
     * We intentionally reset [lastTriggeredAtMs] to zero as well. That way, when
     * glucose later leaves the normal range again, the first qualifying reading
     * will alarm immediately instead of being incorrectly rate-limited by the
     * previous episode's timestamp.
     */
    fun clearRuntimeState(prefs: AppPrefs, rule: AlarmRule) {
        persistActive(prefs, rule, false)
        persistNextTriggerAt(prefs, rule, 0L)
        persistLastTriggeredAt(prefs, rule, 0L)
    }
}
