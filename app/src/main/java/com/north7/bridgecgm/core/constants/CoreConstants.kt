package com.north7.bridgecgm.core.constants

/**
 * Shared non-glucose constants used by multiple features.
 *
 * Why this file exists:
 * - BgReadingImporter.kt uses [CGM_VENDORS] to recognize already-normalized
 *   vendor names before falling back to package-based normalization.
 * - SettingsMenuActivity.kt uses the same vendor list to populate the
 *   primary-input selection dialog.
 *
 * Implementation notes:
 * - The values stay lowercase because importer normalization compares lowercase text.
 * - The list intentionally matches the known vendor names already used elsewhere in the
 *   project such as SupportedPackages.vendorForPackage(...).
 */
object CoreConstants {
    /**
     * Known CGM vendor names supported by the current bridge project.
     *
     * Keeping this as an Array<String> preserves the existing call sites in the patched UI,
     * which pass the value directly into dialog helpers expecting an array.
     */
    val CGM_VENDORS: Array<String> = arrayOf(
        "aidex",
        "ottai",
        "dexcom"
    )
}
