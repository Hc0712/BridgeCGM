package tw.yourcompany.cgmbridge.feature.input.notification

/**
 * Package filter — only AiDEX, OTTAI, and Dexcom are supported.
 *
 * Reference: xDrip-2025.09.05 UiBasedCollector.java static initializer.
 */
object SupportedPackages {

    /** Exact package names whose notifications we intercept. */
    private val packages: Set<String> = setOf(
        // Dexcom G6 (all regions)
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
     * Packages that send BG data in ALL notifications (not only ongoing).
     * Dexcom G6 region variants only use ongoing; the rest process all.
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

    fun isSupported(pkg: String): Boolean = packages.contains(pkg)

    fun shouldProcessAll(pkg: String): Boolean = processAll.contains(pkg)
}
