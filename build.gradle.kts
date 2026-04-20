/**
 * Application module build file.
 *
 * IMPORTANT:
 * - This file keeps the existing dependency set and app structure.
 * - bridge_v3.1 only adds debug logging and code comments; no feature logic is intentionally changed.
 */
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
