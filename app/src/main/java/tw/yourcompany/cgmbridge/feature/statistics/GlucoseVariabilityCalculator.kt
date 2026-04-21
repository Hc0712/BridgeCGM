/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package tw.yourcompany.cgmbridge.feature.statistics

import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import kotlin.math.sqrt

/**
 * Calculates the six Glucose Variability items shown on the bridge main graph.
 *
 * Why this file exists:
 * - The bridge main screen lets the user switch the statistical interval with
 *   3h / 6h / 12h / 24h chips.
 * - The UI should only render numbers; the math should live in one dedicated,
 *   testable place.
 * - The requested SD / CV / HbA1c / Range behavior is intentionally aligned with
 *   xDrip's statistics implementation so the bridge project is easier to compare
 *   against the reference app.
 *
 * Formula notes (xDrip-aligned):
 * - SD / StdDev:
 *     sqrt( sum((x - mean)^2) / (N - 1) )
 *   This is the sample standard deviation used by xDrip's StatsResult.calc_StdDev().
 *
 * - Relative SD / CV(%):
 *     (SD / mean) * 100
 *   This is the standard coefficient of variation based on the same SD above.
 *
 * - HbA1c est.:
 *     (mean_mgdl + 46.7) / 28.7
 *   This is the common eA1c / HbA1c estimation formula used by xDrip statistics.
 *
 * - Range(in/high/low):
 *   The visible readings are split into 3 buckets using the alarm thresholds:
 *   low  = value < dLowBlood
 *   in   = dLowBlood <= value <= dHighBlood
 *   high = value > dHighBlood
 *
 * Important implementation detail:
 * - All calculations are done in mg/dL because the database stores calibrated
 *   glucose values in mg/dL. Any mmol/L conversion is a UI-only concern.
 */
object GlucoseVariabilityCalculator {

    /**
     * Full result for the main-screen Glucose Variability block.
     *
     * Both counts and percentages are provided for the range buckets.
     * The current bridge UI displays the percentages because they stay meaningful
     * across different time windows (3h vs 24h), while the counts are kept for
     * future UI/debug use.
     */
    data class Result(
        val readingCount: Int,
        val meanMgdl: Double,
        val sdMgdl: Double,
        val cvPercent: Double,
        val minMgdl: Int,
        val maxMgdl: Int,
        val estimatedHba1cPercent: Double,
        val inRangeCount: Int,
        val highCount: Int,
        val lowCount: Int,
        val inRangePercent: Double,
        val highPercent: Double,
        val lowPercent: Double
    )

    /**
     * Calculates the Glucose Variability values for the currently visible readings.
     *
     * @param readings The already-filtered readings for the selected chip interval.
     * @param lowThresholdMgdl The user-configured dLowBlood value from Alarm Settings.
     * @param highThresholdMgdl The user-configured dHighBlood value from Alarm Settings.
     * @return null when there is no data to show; otherwise the complete GV result.
     */
    fun calculate(
        readings: List<BgReadingEntity>,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double
    ): Result? {
        if (readings.isEmpty()) return null

        val values = readings.map { it.calibratedValueMgdl.toDouble() }
        val count = values.size
        val mean = values.average()

        // xDrip-style sample SD: use (N - 1) in the denominator when at least
        // two readings are available. A single reading has SD = 0 by definition
        // for display purposes on this screen.
        val sd = if (count >= 2) {
            val variance = values.sumOf { value ->
                val delta = value - mean
                delta * delta
            } / (count - 1)
            sqrt(variance)
        } else {
            0.0
        }

        // Relative SD / coefficient of variation in percent.
        val cv = if (mean > 0.0) (sd / mean) * 100.0 else 0.0

        // HbA1c estimate from mean glucose in mg/dL.
        val estimatedHba1c = (mean + 46.7) / 28.7

        // Range buckets based on Alarm Settings.
        val lowCount = values.count { it < lowThresholdMgdl }
        val highCount = values.count { it > highThresholdMgdl }
        val inRangeCount = count - lowCount - highCount

        fun pct(bucketCount: Int): Double = (bucketCount * 100.0) / count

        return Result(
            readingCount = count,
            meanMgdl = mean,
            sdMgdl = sd,
            cvPercent = cv,
            minMgdl = values.minOrNull()?.toInt() ?: 0,
            maxMgdl = values.maxOrNull()?.toInt() ?: 0,
            estimatedHba1cPercent = estimatedHba1c,
            inRangeCount = inRangeCount,
            highCount = highCount,
            lowCount = lowCount,
            inRangePercent = pct(inRangeCount),
            highPercent = pct(highCount),
            lowPercent = pct(lowCount)
        )
    }
}
