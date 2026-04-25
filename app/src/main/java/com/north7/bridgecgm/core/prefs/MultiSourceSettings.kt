package com.north7.bridgecgm.core.prefs

import android.content.Context

/**
 * Persistent settings that describe how the app should behave when multiple input sources are
 * active at the same time.
 *
 * Why this wrapper exists:
 * - it gives the UI, alarm engine, calibration pipeline, and graph layer one shared place to read
 *   the selected primary source;
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
     */
    var primaryInputSourceId: String?
        get() = sp.getString("primaryInputSourceId", null)
        set(value) = sp.edit().putString("primaryInputSourceId", value).apply()

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
