package tw.yourcompany.cgmbridge.core.source

import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity

/**
 * Helper factory for stable source identifiers and registry rows.
 *
 * The production rule is that the user chooses one exact source channel, not only a
 * vendor or transport family. Therefore [sourceId] must be stable, readable, and safe.
 */
object SourceIdentity {
    private const val DEFAULT_COLOR = 0xFF4CAF50.toInt()

    /**
     * Builds a stable multi-source identifier.
     * Example: notification:aidex:default
     */
    fun buildSourceId(transportType: TransportType, vendorName: String, originKey: String?): String {
        val safeVendor = sanitize(vendorName)
        val safeOrigin = sanitize(originKey ?: "default")
        return "${transportType.name.lowercase()}:$safeVendor:$safeOrigin"
    }

    /**
     * Creates a registry row for first-seen sources.
     * The caller may later update the display name or color when richer UI exists.
     */
    fun newEntity(transportType: TransportType, vendorName: String, originKey: String?, nowMs: Long): CgmSourceEntity {
        val sourceId = buildSourceId(transportType, vendorName, originKey)
        return CgmSourceEntity(
            sourceId = sourceId,
            transportType = transportType.name,
            vendorName = vendorName,
            originKey = originKey,
            displayName = buildDisplayName(vendorName, transportType, originKey),
            colorArgb = DEFAULT_COLOR,
            enabled = true,
            visibleOnMainGraph = true,
            lastSeenAtMs = nowMs,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
    }

    fun buildDisplayName(vendorName: String, transportType: TransportType, originKey: String?): String {
        return listOf(vendorName, transportType.name.lowercase().replaceFirstChar { it.uppercase() }, originKey ?: "default")
            .joinToString(" / ")
    }

    private fun sanitize(value: String): String =
        value.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
}
