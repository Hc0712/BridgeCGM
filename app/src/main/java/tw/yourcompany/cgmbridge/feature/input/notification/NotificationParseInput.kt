package tw.yourcompany.cgmbridge.feature.input.notification

/**
 * Immutable snapshot of a single notification, passed into the parser.
 *
 * [contentViewTexts] — text extracted from RemoteViews (primary data path).
 * [isOngoing] — true for persistent CGM value notifications.
 */
data class NotificationParseInput(
    val sourcePackage: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val textLines: String?,
    val tickerText: String?,
    val postTimeMs: Long,
    val contentViewTexts: List<String> = emptyList(),
    val isOngoing: Boolean = false
)

