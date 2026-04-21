package tw.yourcompany.cgmbridge.feature.input.notification

/**
 * Package filter and vendor resolver for supported notification-based CGM apps.
 *
 * This file now serves two roles:
 * 1) decide whether a notification should be processed at all;
 * 2) map Android package names to normalized vendor names used by the multi-source identity
 *    model.
 *
 * The second role is important for the multi-vendor plotting fix because the graph and alarm
 * layers must work with stable vendor names such as `aidex` and `ottai`, not raw Android package
 * names.
 */
object SupportedPackages {
    /** Exact package names whose notifications we intercept. */
    private val packages: Set<String> = setOf(
        // Dexcom G6 / G7 / related variants
        "com.dexcom.g6",
        "com.dexcom.g6.region1.mmol",
        "com.dexcom.g6.region2.mgdl",
        "com.dexcom.g6.region3.mgdl",
        "com.dexcom.g6.region4.mmol",
        "com.dexcom.g6.region5.mmol",
        "com.dexcom.g6.region6.mgdl",
        "com.dexcom.g6.region7.mmol",
        "com.dexcom.g6.region8.mmol",
        "com.dexcom.g6.region9.mgdl",
        "com.dexcom.g6.region10.mgdl",
        "com.dexcom.g6.region11.mmol",
        "com.dexcom.g7",
        "com.dexcom.dexcomone",
        "com.dexcom.stelo",
        "com.dexcom.d1plus",
        // Microtech / AiDEX
        "com.microtech.aidexx",
        "com.microtech.aidexx.mgdl",
        "com.microtech.aidexx.equil.mmoll",
        // OTTAI
        "com.ottai.seas",
        "com.ottai.tag"
    )

    /**
     * Packages that send usable BG data in non-ongoing notifications as well.
     * Dexcom G6 region variants are kept in the stricter ongoing-only bucket.
     */
    private val processAll: Set<String> = setOf(
        "com.dexcom.dexcomone",
        "com.dexcom.stelo",
        "com.dexcom.d1plus",
        "com.microtech.aidexx",
        "com.microtech.aidexx.mgdl",
        "com.microtech.aidexx.equil.mmoll",
        "com.ottai.seas",
        "com.ottai.tag"
    )

    /** Returns `true` when the package is on the supported CGM-app allow-list. */
    fun isSupported(pkg: String): Boolean = packages.contains(pkg)

    /**
     * Returns `true` when all notifications from the package may be processed, not only ongoing
     * notifications.
     */
    fun shouldProcessAll(pkg: String): Boolean = processAll.contains(pkg)

    /**
     * Maps an Android package name to the normalized vendor name used throughout the multi-source
     * architecture.
     *
     * This method intentionally hides package-name details from the rest of the app so that:
     * - sourceId values stay stable even if a vendor ships multiple package variants;
     * - the Settings screen can show simple vendor names (`aidex`, `ottai`, `dexcom`);
     * - future non-notification transports can reuse the same vendor name vocabulary.
     */
    fun vendorForPackage(pkg: String): String = when {
        pkg.startsWith("com.microtech.aidexx") -> "aidex"
        pkg.startsWith("com.ottai.") -> "ottai"
        pkg.startsWith("com.dexcom.") -> "dexcom"
        else -> pkg.substringAfterLast('.').trim().lowercase().ifBlank { "unknown" }
    }
}