package tw.yourcompany.cgmbridge.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BG readings stored in local Room DB.
 *
 * Indexes are added for long-term performance.
 */
@Entity(
    tableName = "bg_reading",
    indices = [Index(value = ["timestampMs"], unique = true), Index(value = ["sourcePackage"])]
)
data class BgReadingEntity(
    @PrimaryKey val timestampMs: Long,
    val calculatedValueMgdl: Int,
    val CalibratedValueMgdl: Int, // Calibrated Glucose value in mg/dL
    val direction: String,
    val sourcePackage: String,
    val rawText: String,
    val sensorStatus: String?,
    val alertText: String?
)

/**
 * Debug/audit events, used to troubleshoot missing notifications and parsing.
 */
@Entity(tableName = "event_log", indices = [Index(value = ["timestampMs"])])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val level: String, // D/W/E
    val tag: String,
    val message: String
)
