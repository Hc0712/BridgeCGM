package tw.yourcompany.cgmbridge.feature.statistics

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import tw.yourcompany.cgmbridge.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chart marker that shows a crosshair popup with glucose value, time, and date.
 * Similar to AiDEX app's touch-to-inspect behavior.
 *
 * [xReferenceMs] must be set to the same `dayStartMs` used by [ChartHelper]
 * so that relative x-values can be converted back to absolute epoch timestamps.
 */
class GlucoseMarkerView(
    context: Context,
    private val outputUnit: String
) : MarkerView(context, R.layout.marker_view_glucose) {

    // UI constants
    private companion object {
        const val MS_PER_MIN = 60_000L
        const val COLOR_LOW = 0xFFFF5252.toInt()      // Red
        const val COLOR_IN_RANGE = 0xFF4CAF50.toInt() // Green
        const val COLOR_HIGH = 0xFFFFC107.toInt()     // Amber/Yellow
        const val VALUE_TEXT_SIZE = 16f
        const val OFFSET_Y = 10f
    }

    private val tvValue: TextView = findViewById(R.id.markerValue)
    private val tvTime: TextView = findViewById(R.id.markerTime)
    private val tvDate: TextView = findViewById(R.id.markerDate)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MM/dd", Locale.getDefault())

    /**
     * Epoch-ms reference origin for the x-axis (typically midnight of the viewed day).
     * Set by [ChartHelper] before each render.
     */
    var xReferenceMs: Long = 0L

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e == null) return

        // Glucose value (stored as mg/dL Int in Entry.data)
        val mgdl = e.data as? Int ?: e.y.toInt()
        tvValue.text = if (outputUnit == "mmol") {
            String.format(Locale.US, "%.1f mmol/L", mgdl / tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.MMOL_FACTOR)
        } else {
            "$mgdl mg/dL"
        }
        tvValue.textSize = VALUE_TEXT_SIZE

        // Color by range
        val color = when {
            mgdl < tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.LOW_DEFAULT_MGDL -> COLOR_LOW
            mgdl <= tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.HIGH_DEFAULT_MGDL -> COLOR_IN_RANGE
            else        -> COLOR_HIGH
        }
        tvValue.setTextColor(color)

        // Time & date — reconstruct absolute epoch-ms from relative x-value
        val timestampMs = (e.x.toDouble() * MS_PER_MIN + xReferenceMs).toLong()
        val date = Date(timestampMs)
        tvTime.text = timeFmt.format(date)
        tvDate.text = dateFmt.format(date)

        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        // Center horizontally, place above the point
        return MPPointF(-(width / 2f), -height.toFloat() - OFFSET_Y)
    }
}
