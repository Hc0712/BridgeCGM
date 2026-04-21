package tw.yourcompany.cgmbridge.feature.input.notification

import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.model.GlucoseSample
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.prefs.MultiSourceSettings
import tw.yourcompany.cgmbridge.feature.alarm.PrimarySourceStalenessNotifier
import tw.yourcompany.cgmbridge.feature.input.pipeline.MultiSourceReadingProcessor
import tw.yourcompany.cgmbridge.feature.input.pipeline.NormalizedReading
import tw.yourcompany.cgmbridge.feature.input.pipeline.SourceRegistryService

/**
 * Notification importer backed by the shared multi-source processor.
 *
 * Compatibility note:
 * Older notification-listener code still expects a sealed [ImportResult] with detailed
 * reasons for skipped inserts. We keep that shape here while delegating actual storage
 * to the new multi-source processor.
 */
class BgReadingImporter(
    private val repo: Repository,
    private val prefs: AppPrefs,
    private val multiSourceSettings: MultiSourceSettings,
    private val stalenessNotifier: PrimarySourceStalenessNotifier
) {
    private val processor = MultiSourceReadingProcessor(
        repo = repo,
        appPrefs = prefs,
        multiSourceSettings = multiSourceSettings,
        sourceRegistryService = SourceRegistryService(repo),
        stalenessNotifier = stalenessNotifier
    )

    sealed class ImportResult {
        data class Inserted(val entity: BgReadingEntity) : ImportResult()
        data class IgnoredDuplicate(val timestampMs: Long) : ImportResult()
        data class IgnoredTooSoon(val timestampMs: Long, val gapMs: Long) : ImportResult()
        data class InvalidTimestamp(val timestampMs: Long) : ImportResult()
        data class InvalidValue(val valueMgdl: Int) : ImportResult()
        data class StatusOnly(val status: String?, val alert: String?) : ImportResult()
    }

    suspend fun import(sample: GlucoseSample): ImportResult {
        if (sample.timestampMs <= 0L) return ImportResult.InvalidTimestamp(sample.timestampMs)
        if (sample.valueMgdl !in 20..600) {
            return if (!sample.sensorStatus.isNullOrBlank() || !sample.alertText.isNullOrBlank()) {
                ImportResult.StatusOnly(sample.sensorStatus, sample.alertText)
            } else {
                ImportResult.InvalidValue(sample.valueMgdl)
            }
        }

        val latest = repo.latestReadingForSource(sample.sourceId)
        if (latest != null) {
            if (latest.timestampMs == sample.timestampMs) return ImportResult.IgnoredDuplicate(sample.timestampMs)
            val gapMs = sample.timestampMs - latest.timestampMs
            if (gapMs in 0 until 50_000L) return ImportResult.IgnoredTooSoon(sample.timestampMs, gapMs)
        }

        val normalized = NormalizedReading(
            sourceId = sample.sourceId,
            transportType = sample.transportType,
            vendorName = sample.vendorName,
            originKey = sample.originKey,
            timestampMs = sample.timestampMs,
            valueMgdl = sample.valueMgdl,
            direction = sample.direction,
            rawText = sample.rawText,
            sensorStatus = sample.sensorStatus,
            alertText = sample.alertText
        )
        val inserted = processor.process(normalized)
        return inserted?.let { ImportResult.Inserted(it) } ?: ImportResult.IgnoredDuplicate(sample.timestampMs)
    }
}
