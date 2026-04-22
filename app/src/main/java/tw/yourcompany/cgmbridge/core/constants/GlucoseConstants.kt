package tw.yourcompany.cgmbridge.core.constants

/**
 * Centralized constants for glucose-related values and conversions.
 *
 * Move all glucose-related constants here for single-point reference.
 */

// Add more glucose-related constants here as needed
object GlucoseConstants {
    const val MMOL_FACTOR = 18.0
    const val HIGH_DEFAULT_MGDL = 180.0
    const val LOW_DEFAULT_MGDL = 70.0
    const val URGENT_LOW_DEFAULT_MGDL = 54.0
    const val DEFAULT_MIN_MGDL = 40.0
    const val DEFAULT_MAX_MGDL = 250.0
    const val TITR_MAX = 140.0
    const val TITR_MIN = 70.0

    const val MIDDLE_MGDL = 126.0 // 7 mmol/L
}
