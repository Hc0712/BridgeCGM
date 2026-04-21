package tw.yourcompany.cgmbridge.core.prefs

import android.content.Context

/**
 * Settings wrapper for clean Version 1 multi-source rules.
 */
class MultiSourceSettings(context: Context) {
    private val sp = context.getSharedPreferences("bridge_multi_source_prefs", Context.MODE_PRIVATE)

    /**
     * One exact source channel selected by the user.
     * Version 1 binds this to:
     * - calibration target
     * - alarm source
     * - mini graph source
     */
    var primaryOutputSourceId: String?
        get() = sp.getString("primaryOutputSourceId", null)
        set(value) = sp.edit().putString("primaryOutputSourceId", value).apply()

    /** Sticky flag used to avoid repeated stale-source notification spam. */
    var primaryStaleNotificationActive: Boolean
        get() = sp.getBoolean("primaryStaleNotificationActive", false)
        set(value) = sp.edit().putBoolean("primaryStaleNotificationActive", value).apply()

    /** Timestamp of the last stale-primary warning notification. */
    var lastPrimaryStaleNotificationAtMs: Long
        get() = sp.getLong("lastPrimaryStaleNotificationAtMs", 0L)
        set(value) = sp.edit().putLong("lastPrimaryStaleNotificationAtMs", value).apply()
}
