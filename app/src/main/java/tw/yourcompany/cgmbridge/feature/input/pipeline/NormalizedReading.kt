package tw.yourcompany.cgmbridge.feature.input.pipeline

/**
 * Transport-agnostic reading contract used by the shared multi-source processor.
 *
 * All adapters (Notification now, Bluetooth/Broadcast/Network later) should output
 * this shape so the production pipeline can apply one consistent set of rules.
 */
data class NormalizedReading(
    val sourceId: String,
    val transportType: String,
    val vendorName: String,
    val originKey: String?,
    val timestampMs: Long,
    val valueMgdl: Int,
    val direction: String,
    val rawText: String,
    val sensorStatus: String?,
    val alertText: String?
)
