package tw.yourcompany.cgmbridge.core.model

/**
 * Source-aware normalized sample used by the clean multi-source-first project.
 *
 * Compatibility note:
 * This data class also exposes a legacy convenience constructor and [sourcePackage]
 * alias so older parser / notification code can still compile while the project is
 * being fully migrated to the explicit sourceId / transportType / vendorName model.
 */
data class GlucoseSample(
    val sourceId: String,
    val transportType: String,
    val vendorName: String,
    val originKey: String? = null,
    val timestampMs: Long,
    val valueMgdl: Int,
    val direction: String,
    val rawText: String,
    val sensorStatus: String? = null,
    val alertText: String? = null
) {
    /** Legacy alias used by older notification code paths. */
    val sourcePackage: String get() = sourceId

    /**
     * Legacy constructor kept so older parser code that still builds
     * `GlucoseSample(ts, mgdl, dir, pkg, text, status, alert)` can compile.
     */
    constructor(
        timestampMs: Long,
        valueMgdl: Int,
        direction: String,
        sourcePackage: String,
        rawText: String,
        sensorStatus: String? = null,
        alertText: String? = null
    ) : this(
        sourceId = sourcePackage,
        transportType = "NOTIFICATION",
        vendorName = sourcePackage,
        originKey = sourcePackage,
        timestampMs = timestampMs,
        valueMgdl = valueMgdl,
        direction = direction,
        rawText = rawText,
        sensorStatus = sensorStatus,
        alertText = alertText
    )
}
