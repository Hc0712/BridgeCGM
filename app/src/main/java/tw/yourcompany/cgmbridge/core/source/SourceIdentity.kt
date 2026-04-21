package tw.yourcompany.cgmbridge.core.source

import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity

/**
 * Factory and utility helpers for stable source identifiers and first-seen source registry rows.
 *
 * This file was updated for the multi-source bug fix so that:
 * - `sourceId` is always derived from transport + vendor + origin,
 * - each source gets a stable human-readable display name,
 * - each source also gets a stable color so different vendors can be distinguished on the main
 *   graph immediately after the first reading is inserted.
 */
object SourceIdentity {
    /**
     * Small deterministic fallback palette used when a vendor does not have an explicit brand
     * color yet.
     *
     * The palette is intentionally short and high-contrast because the graph only needs to
     * distinguish a few simultaneous sources in Version 1.
     */
    private val FALLBACK_PALETTE = intArrayOf(
        0xFF26A69A.toInt(),
        0xFF5C6BC0.toInt(),
        0xFF7E57C2.toInt(),
        0xFF42A5F5.toInt(),
        0xFFEF5350.toInt(),
        0xFF8D6E63.toInt()
    )

    /**
     * Builds a stable multi-source identifier.
     *
     * Example values:
     * - `notification:aidex:default`
     * - `notification:ottai:default`
     * - `bluetooth:dexcom:ab12`
     *
     * The identifier is lowercase, sanitized, and safe to use as a Room primary key.
     */
    fun buildSourceId(transportType: TransportType, vendorName: String, originKey: String?): String {
        val safeVendor = sanitize(vendorName)
        val safeOrigin = sanitize(originKey ?: "default")
        return "${transportType.name.lowercase()}:$safeVendor:$safeOrigin"
    }

    /**
     * Creates the first registry row for a source that has just been seen.
     *
     * The row stores not only identity fields but also a stable color and display name so the
     * graph layer can immediately render the source without waiting for any later metadata
     * enrichment step.
     */
    fun newEntity(transportType: TransportType, vendorName: String, originKey: String?, nowMs: Long): CgmSourceEntity {
        val sourceId = buildSourceId(transportType, vendorName, originKey)
        return CgmSourceEntity(
            sourceId = sourceId,
            transportType = transportType.name,
            vendorName = vendorName,
            originKey = originKey,
            displayName = buildDisplayName(vendorName, transportType, originKey),
            colorArgb = colorFor(transportType, vendorName, originKey),
            enabled = true,
            visibleOnMainGraph = true,
            lastSeenAtMs = nowMs,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
    }

    /**
     * Builds the human-readable label shown in source-related UI such as graph legends and the
     * future source-management screens.
     */
    fun buildDisplayName(vendorName: String, transportType: TransportType, originKey: String?): String {
        return listOf(
            sanitize(vendorName),
            transportType.name.lowercase().replaceFirstChar { it.uppercase() },
            sanitize(originKey ?: "default")
        ).joinToString(" / ")
    }

    /**
     * Returns a stable graph color for the source.
     *
     * Vendor-specific colors are used when available so the same vendor always looks the same
     * across sessions. Unknown vendors are mapped to a deterministic fallback based on the hashed
     * `sourceId`, which means the same source keeps the same color every time the app starts.
     */
    private fun colorFor(transportType: TransportType, vendorName: String, originKey: String?): Int {
        return when (sanitize(vendorName)) {
            "aidex" -> 0xFF4CAF50.toInt()
            "ottai" -> 0xFF2196F3.toInt()
            "dexcom" -> 0xFFFF9800.toInt()
            else -> {
                val sourceId = buildSourceId(transportType, vendorName, originKey)
                FALLBACK_PALETTE[(sourceId.hashCode() and Int.MAX_VALUE) % FALLBACK_PALETTE.size]
            }
        }
    }

    /**
     * Sanitizes one source-identity component.
     *
     * Rules:
     * - trim whitespace,
     * - force lowercase,
     * - replace non-alphanumeric runs with `_`,
     * - collapse empty results to `default`.
     */
    private fun sanitize(value: String): String =
        value.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
}