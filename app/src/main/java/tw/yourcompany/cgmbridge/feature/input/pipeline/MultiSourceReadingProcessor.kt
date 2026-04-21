package tw.yourcompany.cgmbridge.feature.input.pipeline

import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.prefs.MultiSourceSettings
import tw.yourcompany.cgmbridge.feature.alarm.PrimarySourceStalenessNotifier
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationSettings

/**
 * Shared production processor for all normalized readings.
 *
 * Pipeline responsibilities:
 * 1) ensure source registry row exists
 * 2) run per-source deduplication
 * 3) apply calibration only to the selected primary output source
 * 4) persist the reading row
 * 5) leave alarm triggering to the caller for the primary source only
 *
 * This class intentionally does not perform transport-specific parsing. That work stays
 * in adapters so Bluetooth/Broadcast/Network can plug in later with the same contract.
 */
class MultiSourceReadingProcessor(
    private val repo: Repository,
    private val appPrefs: AppPrefs,
    private val multiSourceSettings: MultiSourceSettings,
    private val sourceRegistryService: SourceRegistryService,
    private val stalenessNotifier: PrimarySourceStalenessNotifier
) {
    companion object {
        /**
         * Same-source dedup window.
         * Production requirement: dedupe must be per source, never cross-source.
         */
        private const val PER_SOURCE_DEDUP_WINDOW_MS = 50_000L
    }

    suspend fun process(reading: NormalizedReading): BgReadingEntity? {
        sourceRegistryService.ensureRegistered(reading)

        if (isDuplicateForSameSource(reading)) {
            DebugTrace.t(DebugCategory.DATABASE, "MS-DEDUP", "Dropped duplicate for source=${reading.sourceId} ts=${reading.timestampMs}")
            return null
        }

        val primaryId = multiSourceSettings.primaryOutputSourceId
        val isPrimary = !primaryId.isNullOrBlank() && primaryId == reading.sourceId
        val calibrated = if (isPrimary && appPrefs.calibrationEnabled) {
            CalibrationSettings.apply(reading.valueMgdl, appPrefs)
        } else {
            reading.valueMgdl
        }

        val row = BgReadingEntity(
            sourceId = reading.sourceId,
            timestampMs = reading.timestampMs,
            calculatedValueMgdl = reading.valueMgdl,
            calibratedValueMgdl = calibrated,
            direction = reading.direction,
            rawText = reading.rawText,
            sensorStatus = reading.sensorStatus,
            alertText = reading.alertText
        )
        val inserted = repo.insertReading(row)
        if (inserted > 0L) {
            repo.touchSource(reading.sourceId, reading.timestampMs)
            if (isPrimary) stalenessNotifier.markPrimaryFresh()
            return row
        }
        return null
    }

    /**
     * Per-source dedupe rule.
     *
     * Why this matters:
     * Two different vendors or transport paths may emit equal glucose values at nearly the
     * same time. Those rows must both survive. We only suppress duplicates when the same
     * source channel repeats inside the same short gap.
     */
    private suspend fun isDuplicateForSameSource(reading: NormalizedReading): Boolean {
        val latest = repo.latestReadingForSource(reading.sourceId) ?: return false
        return (reading.timestampMs - latest.timestampMs) in 0 until PER_SOURCE_DEDUP_WINDOW_MS
    }
}
