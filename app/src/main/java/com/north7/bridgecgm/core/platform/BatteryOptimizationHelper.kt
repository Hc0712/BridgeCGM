package com.north7.bridgecgm.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper for Android battery optimization behavior.
 *
 * The project uses the official user-facing flow instead of hidden or unsupported APIs.
 */
object BatteryOptimizationHelper {

    /**
     * Returns true when the app is already excluded from battery optimizations.
     */
    fun isIgnoringOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Builds the official Android intent that asks the user to exclude this app from battery optimizations.
     */
    fun buildIgnoreOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
