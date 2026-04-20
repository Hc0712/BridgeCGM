package tw.yourcompany.cgmbridge.feature.input.notification

import kotlin.math.roundToInt

/**
 * Converts glucose values into mg/dL.
 */
object GlucoseUnitConverter {

    /** Converts mmol/L to mg/dL. */
    fun mmolToMgdl(mmol: Double): Int = (mmol * tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.MMOL_FACTOR).roundToInt()

    /** Converts mg/dL to a formatted mmol/L display string. */
    fun mgdlToMmolString(mgdl: Int): String = String.format(java.util.Locale.US, "%.1f", mgdl / tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.MMOL_FACTOR)
}
