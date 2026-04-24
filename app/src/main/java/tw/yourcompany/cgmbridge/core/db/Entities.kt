package tw.yourcompany.cgmbridge.core.db

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Source registry for the clean Version 1 multi-source-first architecture.
 *
 * This project is now treated as a brand new schema baseline:
 * - no old single-source rows are preserved
 * - no legacy mapping table is needed
 * - every source channel is explicitly registered here
 *
 * Why this table exists:
 * - graph labels and colors must be stable over long-running use
 * - the user must be able to select one exact primary output source
 * - stale/disconnection checks need a reliable last-seen timestamp per source
 */
@Entity(
    tableName = "cgm_source",
    indices = [
        Index(value = ["transportType"]),
        Index(value = ["vendorName"]),
        Index(value = ["lastSeenAtMs"])
    ]
)
data class CgmSourceEntity(
    @PrimaryKey val sourceId: String,
    val transportType: String,
    val vendorName: String,
    val originKey: String?,
    val displayName: String,
    val colorArgb: Int,
    val enabled: Boolean = true,
    val visibleOnMainGraph: Boolean = true,
    val lastSeenAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Clean Version 1 multi-source glucose row.
 *
 * Important production rules:
 * - [id] is the real primary key
 * - (sourceId, timestampMs) is the business uniqueness rule
 * - different source channels may store the same timestamp without collision
 * - both raw and calibrated values are stored so the main graph can display
 *   the primary raw line and, when enabled, the calibrated primary overlay
 */
@Entity(
    tableName = "bg_reading",
    indices = [
        Index(value = ["sourceId", "timestampMs"], unique = true),
        Index(value = ["timestampMs"]),
        Index(value = ["sourceId"])
    ]
)
data class BgReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val timestampMs: Long,
    val rawValueMgdl: Int,
    val calibratedValueMgdl: Int,
    val direction: String,
    val rawText: String,
    val sensorStatus: String?,
    val alertText: String?
) {
    /** Compatibility alias used by older callers during transition. */
    @Ignore val sourcePackage: String = sourceId
}

@Entity(tableName = "event_log", indices = [Index(value = ["timestampMs"])])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String
)
