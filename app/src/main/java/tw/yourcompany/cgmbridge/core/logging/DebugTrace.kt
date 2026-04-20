package tw.yourcompany.cgmbridge.core.logging

import android.util.Log
import tw.yourcompany.cgmbridge.core.config.FeatureFlags

/**
 * Centralized log helper.
 *
 * [verbose] is determined at compile time by [FeatureFlags.verboseDump]:
 *   - Debug build  → true  (verbose output enabled)
 *   - Release build → false (verbose output disabled, zero cost)
 *
 * When false, [v] calls are completely skipped — no string building, zero cost.
 */
object DebugTrace {

    @PublishedApi internal const val TAG = "CgmBridgeTrace"

    @PublishedApi
    internal fun isCategoryEnabled(category: DebugCategory): Boolean {
        if (!FeatureFlags.verboseDump) return false
        return when (category) {
            DebugCategory.PARSING -> FeatureFlags.debugParsing
            DebugCategory.PLOTTING -> FeatureFlags.debugPlotting
            DebugCategory.KEEPALIVE -> FeatureFlags.debugKeepAlive
            DebugCategory.NOTIFICATION -> FeatureFlags.debugNotification
            DebugCategory.DATABASE -> FeatureFlags.debugDatabase
            DebugCategory.CALIBRATION -> FeatureFlags.debugCalibration
            DebugCategory.NIGHTSCOUT -> FeatureFlags.debugNightscout
            DebugCategory.SETTING -> FeatureFlags.debugSetting
            DebugCategory.STATISTICS -> FeatureFlags.debugStatistics
            DebugCategory.BLUETOOTH -> FeatureFlags.debugBluetooth
            DebugCategory.WIFI -> FeatureFlags.debugWifi
            DebugCategory.ALARM -> FeatureFlags.debugAlarm
        }
    }

    /** Always-on debug trace line. */
    fun t(category: DebugCategory, pointId: String, message: String) {
        Log.d(TAG, "[${category.name}] TRACE_POINT:$pointId | $message")
    }

    /**
     * Verbose-only trace line.
     * The [msgProvider] lambda is NOT invoked when the category is disabled,
     * so expensive string building has zero cost in release.
     */
    inline fun v(category: DebugCategory, pointId: String, msgProvider: () -> String) {
        if (isCategoryEnabled(category)) Log.d(TAG, "[${category.name}] VERBOSE:$pointId | ${msgProvider()}")
    }

    fun w(category: DebugCategory, pointId: String, message: String, tr: Throwable? = null) {
        Log.w(TAG, "[${category.name}] TRACE_POINT:$pointId | $message", tr)
    }

    fun e(category: DebugCategory, pointId: String, message: String, tr: Throwable? = null) {
        Log.e(TAG, "[${category.name}] TRACE_POINT:$pointId | $message", tr)
    }
}
