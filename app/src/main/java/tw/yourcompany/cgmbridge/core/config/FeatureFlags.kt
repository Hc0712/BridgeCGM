package tw.yourcompany.cgmbridge.core.config

import tw.yourcompany.cgmbridge.BuildConfig

/**
 * Centralized feature-flag registry.
 *
 * ┌───────────────────────────────────────────────────────────────┐
 * │  所有開關統一在 gradle.properties 設定：                        │
 * │                                                               │
 * │    cgmbridge.<flag>.debug   = debug build 時的值               │
 * │    cgmbridge.<flag>.release = release build 時的值             │
 * │                                                               │
 * │  改完 gradle.properties → 重新 build → 自動生效                │
 * │  debug / release 的值都在同一個檔案，一目了然。                  │
 * └───────────────────────────────────────────────────────────────┘
 */
object FeatureFlags {

    // ====================================================================
    //  所有值來自 gradle.properties → BuildConfig（編譯期決定，唯讀）
    //
    //  Flag                  Debug    Release     用途
    //  ──────────────────    ─────    ───────     ─────────────────
    //  verboseDump           true     false       詳細 log 開關
    //  chartEnabled          true     true        BG 圖表（預留）
    //  sharingEnabled        false    false       資料分享（預留）
    // ====================================================================

    /** Verbose / detailed logging (controls DebugTrace.v). */
    val verboseDump: Boolean = BuildConfig.VERBOSE_DUMP

    // Per-category debug flags
    val debugParsing: Boolean = BuildConfig.DEBUG_PARSING
    val debugPlotting: Boolean = BuildConfig.DEBUG_PLOTTING
    val debugKeepAlive: Boolean = BuildConfig.DEBUG_KEEPALIVE
    val debugNotification: Boolean = BuildConfig.DEBUG_NOTIFICATION
    val debugDatabase: Boolean = BuildConfig.DEBUG_DATABASE
    // Future categories
    val debugCalibration: Boolean = BuildConfig.DEBUG_CALIBRATION
    val debugNightscout: Boolean = BuildConfig.DEBUG_NIGHTSCOUT
    val debugSetting: Boolean = BuildConfig.DEBUG_SETTING
    val debugStatistics: Boolean = BuildConfig.DEBUG_STATISTICS
    val debugBluetooth: Boolean = BuildConfig.DEBUG_BLUETOOTH
    val debugWifi: Boolean = BuildConfig.DEBUG_WIFI
    val debugAlarm: Boolean = BuildConfig.DEBUG_ALARM

    /** BG chart on main screen (future). */
    val chartEnabled: Boolean = BuildConfig.CHART_ENABLED

    /** WiFi / Bluetooth data sharing (future). */
    val sharingEnabled: Boolean = BuildConfig.SHARING_ENABLED

    /** One-line summary for startup log. */
    fun summary(): String = buildString {
        append("FeatureFlags {")
        append(" verboseDump=$verboseDump")
        append(", debugParsing=$debugParsing")
        append(", debugPlotting=$debugPlotting")
        append(", debugKeepAlive=$debugKeepAlive")
        append(", debugNotification=$debugNotification")
        append(", debugDatabase=$debugDatabase")
        append(", debugCalibration=$debugCalibration")
        append(", debugNightscout=$debugNightscout")
        append(", debugSetting=$debugSetting")
        append(", debugStatistics=$debugStatistics")
        append(", debugBluetooth=$debugBluetooth")
        append(", debugWifi=$debugWifi")
        append(", debugAlarm=$debugAlarm")
        append(", chartEnabled=$chartEnabled")
        append(", sharingEnabled=$sharingEnabled")
        append(" }")
    }
}
