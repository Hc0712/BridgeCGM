package tw.yourcompany.cgmbridge.feature.input.notification

import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Calculates trend direction from the slope between the two most recent readings.
 *
 * Mirrors xDrip+ 2025.09.05 logic:
 *   1. `BgReading.find_slope()` — computes (prev_value - current_value) / (prev_time - current_time)
 *   2. `Dex_Constants.TREND_ARROW_VALUES.getTrend(slope_per_minute)` — maps slope to a trend name.
 *   3. `BestGlucose.unitizedDeltaString()` — delta normalized to 5-minute period.
 *
 * All calculations are done in mg/dL internally. The DB stores mg/dL.
 * Display conversion (mg/dL → mmol/L) is handled in the UI layer only.
 *
 * Thresholds from xDrip+ `Dex_Constants.java` (mg/dL per minute):
 *   slope >  3.5  → DoubleUp
 *   slope >  2.0  → SingleUp
 *   slope >  1.0  → FortyFiveUp
 *   slope > -1.0  → Flat
 *   slope > -2.0  → FortyFiveDown
 *   slope > -3.5  → SingleDown
 *   slope ≤ -3.5  → DoubleDown
 */
object SlopeDirectionCalculator {

    /**
     * Result of a slope calculation.
     *
     * @param slopePerMinute  mg/dL per minute (positive = rising)
     * @param deltaPerFiveMin mg/dL change normalized to 5 minutes
     * @param directionName   xDrip-compatible direction string
     * @param minutesAgo      minutes since the latest reading
     * @param isValid         false when there aren't enough readings or gap > 20 min
     */
    data class SlopeResult(
        val slopePerMinute: Double,
        val deltaPerFiveMin: Double,
        val directionName: String,
        val minutesAgo: Int,
        val isValid: Boolean
    ) {
        companion object {
            val EMPTY = SlopeResult(0.0, 0.0, "NONE", 0, false)
        }
    }

    // Ordered from steepest rise to steepest fall (matching xDrip getTrend iteration order).
    private data class Threshold(val minSlope: Double, val name: String)

    private val THRESHOLDS = listOf(
        Threshold(3.5, "DoubleUp"),
        Threshold(2.0, "SingleUp"),
        Threshold(1.0, "FortyFiveUp"),
        Threshold(-1.0, "Flat"),
        Threshold(-2.0, "FortyFiveDown"),
        Threshold(-3.5, "SingleDown")
        // anything below -3.5 → DoubleDown
    )

    /**
     * Maximum gap in ms between two readings for delta display (20 min, same as xDrip).
     * Beyond this gap, delta shows "???" but slope is still calculated
     * (it naturally normalizes to near-zero for long gaps).
     */
    private const val MAX_DELTA_GAP_MS = 20L * 60 * 1000

    /**
     * Minimum time gap (ms) between two readings used for slope calculation.
     *
     * Must match [BgReadingImporter.MIN_GAP_MS] (50 seconds, Plan C).
     * Defense-in-depth: even if old rapid duplicates (~960ms apart from AiDEX)
     * already exist in the DB, the slope calculator will skip them and find
     * a reading pair with a meaningful time gap.
     *
     * Without this guard, slope = (2 mg/dL) / (0.016 min) = -124 mg/dL/min → DoubleDown.
     */
    private const val MIN_SLOPE_GAP_MS = 50_000L

    /**
     * Maps a direction name to a Unicode arrow character.
     */
    fun directionToArrow(direction: String): String = when (direction) {
        "DoubleUp"      -> "\u21C8"   // ⇈
        "SingleUp"      -> "\u2191"   // ↑
        "FortyFiveUp"   -> "\u2197"   // ↗
        "Flat"          -> "\u2192"   // →
        "FortyFiveDown" -> "\u2198"   // ↘
        "SingleDown"    -> "\u2193"   // ↓
        "DoubleDown"    -> "\u21CA"   // ⇊
        else            -> ""
    }

    /**
     * Calculates the slope, direction, and delta from a list of readings.
     *
     * @param readings  All readings in the current window (any order).
     *                  We pick the most recent, then scan backwards to find the first
     *                  "previous" reading ≥ [MIN_SLOPE_GAP_MS] older. This skips any
     *                  rapid duplicates (AiDEX ~960ms) that may still exist in the DB.
     * @return [SlopeResult] with computed values.
     */
    fun calculate(readings: List<BgReadingEntity>): SlopeResult {
        if (readings.isEmpty()) return SlopeResult.EMPTY

        val sorted = readings.sortedByDescending { it.timestampMs }
        val current = sorted[0]
        val minutesAgo = ((System.currentTimeMillis() - current.timestampMs) / 60_000).toInt()

        // Find the first previous reading with sufficient time gap.
        val previous = sorted.drop(1).firstOrNull { r ->
            (current.timestampMs - r.timestampMs) >= MIN_SLOPE_GAP_MS
        }

        if (previous == null) {
            DebugTrace.v(
                DebugCategory.PARSING,
                "SLOPE-CALC"
            ) {
                "No previous reading with gap>=${MIN_SLOPE_GAP_MS}ms " +
                "(have ${sorted.size} total) → Flat, minutesAgo=$minutesAgo"
            }
            return SlopeResult(
                slopePerMinute = 0.0,
                deltaPerFiveMin = 0.0,
                directionName = "Flat",
                minutesAgo = minutesAgo,
                isValid = true
            )
        }

        val timeDeltaMs = current.timestampMs - previous.timestampMs


        // xDrip: slope = (prev - current) / (prev_ts - current_ts)  → positive when rising
        // Equivalent: (current - prev) / (current_ts - prev_ts) → same sign
        val valueDelta = current.CalibratedValueMgdl.toDouble() - previous.CalibratedValueMgdl.toDouble()
        val minutesDelta = timeDeltaMs.toDouble() / 60_000.0
        val slopePerMinute = valueDelta / minutesDelta

        // Delta normalized to 5-minute period (same as xDrip BestGlucose line 318)
        val deltaPerFiveMin = slopePerMinute * 5.0

        // Direction from slope
        val direction = slopeToDirection(slopePerMinute)

        // Delta is only valid if gap ≤ 20 minutes (xDrip BestGlucose line 315)
        val deltaValid = timeDeltaMs <= MAX_DELTA_GAP_MS

        DebugTrace.v(
            DebugCategory.PARSING,
            "SLOPE-CALC"
        ) {
            "curr=${current.CalibratedValueMgdl}@${current.timestampMs} " +
            "prev=${previous.CalibratedValueMgdl}@${previous.timestampMs} " +
            "Δ=${valueDelta}mg/dL over ${"%.1f".format(minutesDelta)}min " +
            "slope=${"%.3f".format(slopePerMinute)}/min " +
            "delta5=${"%.1f".format(deltaPerFiveMin)}mg/dL " +
            "dir=$direction deltaValid=$deltaValid minutesAgo=$minutesAgo"
        }

        return SlopeResult(
            slopePerMinute = slopePerMinute,
            deltaPerFiveMin = if (deltaValid) deltaPerFiveMin else Double.NaN,
            directionName = direction,
            minutesAgo = minutesAgo,
            isValid = true
        )
    }

    /**
     * Maps slope (mg/dL per minute) to an xDrip-compatible direction name.
     * Mirrors `Dex_Constants.TREND_ARROW_VALUES.getTrend()`.
     */
    fun slopeToDirection(slopePerMinute: Double): String {
        for (t in THRESHOLDS) {
            if (slopePerMinute > t.minSlope) return t.name
        }
        return "DoubleDown"
    }
}
