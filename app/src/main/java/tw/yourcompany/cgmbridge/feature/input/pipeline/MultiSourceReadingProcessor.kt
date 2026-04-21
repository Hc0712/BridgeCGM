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
 * Shared production processor for normalized readings from any transport.
 *
 * This class now contains explicit multi-source comments because it is the central place where the
 * product rules meet the persisted data model:
 * - dedupe is per source only;
 * - calibration is primary-source-only;
 * - a fresh primary reading clears the stale-primary episode;
 * - all sources, including non-primary ones, are still stored and remain eligible for the main
 *   graph.
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
         *
         * This window must never be applied across different sources. Two vendors may publish a
         * value at nearly the same time and both readings must survive so the main graph can
         * compare them side by side.
         */
        private const val PER_SOURCE_DEDUP_WINDOW_MS = 50_000L
    }

    /**
     * Processes one already-normalized reading.
     *
     * Steps:
     * 1. ensure the source registry row exists;
     * 2. suppress only same-source rapid duplicates;
     * 3. apply calibration only when the reading belongs to the selected primary input source;
     * 4. store the row;
     * 5. mark the primary source fresh so stale-source notifications can be cleared.
     */
    suspend fun process(reading: NormalizedReading): BgReadingEntity? {
        sourceRegistryService.ensureRegistered(reading)

        if (isDuplicateForSameSource(reading)) {
            DebugTrace.t(DebugCategory.DATABASE, "MS-DEDUP", "Dropped duplicate for source=${reading.sourceId} ts=${reading.timestampMs}")
            return null
        }

        val primaryId = multiSourceSettings.primaryInputSourceId
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
     * This function purposely looks up only the latest row for the same `sourceId`. Using a global
     * latest reading would reintroduce the original bug where one vendor could suppress another
     * vendor's valid reading simply because both arrived close together.
     */
    private suspend fun isDuplicateForSameSource(reading: NormalizedReading): Boolean {
        val latest = repo.latestReadingForSource(reading.sourceId) ?: return false
        return (reading.timestampMs - latest.timestampMs) in 0 until PER_SOURCE_DEDUP_WINDOW_MS
    }
}