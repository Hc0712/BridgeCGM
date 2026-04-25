package com.north7.bridgecgm.feature.input.notification

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace

/**
 * Extracts a timestamp from notification text.
 *
 * Requirement C:
 * - Prefer "time string inside notification".
 * - Fallback to notification posted time if time string is missing.
 *
 * Supported patterns (examples):
 * - 14:05
 * - 14:05:33
 * - 2026-04-09 14:05
 * - 04/09 14:05 (assumes current year)
 *
 * Verbose log tag TS-EXTRACT is controlled by bridgecgm.verboseDump.
 */
object NotificationTimestampExtractor {

    private val reTime = Regex("(\\b[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?\\b")
    private val reFull1 = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\s+[0-2]\\d:[0-5]\\d\\b")
    private val reFull2 = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\s+[0-2]\\d:[0-5]\\d:[0-5]\\d\\b")

    /**
     * Returns a best-effort epoch timestamp.
     * If a recognizable time string is found in the notification, that value is preferred.
     * Otherwise the notification post time is used.
     */
    fun extractEpochMs(text: String?, fallbackEpochMs: Long): Long {
        if (text.isNullOrBlank()) {
            DebugTrace.v(
                DebugCategory.NOTIFICATION,
                "TS-EXTRACT"
            ) { "text=null/blank → using fallback=$fallbackEpochMs" }
            return fallbackEpochMs
        }
        // Use legacy date/time parsing for minSdk 24 compatibility
        val locale = Locale.getDefault()
        val cal = Calendar.getInstance()

        reFull2.find(text)?.value?.let {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale)
            val date = sdf.parse(it)
            val ms = date?.time ?: fallbackEpochMs
            DebugTrace.v(
                DebugCategory.NOTIFICATION,
                "TS-EXTRACT"
            ) { "Matched fullDateTime2=[$it] → epochMs=$ms" }
            return ms
        }
        reFull1.find(text)?.value?.let {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
            val date = sdf.parse(it)
            val ms = date?.time ?: fallbackEpochMs
            DebugTrace.v(
                DebugCategory.NOTIFICATION,
                "TS-EXTRACT"
            ) { "Matched fullDateTime1=[$it] → epochMs=$ms" }
            return ms
        }
        reTime.find(text)?.value?.let { token ->
            val now = cal.clone() as Calendar
            val parts = token.split(":")
            now.set(Calendar.SECOND, if (parts.size == 3) parts[2].toIntOrNull() ?: 0 else 0)
            now.set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
            now.set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
            var epoch = now.timeInMillis
            val adjusted = if (epoch - fallbackEpochMs > 2 * 60 * 1000L) {
                now.add(Calendar.DAY_OF_YEAR, -1)
                epoch = now.timeInMillis
                true
            } else false
            DebugTrace.v(
                DebugCategory.NOTIFICATION,
                "TS-EXTRACT"
            ) {
                "Matched timeToken=[$token] dayAdjusted=$adjusted → epochMs=$epoch (fallback=$fallbackEpochMs)"
            }
            return epoch
        }
        DebugTrace.v(
            DebugCategory.NOTIFICATION,
            "TS-EXTRACT"
        ) { "No time pattern in text=[$text] → using fallback=$fallbackEpochMs" }
        return fallbackEpochMs
    }
}
