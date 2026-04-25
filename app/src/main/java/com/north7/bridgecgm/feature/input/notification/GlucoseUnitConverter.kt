package com.north7.bridgecgm.feature.input.notification

import kotlin.math.roundToInt

/**
 * Converts glucose values into mg/dL.
 */
object GlucoseUnitConverter {

    /** Converts mmol/L to mg/dL. */
    fun mmolToMgdl(mmol: Double): Int = (mmol * com.north7.bridgecgm.core.constants.GlucoseConstants.MMOL_FACTOR).roundToInt()

    /** Converts mg/dL to a formatted mmol/L display string. */
    fun mgdlToMmolString(mgdl: Int): String = String.format(java.util.Locale.US, "%.1f", mgdl / com.north7.bridgecgm.core.constants.GlucoseConstants.MMOL_FACTOR)
}
