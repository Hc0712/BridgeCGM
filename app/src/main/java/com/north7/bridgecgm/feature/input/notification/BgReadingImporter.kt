package com.north7.bridgecgm.feature.input.notification

import com.north7.bridgecgm.core.constants.CoreConstants
import com.north7.bridgecgm.core.data.Repository
import com.north7.bridgecgm.core.db.BgReadingEntity
import com.north7.bridgecgm.core.model.GlucoseSample
import com.north7.bridgecgm.core.prefs.AppPrefs
import com.north7.bridgecgm.core.prefs.MultiSourceSettings
import com.north7.bridgecgm.core.source.SourceIdentity
import com.north7.bridgecgm.core.source.TransportType
import com.north7.bridgecgm.feature.alarm.PrimarySourceStalenessNotifier
import com.north7.bridgecgm.feature.input.pipeline.MultiSourceReadingProcessor
import com.north7.bridgecgm.feature.input.pipeline.NormalizedReading
import com.north7.bridgecgm.feature.input.pipeline.SourceRegistryService

/**
 * Notification importer backed by the shared multi-source processor.
 *
 * The importer performs a small amount of transport-specific normalization before the reading
 * enters the shared pipeline. That is the right place for the source-identity fix because this
 * class still receives samples from older parser code paths and therefore needs one final
 * defense-in-depth check.
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

    /** Result shape kept for compatibility with older notification-listener callers. */
    sealed class ImportResult {
        data class Inserted(val entity: BgReadingEntity) : ImportResult()
        data class IgnoredDuplicate(val timestampMs: Long) : ImportResult()
        data class IgnoredTooSoon(val timestampMs: Long, val gapMs: Long) : ImportResult()
        data class InvalidTimestamp(val timestampMs: Long) : ImportResult()
        data class InvalidValue(val valueMgdl: Int) : ImportResult()
        data class StatusOnly(val status: String?, val alert: String?) : ImportResult()
    }

    /**
     * Validates, normalizes, and imports one notification-derived sample.
     *
     * The method deliberately normalizes the source identity again even though the parser now does
     * the same thing. This extra step makes the importer robust against any future caller that
     * accidentally constructs a `GlucoseSample` via the old legacy constructor.
     */
    suspend fun import(sample: GlucoseSample): ImportResult {
        if (sample.timestampMs <= 0L) return ImportResult.InvalidTimestamp(sample.timestampMs)
        if (sample.valueMgdl !in 20..600) {
            return if (!sample.sensorStatus.isNullOrBlank() || !sample.alertText.isNullOrBlank()) {
                ImportResult.StatusOnly(sample.sensorStatus, sample.alertText)
            } else {
                ImportResult.InvalidValue(sample.valueMgdl)
            }
        }

        val transport = normalizeTransport(sample.transportType)
        val vendor = normalizeVendor(sample)
        val sourceId = SourceIdentity.buildSourceId(transport, vendor, sample.originKey)

        val latest = repo.latestReadingForSource(sourceId)
        if (latest != null) {
            if (latest.timestampMs == sample.timestampMs) return ImportResult.IgnoredDuplicate(sample.timestampMs)
            val gapMs = sample.timestampMs - latest.timestampMs
            if (gapMs in 0 until 50_000L) return ImportResult.IgnoredTooSoon(sample.timestampMs, gapMs)
        }

        val normalized = NormalizedReading(
            sourceId = sourceId,
            transportType = transport.name,
            vendorName = vendor,
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

    /**
     * Normalizes the vendor name part of the source identity.
     *
     * Preference order:
     * 1. already-normalized `sample.vendorName` if it is one of the known vendors,
     * 2. package-to-vendor mapping for legacy samples that still carry a package-like value,
     * 3. sanitized raw value or `unknown` as a last resort.
     */
    private fun normalizeVendor(sample: GlucoseSample): String {
        val direct = sample.vendorName.trim().lowercase()
        return when {
            CoreConstants.CGM_VENDORS.contains(direct) -> direct
            sample.originKey?.startsWith("com.") == true -> SupportedPackages.vendorForPackage(sample.originKey.orEmpty())
            else -> direct.ifBlank { "unknown" }
        }
    }

    /** Converts any external transport string into the normalized transport enum. */
    private fun normalizeTransport(raw: String?): TransportType = TransportType.fromUserValue(raw)
}
