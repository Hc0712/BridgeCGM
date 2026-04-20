package tw.yourcompany.cgmbridge.feature.input.notification

import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Maps various vendor trend formats to xDrip-compatible direction names.
 *
 * xDrip uses strings like: Flat, SingleUp, DoubleUp, SingleDown, DoubleDown,
 * FortyFiveUp, FortyFiveDown, NONE.
 *
 * Verbose log tag TREND-MAP is controlled by cgmbridge.verboseDump.
 */
object TrendDirectionMapper {

    /**
     * Maps a raw token into a normalized xDrip direction string.
     */
    fun map(raw: String?): String {
        if (raw.isNullOrBlank()) {
            DebugTrace.v(
                DebugCategory.PARSING,
                "TREND-MAP"
            ) { "raw=null/blank → NONE" }
            return "NONE"
        }
        val s = raw.trim().lowercase()
        val result = when {
            // Double arrows MUST be checked before single arrows to avoid premature match.
            s.contains("↑↑") || s.contains("doubleup") -> "DoubleUp"
            s.contains("↓↓") || s.contains("doubledown") -> "DoubleDown"
            s.contains("↑") || s.contains("singleup") -> "SingleUp"
            s.contains("↗") || s.contains("fortyfiveup") -> "FortyFiveUp"
            s.contains("→") || s.contains("flat") -> "Flat"
            s.contains("↘") || s.contains("fortyfivedown") -> "FortyFiveDown"
            s.contains("↓") || s.contains("singledown") -> "SingleDown"
            else -> "NONE"
        }
        DebugTrace.v(
            DebugCategory.PARSING,
            "TREND-MAP"
        ) { "raw=[$raw] lowercase=[$s] → mapped=$result" }
        return result
    }
}
