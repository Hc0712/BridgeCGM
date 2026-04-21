package tw.yourcompany.cgmbridge.core.source

/**
 * Supported transport families for the multi-source identity model.
 *
 * Important note:
 * Only [NOTIFICATION] is implemented today. The remaining values are intentionally present so the
 * app can reserve stable source identifiers for future Bluetooth / Wi-Fi / Nightscout work
 * without changing the persisted identity format later.
 */
enum class TransportType {
    NOTIFICATION,
    BLUETOOTH,
    WIFI,
    NIGHTSCOUT,
    NETWORK,
    BROADCAST;

    companion object {
        /**
         * Converts a user-facing or externally supplied transport string into the normalized enum
         * value used by the source identity layer.
         *
         * The helper is intentionally permissive because values may come from:
         * - UI labels (for example `WIFI` or `NightScout`),
         * - older code paths (for example `network` or `broadcast`),
         * - future adapters that already normalize to uppercase enum names.
         *
         * Unknown values fall back to [NOTIFICATION] because that is the only currently
         * implemented transport and therefore the safest default for existing builds.
         */
        fun fromUserValue(raw: String?): TransportType = when (raw?.trim()?.lowercase()) {
            "notification" -> NOTIFICATION
            "bluetooth" -> BLUETOOTH
            "wifi" -> WIFI
            "nightscout" -> NIGHTSCOUT
            "network" -> NETWORK
            "broadcast" -> BROADCAST
            else -> NOTIFICATION
        }
    }
}
