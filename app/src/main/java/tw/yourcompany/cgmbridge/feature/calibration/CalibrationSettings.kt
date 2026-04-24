package tw.yourcompany.cgmbridge.feature.calibration

import kotlin.math.roundToInt
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

/**
 * Central place for calibration state and formula application.
 * Stored values always mirror what the UI shows.
 *
 * Patch rule:
 * - Only LEVEL_SHIFT is implemented today.
 * - LINEAR_TRANSFORM is UI-selectable only as a placeholder and must not change glucose output.
 */
object CalibrationSettings {

    enum class Algorithm {
        LEVEL_SHIFT,
        LINEAR_TRANSFORM
    }

    data class State(
        val enabled: Boolean,
        val algorithm: Algorithm,
        val levelShift: Double,
        val linearSlope: Double,
        val linearShift: Double
    )

    fun current(prefs: AppPrefs): State {
        val algorithm = try {
            Algorithm.valueOf(prefs.calibrationAlgorithm)
        } catch (_: Exception) {
            Algorithm.LEVEL_SHIFT
        }

        return State(
            enabled = prefs.calibrationEnabled,
            algorithm = algorithm,
            levelShift = prefs.calibrationLevelShift,
            linearSlope = prefs.calibrationLinearSlope,
            linearShift = prefs.calibrationLinearShift
        )
    }

    /**
     * Applies calibration to the raw glucose value.
     *
     * Linear Transform intentionally returns the raw value unchanged because the feature is not
     * implemented yet and must not become active accidentally through stored preferences.
     */
    fun apply(rawValueMgdl: Int, prefs: AppPrefs): Int {
        val state = current(prefs)
        if (!state.enabled) return rawValueMgdl

        return when (state.algorithm) {
            Algorithm.LEVEL_SHIFT -> (rawValueMgdl + state.levelShift).roundToInt()
            Algorithm.LINEAR_TRANSFORM -> rawValueMgdl
        }
    }

    /**
     * Resets stored calibration variables.
     *
     * The current UI resets Level Shift / Slop / Shift to 0 when toggling calibration or
     * switching algorithm, so this helper mirrors that behavior.
     */
    fun resetVariables(prefs: AppPrefs) {
        prefs.calibrationLevelShift = 0.0
        prefs.calibrationLinearSlope = 0.0
        prefs.calibrationLinearShift = 0.0
    }
}
