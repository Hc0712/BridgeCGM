package tw.yourcompany.cgmbridge.feature.calibration

import kotlin.math.roundToInt
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

/**
 * Central place for calibration state and formula application.
 * Stored values always mirror what the UI shows.
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

    fun apply(calculatedValueMgdl: Int, prefs: AppPrefs): Int {
        val state = current(prefs)
        if (!state.enabled) return calculatedValueMgdl

        return when (state.algorithm) {
            Algorithm.LEVEL_SHIFT -> (calculatedValueMgdl + state.levelShift).roundToInt()
            Algorithm.LINEAR_TRANSFORM ->
                (calculatedValueMgdl * state.linearSlope + state.linearShift).roundToInt()
        }
    }

    fun resetVariables(prefs: AppPrefs) {
        prefs.calibrationLevelShift = 0.0
        prefs.calibrationLinearSlope = 1.0
        prefs.calibrationLinearShift = 0.0
    }
}
