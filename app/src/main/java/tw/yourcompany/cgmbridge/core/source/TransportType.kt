package tw.yourcompany.cgmbridge.core.source

/**
 * Supported source transport categories for the production multi-source architecture.
 * Notification is implemented now. The other entries are reserved so the normalized
 * pipeline can be extended later without changing the identity model.
 */
enum class TransportType {
    NETWORK,
    BLUETOOTH,
    BROADCAST,
    NOTIFICATION
}
