/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package com.north7.bridgecgm.feature.output.xdrip

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.north7.bridgecgm.core.model.GlucoseSample
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace

/**
 * Sends glucose data to xDrip+ using the Nightscout Emulation receiver.
 *
 * Spec reference (Incoming_Glucose_Broadcast.md):
 * - Action: com.eveningoutpost.dexdrip.NS_EMULATOR
 * - Package: com.eveningoutpost.dexdrip (Android 8+)
 * - Extras:
 *     collection = "entries"
 *     data = JSON array string
 *
 * Verified against xDrip-2025.09.05 NSEmulatorReceiver.java:
 *   getLong("date"), getDouble("sgv"), getString("direction") → slopefromName()
 */
object XDripBroadcastSender {
    private const val ACTION = "com.eveningoutpost.dexdrip.NS_EMULATOR"
    private const val PKG = "com.eveningoutpost.dexdrip"

    fun sendGlucose(context: Context, sample: GlucoseSample) {
        val payload = buildPayload(sample)
        DebugTrace.v(
            DebugCategory.NIGHTSCOUT,
            "BCAST-V-PAYLOAD"
        ) {
            "NS_EMULATOR payload → action=$ACTION pkg=$PKG " +
            "collection=entries data=$payload"
        }
        val intent = Intent(ACTION).setPackage(PKG)
        intent.putExtra("collection", "entries")
        intent.putExtra("data", payload)
        context.sendBroadcast(intent)
    }

    private fun buildPayload(sample: GlucoseSample): String {
        val arr = JSONArray()
        val obj = JSONObject()
        obj.put("type", "sgv")
        obj.put("date", sample.timestampMs)          // Long — NSEmulatorReceiver uses getLong("date")
        obj.put("sgv", sample.valueMgdl.toDouble())   // Double — NSEmulatorReceiver uses getDouble("sgv")
        obj.put("direction", sample.direction)         // String — passed to BgReading.slopefromName()
        arr.put(obj)
        return arr.toString()
    }
}
