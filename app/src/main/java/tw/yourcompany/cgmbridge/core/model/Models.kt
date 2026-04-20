package tw.yourcompany.cgmbridge.core.model

/**
 * Normalized glucose sample that we can store and broadcast.
 *
 * IMPORTANT: xDrip+ Nightscout Emulation receiver expects glucose in mg/dL.
 */
data class GlucoseSample(
    val timestampMs: Long,
    val valueMgdl: Int,
    val direction: String,
    val sourcePackage: String,
    val rawText: String,
    val sensorStatus: String? = null,
    val alertText: String? = null
)
