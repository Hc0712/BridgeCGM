package com.north7.bridgecgm.core.constants

/**
 * Centralized constants for glucose-related values and conversions.
 *
 * This file is intentionally small and dependency-free so every feature that needs
 * glucose thresholds can reuse the same source of truth.
 */
object GlucoseConstants {
    /** Conversion factor between mmol/L and mg/dL. */
    const val MMOL_FACTOR = 18.0

    /** Default high-glucose alarm threshold in mg/dL. */
    const val HIGH_DEFAULT_MGDL = 180.0

    /** Default low-glucose alarm threshold in mg/dL. */
    const val LOW_DEFAULT_MGDL = 70.0

    /** Default urgent-low alarm threshold in mg/dL. */
    const val URGENT_LOW_DEFAULT_MGDL = 54.0

    /** Lowest glucose value shown/allowed by the UI in mg/dL. */
    const val DEFAULT_MIN_MGDL = 40.0

    /** Highest glucose value shown/allowed by the UI in mg/dL. */
    const val DEFAULT_MAX_MGDL = 250.0

    /** Time-in-tight-range upper bound in mg/dL. */
    const val TITR_MAX = 140.0

    /** Time-in-tight-range lower bound in mg/dL. */
    const val TITR_MIN = 70.0

    /**
     * Mid-point threshold used by the Reminder settings range validation.
     * 126 mg/dL equals 7.0 mmol/L.
     */
    const val MIDDLE_MGDL = 126.0
}
