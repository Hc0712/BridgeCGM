package tw.yourcompany.cgmbridge.core.prefs

import android.content.Context

/**
 * Persistent settings that describe how the app should behave when multiple input sources are
 * active at the same time.
 *
 * Why this wrapper exists:
 * - it gives the UI, alarm engine, calibration pipeline, and graph layer one shared place to read
 *   the selected primary source;
 * - it preserves backward compatibility with the older `primaryOutputSourceId` key while moving
 *   new code toward the product-spec name `primaryInputSourceId`;
 * - it keeps the stale-primary flags next to the primary-source selection because those values
 *   belong to the same business concept.
 */
class MultiSourceSettings(context: Context) {
    private val sp = context.getSharedPreferences("bridge_multi_source_prefs", Context.MODE_PRIVATE)

    /**
     * The exact source channel chosen by the user as the primary input source.
     *
     * The value is a stable `sourceId` such as `notification:aidex:default`.
     * This primary source is used for:
     * - calibration target selection,
     * - mini graph filtering,
     * - alarm evaluation,
     * - stale-primary monitoring.
     *
     * Backward compatibility rule:
     * Older builds stored the same concept under `primaryOutputSourceId`.
     * The getter falls back to the old key so existing installations keep working after the
     * migration without requiring the user to select the source again.
     */
    var primaryInputSourceId: String?
        get() = sp.getString("primaryInputSourceId", sp.getString("primaryOutputSourceId", null))
        set(value) = sp.edit()
            .putString("primaryInputSourceId", value)
            .putString("primaryOutputSourceId", value)
            .apply()

    /**
     * Compatibility alias kept for legacy callers that still use the older property name.
     * New code should prefer [primaryInputSourceId].
     */
    var primaryOutputSourceId: String?
        get() = primaryInputSourceId
        set(value) {
            primaryInputSourceId = value
        }

    /**
     * Sticky notification state used to avoid spamming the user with repeated stale-source
     * notifications while the same stale episode is still ongoing.
     */
    var primaryStaleNotificationActive: Boolean
        get() = sp.getBoolean("primaryStaleNotificationActive", false)
        set(value) = sp.edit().putBoolean("primaryStaleNotificationActive", value).apply()

    /**
     * Timestamp of the last stale-primary warning notification.
     * This is useful for diagnostics and future throttling rules.
     */
    var lastPrimaryStaleNotificationAtMs: Long
        get() = sp.getLong("lastPrimaryStaleNotificationAtMs", 0L)
        set(value) = sp.edit().putLong("lastPrimaryStaleNotificationAtMs", value).apply()
}
