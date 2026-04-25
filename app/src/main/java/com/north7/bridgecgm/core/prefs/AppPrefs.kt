package com.north7.bridgecgm.core.prefs
import android.content.Context
import android.content.SharedPreferences
import com.north7.bridgecgm.core.constants.GlucoseConstants

/**
 * Minimal SharedPreferences wrapper.
 * This object stores choices from the setup page and reminder settings.
 */
class AppPrefs(context: Context) {

    private val sp = context.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)

    // Glucose-related constants are now centralized in GlucoseConstants.kt

    var setupAccepted: Boolean
        get() = sp.getBoolean("setupAccepted", false)
        set(value) = sp.edit().putBoolean("setupAccepted", value).apply()


    /** True after the user accepts the first-launch disclaimer. */
    var disclaimerAccepted: Boolean
        get() = sp.getBoolean("disclaimerAccepted", false)
        set(value) = sp.edit().putBoolean("disclaimerAccepted", value).apply()

    /** Output glucose unit shown on the main screen. */
    var outputUnit: String
        get() = sp.getString("outputUnit", "mgdl") ?: "mgdl"
        set(value) = sp.edit().putString("outputUnit", value).apply()

    /** Selected role from the setup page. */
    var role: String
        get() = sp.getString("role", "host") ?: "host"
        set(value) = sp.edit().putString("role", value).apply()


    /** True when calibration is enabled. */
    var calibrationEnabled: Boolean
        get() = sp.getBoolean("calibrationEnabled", false)
        set(value) = sp.edit().putBoolean("calibrationEnabled", value).apply()

    /** Selected calibration algorithm. */
    var calibrationAlgorithm: String
        get() = sp.getString("calibrationAlgorithm", "LEVEL_SHIFT") ?: "LEVEL_SHIFT"
        set(value) = sp.edit().putString("calibrationAlgorithm", value).apply()

    /** Level Shift value shown on the UI and used by the importer. */
    var calibrationLevelShift: Double
        get() = sp.getString("calibrationLevelShift", "0")?.toDoubleOrNull() ?: 0.0
        set(value) = sp.edit().putString("calibrationLevelShift", value.toString()).apply()

    /** Reserved Linear Transform slope shown on the UI and stored for future use. */
    var calibrationLinearSlope: Double
        get() = sp.getString("calibrationLinearSlope", "1")?.toDoubleOrNull() ?: 1.0
        set(value) = sp.edit().putString("calibrationLinearSlope", value.toString()).apply()

    /** Reserved Linear Transform shift shown on the UI and stored for future use. */
    var calibrationLinearShift: Double
        get() = sp.getString("calibrationLinearShift", "0")?.toDoubleOrNull() ?: 0.0
        set(value) = sp.edit().putString("calibrationLinearShift", value.toString()).apply()

    var alarmHighEnabled: Boolean
        get() = sp.getBoolean("alarmHighEnabled", false)
        set(value) = sp.edit().putBoolean("alarmHighEnabled", value).apply()

    var alarmLowEnabled: Boolean
        get() = sp.getBoolean("alarmLowEnabled", true)
        set(value) = sp.edit().putBoolean("alarmLowEnabled", value).apply()

    var alarmUrgentLowEnabled: Boolean
        get() = sp.getBoolean("alarmUrgentLowEnabled", true)
        set(value) = sp.edit().putBoolean("alarmUrgentLowEnabled", value).apply()

    /** dHighBlood stored internally as mg/dL. */
    var dHighBlood: Double
        get() = sp.getString("dHighBloodMgdl", GlucoseConstants.HIGH_DEFAULT_MGDL.toString())?.toDoubleOrNull() ?: GlucoseConstants.HIGH_DEFAULT_MGDL
        set(value) = sp.edit().putString("dHighBloodMgdl", value.toString()).apply()

    /** dLowBlood stored internally as mg/dL. */
    var dLowBlood: Double
        get() = sp.getString("dLowBloodMgdl", GlucoseConstants.LOW_DEFAULT_MGDL.toString())?.toDoubleOrNull() ?: GlucoseConstants.LOW_DEFAULT_MGDL
        set(value) = sp.edit().putString("dLowBloodMgdl", value.toString()).apply()

    /** dUrgentLowBlood stored internally as mg/dL. */
    var dUrgentLowBlood: Double
        get() = sp.getString("dUrgentLowBloodMgdl", GlucoseConstants.URGENT_LOW_DEFAULT_MGDL.toString())?.toDoubleOrNull() ?: GlucoseConstants.URGENT_LOW_DEFAULT_MGDL
        set(value) = sp.edit().putString("dUrgentLowBloodMgdl", value.toString()).apply()

    var alarmHighMethod: String
        get() = sp.getString("alarmHighMethod", "sound") ?: "sound"
        set(value) = sp.edit().putString("alarmHighMethod", value).apply()

    var alarmHighIntervalMin: Int
        get() = sp.getInt("alarmHighIntervalMin", 15)
        set(value) = sp.edit().putInt("alarmHighIntervalMin", value).apply()

    var alarmHighDurationSec: Int
        get() = sp.getInt("alarmHighDurationSec", 2)
        set(value) = sp.edit().putInt("alarmHighDurationSec", value).apply()


    var alarmLowIntervalMin: Int
        get() = sp.getInt("alarmLowIntervalMin", 15)
        set(value) = sp.edit().putInt("alarmLowIntervalMin", value).apply()

    var alarmLowDurationSec: Int
        get() = sp.getInt("alarmLowDurationSec", 2)
        set(value) = sp.edit().putInt("alarmLowDurationSec", value).apply()

    var alarmUrgentLowIntervalMin: Int
        get() = sp.getInt("alarmUrgentLowIntervalMin", 5)
        set(value) = sp.edit().putInt("alarmUrgentLowIntervalMin", value).apply()

    var alarmUrgentLowDurationSec: Int
        get() = sp.getInt("alarmUrgentLowDurationSec", 10)
        set(value) = sp.edit().putInt("alarmUrgentLowDurationSec", value).apply()

    var lastHighAlarmMs: Long
        get() = sp.getLong("lastHighAlarmMs", 0L)
        set(value) = sp.edit().putLong("lastHighAlarmMs", value).apply()

    var lastLowAlarmMs: Long
        get() = sp.getLong("lastLowAlarmMs", 0L)
        set(value) = sp.edit().putLong("lastLowAlarmMs", value).apply()

    var lastUrgentLowAlarmMs: Long
        get() = sp.getLong("lastUrgentLowAlarmMs", 0L)
        set(value) = sp.edit().putLong("lastUrgentLowAlarmMs", value).apply()


    /**
     * Runtime state flag for the high-glucose alarm.
     *
     * Meaning of "active": the latest known glucose value is still inside the
     * alarm zone, so the alarm engine must continue repeating at the configured
     * reminder interval until recovery or manual disable.
     */
    var highAlarmActive: Boolean
        get() = sp.getBoolean("highAlarmActive", false)
        set(value) = sp.edit().putBoolean("highAlarmActive", value).apply()

    /** Same meaning as [highAlarmActive], but for the low-glucose alarm. */
    var lowAlarmActive: Boolean
        get() = sp.getBoolean("lowAlarmActive", false)
        set(value) = sp.edit().putBoolean("lowAlarmActive", value).apply()

    /** Same meaning as [highAlarmActive], but for the urgent-low alarm. */
    var urgentLowAlarmActive: Boolean
        get() = sp.getBoolean("urgentLowAlarmActive", false)
        set(value) = sp.edit().putBoolean("urgentLowAlarmActive", value).apply()

    /**
     * Absolute epoch timestamp when the next high-glucose replay is due.
     *
     * This is stored so the app can re-arm the replay alarm after process death,
     * app update, or device reboot.
     */
    var nextHighAlarmAtMs: Long
        get() = sp.getLong("nextHighAlarmAtMs", 0L)
        set(value) = sp.edit().putLong("nextHighAlarmAtMs", value).apply()

    /** Absolute epoch timestamp when the next low-glucose replay is due. */
    var nextLowAlarmAtMs: Long
        get() = sp.getLong("nextLowAlarmAtMs", 0L)
        set(value) = sp.edit().putLong("nextLowAlarmAtMs", value).apply()

    /** Absolute epoch timestamp when the next urgent-low replay is due. */
    var nextUrgentLowAlarmAtMs: Long
        get() = sp.getLong("nextUrgentLowAlarmAtMs", 0L)
        set(value) = sp.edit().putLong("nextUrgentLowAlarmAtMs", value).apply()

    fun glucoseForDisplay(mgdl: Double): Double {
        return if (outputUnit == "mmol") mgdl / GlucoseConstants.MMOL_FACTOR else mgdl
    }

    fun glucoseFromDisplay(displayValue: Double): Double {
        return if (outputUnit == "mmol") displayValue * GlucoseConstants.MMOL_FACTOR else displayValue
    }
    /**
     * ── KEEP-ALIVE: Primary liveness indicator ──
     *
     * Epoch millis of the last successfully received CGM notification.
     *
     * **Writer:** [CgmNotificationListenerService.onNotificationPosted] — updated
     * every time a supported-package notification passes the package filter. 
     *
     * **Readers:**
     * - [NotificationPollReceiver] (Layer 4) — checks every 5 min, flags stale if >7 min
     * - [HealthCheckWorker] (Layer 5) — checks every ~15 min, flags stale if >15 min
     *
     * If this value falls behind by more than the threshold, the watchdog calls
     * `requestRebind()` to reconnect the notification listener.
     */         
    var lastNotificationTimestampMs: Long
        get() = sp.getLong("lastNotificationTimestampMs", 0L)
        set(value) = sp.edit().putLong("lastNotificationTimestampMs", value).apply()

    /**
     * ── KEEP-ALIVE: Diagnostic timestamp ──
     *
     * Epoch millis of the last time `onListenerConnected()` was called.
     *
     * **Writer:** [CgmNotificationListenerService.onListenerConnected]
     *
     * **Use:** Diagnostics only — helps determine when the listener was last
     * bound by the system. Not used for staleness detection (that uses
     * [lastNotificationTimestampMs] instead, which proves data is actually flowing).
     */
    var lastListenerConnectedMs: Long
        get() = sp.getLong("lastListenerConnectedMs", 0L)
        set(value) = sp.edit().putLong("lastListenerConnectedMs", value).apply()


    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
