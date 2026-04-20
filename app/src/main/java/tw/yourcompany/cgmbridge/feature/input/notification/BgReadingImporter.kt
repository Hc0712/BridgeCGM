package tw.yourcompany.cgmbridge.feature.input.notification

import tw.yourcompany.cgmbridge.core.model.GlucoseSample
import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationSettings
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Validates and stores parsed glucose samples.
 *
 * Dedup strategy (Plan C — fixed 50-second minimum gap):
 *
 * AiDEX posts TWO notifications per CGM cycle, ~960ms apart. Without filtering,
 * both are stored, causing wrong slope (-124 mg/dL/min) and duplicate chart points.
 *
 * xDrip+ uses a two-layer dynamic dedup (10s for different values, 250s for same
 * values, plus a hard 60s floor in bgReadingInsertFromG5). We use a simpler fixed
 * 50-second window that:
 *   - Blocks the ~960ms rapid duplicate (50s >> 960ms)  ✓
 *   - Accepts every real 1-minute AiDEX reading (60s > 50s)  ✓
 *   - Accepts every 5-minute Dexcom reading (300s >> 50s)  ✓
 *   - Stores all readings even when value is stable (unlike xDrip's 250s same-value skip)
 *
 * Reference: xDrip-2025.09.05 UiBasedCollector.handleNewValue() lines 373-379
 *            + BgReading.bgReadingInsertFromG5() line 1123
 */
class BgReadingImporter(
    private val repo: Repository,
    private val prefs: AppPrefs
) {

    companion object {
        /**
         * Minimum time gap (ms) between consecutive stored readings.
         *
         * 50 seconds — chosen to sit between:
         *   - AiDEX duplicate gap: ~960ms (blocked)
         *   - AiDEX real interval:  60s   (accepted)
         *   - Dexcom real interval: 300s  (accepted)
         *
         * Comparable to xDrip+'s Layer 2 hard floor of 60s (MINUTE_IN_MS)
         * in bgReadingInsertFromG5, but slightly more permissive.
         */
        const val MIN_GAP_MS = 50_000L
    }

    /** Represents the outcome of one import attempt. */
    sealed class ImportResult {
        data class Inserted(val entity: BgReadingEntity) : ImportResult()
        data class IgnoredDuplicate(val timestampMs: Long) : ImportResult()
        /** Skipped because it arrived within [MIN_GAP_MS] of the previous reading. */
        data class IgnoredTooSoon(val timestampMs: Long, val gapMs: Long) : ImportResult()
        data class InvalidValue(val valueMgdl: Int) : ImportResult()
        data class InvalidTimestamp(val timestampMs: Long) : ImportResult()
        data class StatusOnly(val status: String?, val alert: String?) : ImportResult()
    }

    /**
     * Validates a normalized sample and inserts it when valid.
     *
     * Guard layers (in order):
     *   1. Value < 0 → StatusOnly (sensor status message, no glucose)
     *   2. Timestamp sanity (not zero, not in the future)
     *   3. Value range (20–600 mg/dL)
     *   4. **Minimum gap filter** — if the latest DB reading is < [MIN_GAP_MS] old,
     *      drop this sample. This blocks AiDEX's ~960ms rapid duplicate.
     *   5. Room UNIQUE index on timestampMs (final safety net)
     */
    suspend fun import(sample: GlucoseSample): ImportResult {
        if (sample.valueMgdl < 0) {
            return ImportResult.StatusOnly(sample.sensorStatus, sample.alertText)
        }

        val now = System.currentTimeMillis()
        if (sample.timestampMs <= 0L || sample.timestampMs > now + 5 * 60 * 1000L) {
            return ImportResult.InvalidTimestamp(sample.timestampMs)
        }

        if (sample.valueMgdl !in 20..600) {
            return ImportResult.InvalidValue(sample.valueMgdl)
        }

        // ── Minimum gap filter (Plan C: fixed 50 seconds) ──
        val latestList = repo.latestReadings(1)
        if (latestList.isNotEmpty()) {
            val gap = sample.timestampMs - latestList[0].timestampMs
            if (gap in 1 until MIN_GAP_MS) {
                DebugTrace.v(
                    DebugCategory.DATABASE,
                    "IMPORT-GAP"
                ) {
                    "Skipped rapid duplicate: gap=${gap}ms (<${MIN_GAP_MS}ms) " +
                    "new=${sample.valueMgdl}@${sample.timestampMs} " +
                    "prev=${latestList[0].calculatedValueMgdl}@${latestList[0].timestampMs}"
                }
                return ImportResult.IgnoredTooSoon(sample.timestampMs, gap)
            }
        }

        val calibratedValueMgdl = CalibrationSettings.apply(sample.valueMgdl, prefs)

        val entity = BgReadingEntity(
            timestampMs = sample.timestampMs,
            calculatedValueMgdl = sample.valueMgdl,
            CalibratedValueMgdl = calibratedValueMgdl,
            direction = sample.direction,
            sourcePackage = sample.sourcePackage,
            rawText = sample.rawText,
            sensorStatus = sample.sensorStatus,
            alertText = sample.alertText
        )

        val rowId = repo.insertReading(entity)
        return if (rowId == -1L) {
            ImportResult.IgnoredDuplicate(sample.timestampMs)
        } else {
            ImportResult.Inserted(entity)
        }
    }
}
