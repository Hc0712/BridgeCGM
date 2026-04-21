package tw.yourcompany.cgmbridge.feature.statistics

import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Configures and renders dual-panel glucose charts (detail + overview).
 *
 * X-axis labels are always snapped to round hours (e.g. 00:00, 04:00, …).
 * Y-axis uses dynamic range with high/low limit lines.
 *
 * ### Float precision note
 *
 * MPAndroidChart `Entry` stores x/y as `float` (IEEE 754 single, 23-bit mantissa).
 * Epoch-milliseconds in 2026 (~1.776 × 10¹²) have a ULP of ~262 144 ms ≈ 4.37 min,
 * which means consecutive 1-minute readings collapse to the **same** float value or
 * jump by ~4 min — producing duplicate dots and visual gaps.
 *
 * **Fix:** All x-values are relative to `dayStartMs` (midnight of the viewed day).
 * The resulting range 0–1440 minutes has sub-millisecond Float precision.
 */
object ChartHelper {

    // Chart timing constants
    private const val MS_PER_MIN = 60_000L
    private const val HOUR_MS = 3_600_000L

    // Chart color constants
    private const val COLOR_IN_RANGE = 0xFF4CAF50.toInt()      // Green
    private const val COLOR_HIGH = 0xFFFFC107.toInt()         // Amber/Yellow
    private const val COLOR_LOW = 0xFFFF5252.toInt()          // Red
    private const val COLOR_URGENT = 0xFFD50000.toInt()       // Dark Red
    private const val COLOR_LINE = 0x55888888                 // Faint Gray Connector
    private const val COLOR_GRID = 0x33FFFFFF                 // Subtle Grid
    private const val COLOR_AXIS_TEXT = 0xFFAAAAAA.toInt()    // Axis Text
    private const val COLOR_RAW_SERIES = 0xFF455A64.toInt()   // Raw Series
    private const val COLOR_CALIBRATED_SERIES = 0xFF90A4AE.toInt() // Calibrated Series
    private const val COLOR_BG_MAIN = 0xFF212121.toInt()      // Main chart background
    private const val COLOR_BG_OVERVIEW = 0xFF1A1A1A.toInt()  // Overview chart background
    private const val COLOR_CIRCLE_HOLE = 0xFF212121.toInt()  // Circle hole color
    private const val COLOR_HIGHLIGHT = 0xAAFFFFFF.toInt()    // Highlight color

    // Chart dimension constants
    private const val LINE_WIDTH_MAIN = 1.8f
    private const val LINE_WIDTH_CALIBRATED = 2.2f
    private const val LINE_WIDTH_CALIBRATION_OVERLAY = 1.4f
    private const val LINE_WIDTH_OVERVIEW = 0.8f
    private const val CIRCLE_RADIUS_MAIN = 3.6f
    private const val CIRCLE_RADIUS_CALIBRATION_OVERLAY = 2.8f
    private const val CIRCLE_RADIUS_OVERVIEW = 2f
    private const val CIRCLE_RADIUS_CALIBRATED = 4.2f
    private const val CIRCLE_HOLE_RADIUS_MAIN = 1f
    private const val CIRCLE_HOLE_RADIUS_OVERVIEW = 0.6f
    private const val CIRCLE_HOLE_RADIUS_CALIBRATED = 1.1f
    private const val LIMIT_LINE_WIDTH = 1.0f
    private const val LIMIT_LINE_TEXT_SIZE = 8f
    private const val LIMIT_LINE_DASH_LENGTH = 10f
    private const val LIMIT_LINE_DASH_SPACE = 8f
    private const val LEGEND_TEXT_SIZE = 10f
    private const val LEGEND_FORM_SIZE = 16f
    private const val LEGEND_X_ENTRY_SPACE = 8f
    private const val LEGEND_Y_ENTRY_SPACE = 6f
    private const val LEGEND_X_OFFSET = 8f
    private const val LEGEND_Y_OFFSET = 8f
    private const val HIGHLIGHT_LINE_WIDTH = 0.8f

    // xDrip defaults
    private const val LOW_MARK_MGDL = tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.LOW_DEFAULT_MGDL
    private const val HIGH_MARK_MGDL = tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.HIGH_DEFAULT_MGDL
    private const val DEFAULT_MIN_MGDL = tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.DEFAULT_MIN_MGDL
    private const val DEFAULT_MAX_MGDL = tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.DEFAULT_MAX_MGDL
    private const val MMOL_FACTOR = tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.MMOL_FACTOR

    /** Debug time format for CHART-DOT verbose logs. */
    private val debugTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ─── Float-safe x-axis conversion ────────────────────────────────────

    /**
     * Convert an absolute epoch-ms timestamp to a Float x-axis value,
     * expressed as **minutes since [referenceMs]** (typically dayStartMs / midnight).
     *
     * By subtracting the reference BEFORE converting to Float, the result
     * stays in the 0–1440 range where Float has full precision.
     */
    private fun toXValue(timestampMs: Long, referenceMs: Long): Float =
        ((timestampMs - referenceMs).toDouble() / MS_PER_MIN).toFloat()

    // ─── Init ────────────────────────────────────────────────────────────

    /** One-time setup for the detail chart (zoomable, interactive). */
    fun initDetail(chart: LineChart, outputUnit: String) {
        chart.setBackgroundColor(COLOR_BG_MAIN)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)
        chart.setNoDataText("")
        chart.setNoDataTextColor(COLOR_AXIS_TEXT)
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.isDragEnabled = true
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = false
        chart.isHighlightPerTapEnabled = true
        chart.isHighlightPerDragEnabled = true

        // Crosshair marker (like AiDEX: tap to show value + time + date)
        //
        // IMPORTANT:
        // The marker popup reconstructs the absolute timestamp from the tapped
        // Entry.x value. Because Entry.x is stored as "minutes since midnight"
        // for Float precision safety, the marker also needs the matching
        // midnight reference for the currently displayed day.
        //
        // That reference cannot be finalized here in initDetail(), because the
        // user may later switch between Today / older days. The actual
        // day-specific reference is therefore synchronized during each render in
        // [syncMarkerReference].
        chart.marker = GlucoseMarkerView(chart.context, outputUnit)

        setupAxes(chart, outputUnit, isOverview = false)
    }

    /** One-time setup for the overview chart (non-interactive, always 24h). */
    fun initOverview(chart: LineChart, outputUnit: String) {
        chart.setBackgroundColor(COLOR_BG_OVERVIEW)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)
        chart.setNoDataText("")
        chart.setTouchEnabled(false)
        chart.setPinchZoom(false)
        chart.isDragEnabled = false

        setupAxes(chart, outputUnit, isOverview = true)
    }

    private fun setupAxes(chart: LineChart, outputUnit: String, isOverview: Boolean) {
        val isMmol = outputUnit == "mmol"
        val yMin = if (isMmol) (DEFAULT_MIN_MGDL / MMOL_FACTOR).toFloat() else DEFAULT_MIN_MGDL.toFloat()
        val yMax = if (isMmol) (DEFAULT_MAX_MGDL / MMOL_FACTOR).toFloat() else DEFAULT_MAX_MGDL.toFloat()

        // Right Y axis (like xDrip)
        chart.axisRight.apply {
            /**
             * Keep the right axis enabled for both charts.
             * - The bridge graph draws threshold lines on the right-side Y axis.
             */
            isEnabled = true
            setDrawLabels(!isOverview)
            setDrawAxisLine(false)
            axisMinimum = yMin
            axisMaximum = yMax
            granularity = if (isMmol) 2f else 50f
            setDrawGridLines(!isOverview)
            gridColor = COLOR_GRID
            textColor = COLOR_AXIS_TEXT
            textSize = LEGEND_TEXT_SIZE
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    if (isMmol) String.format(Locale.US, "%.1f", value)
                    else value.toInt().toString()
            }


            removeAllLimitLines()
        }
        chart.axisLeft.isEnabled = false

        // X axis
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(!isOverview)
            gridColor = COLOR_GRID
            textColor = COLOR_AXIS_TEXT
            textSize = if (isOverview) 9f else LEGEND_TEXT_SIZE
            setDrawAxisLine(false)
        }
    }

    // ─── Render ──────────────────────────────────────────────────────────

    /**
     * Renders the detail chart.
     * @param dayStartMs  midnight (00:00) of the selected day
     * @param isToday     true if viewing today (right edge = now, not 24:00)
     */
    fun renderDetail(
        chart: LineChart,
        list: List<BgReadingEntity>,
        outputUnit: String,
        dayStartMs: Long,
        timeWindowHours: Int,
        isToday: Boolean,
        showCalibrationOverlay: Boolean,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double
    ) {
        configureXAxisDetail(chart, dayStartMs, timeWindowHours, isToday)
        renderChart(chart, list, outputUnit, isOverview = false, dayStartMs = dayStartMs, showCalibrationOverlay = showCalibrationOverlay)
        updateYRange(chart = chart, list = list, outputUnit = outputUnit, showCalibrationOverlay = showCalibrationOverlay, lowThresholdMgdl = lowThresholdMgdl, highThresholdMgdl = highThresholdMgdl)
    }

    /**
     * Renders the overview chart (always full day).
     * @param dayStartMs  midnight (00:00) of the selected day
     */
    fun renderOverview(
        chart: LineChart,
        all24h: List<BgReadingEntity>,
        outputUnit: String,
        dayStartMs: Long,
        highlightWindowHours: Int,
        isToday: Boolean,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double
    ) {
        configureXAxisOverview(chart, dayStartMs)
        renderChart(chart, all24h, outputUnit, isOverview = true, dayStartMs = dayStartMs, highlightWindowHours = highlightWindowHours, isToday = isToday, showCalibrationOverlay = false)
        updateYRange(chart = chart, list = all24h, outputUnit = outputUnit, showCalibrationOverlay = false, lowThresholdMgdl = lowThresholdMgdl, highThresholdMgdl = highThresholdMgdl)
    }

    /**
     * Core render logic — shared by detail & overview.
     *
     * @param dayStartMs  midnight epoch-ms of the viewed day; used as the x-axis
     *                    reference so that Float values stay in the 0–1440 range
     *                    (see class-level Float precision note).
     */
    private fun renderChart(
        chart: LineChart,
        list: List<BgReadingEntity>,
        outputUnit: String,
        isOverview: Boolean,
        dayStartMs: Long,
        highlightWindowHours: Int = 24,
        isToday: Boolean = true,
        showCalibrationOverlay: Boolean = false
    ) {
        if (list.isEmpty()) {
            chart.clear()
            chart.legend.isEnabled = !isOverview
            chart.invalidate()
            return
        }

        val isMmol = outputUnit == "mmol"
        val ordered = list.sortedBy { it.timestampMs }

        fun rawY(row: BgReadingEntity): Float {
            val value = row.calculatedValueMgdl.toFloat()
            return if (isMmol) value / MMOL_FACTOR.toFloat() else value
        }

        fun calibratedY(row: BgReadingEntity): Float {
            val value = row.calibratedValueMgdl.toFloat()
            return if (isMmol) value / MMOL_FACTOR.toFloat() else value
        }

        val rawEntries = ordered.map { row ->
            Entry(toXValue(row.timestampMs, dayStartMs), rawY(row)).apply { data = row.calculatedValueMgdl }
        }

        val rawDataSet = LineDataSet(rawEntries, chart.context.getString(R.string.legend_raw_data)).apply {
            color = COLOR_RAW_SERIES
            lineWidth = if (showCalibrationOverlay && !isOverview) LINE_WIDTH_CALIBRATION_OVERLAY else if (isOverview) LINE_WIDTH_OVERVIEW else LINE_WIDTH_MAIN
            setDrawValues(false)
            setDrawCircles(true)
            circleRadius = if (showCalibrationOverlay && !isOverview) CIRCLE_RADIUS_CALIBRATION_OVERLAY else if (isOverview) CIRCLE_RADIUS_OVERVIEW else CIRCLE_RADIUS_MAIN
            circleHoleRadius = if (isOverview) CIRCLE_HOLE_RADIUS_OVERVIEW else CIRCLE_HOLE_RADIUS_MAIN
            circleHoleColor = COLOR_CIRCLE_HOLE
            setCircleColor(COLOR_RAW_SERIES)
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(false)
            axisDependency = YAxis.AxisDependency.RIGHT
            isHighlightEnabled = !isOverview
            highLightColor = COLOR_HIGHLIGHT
            highlightLineWidth = HIGHLIGHT_LINE_WIDTH
        }

        val lineData = if (showCalibrationOverlay && !isOverview) {
            val calibratedEntries = ordered.map { row ->
                Entry(toXValue(row.timestampMs, dayStartMs), calibratedY(row)).apply { data = row.calibratedValueMgdl }
            }
            val calibratedDataSet = LineDataSet(calibratedEntries, chart.context.getString(R.string.legend_calibrated_data)).apply {
                color = COLOR_CALIBRATED_SERIES
                lineWidth = LINE_WIDTH_CALIBRATED
                setDrawValues(false)
                setDrawCircles(true)
                circleRadius = CIRCLE_RADIUS_CALIBRATED
                circleHoleRadius = CIRCLE_HOLE_RADIUS_CALIBRATED
                circleHoleColor = COLOR_CIRCLE_HOLE
                setCircleColor(COLOR_CALIBRATED_SERIES)
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(false)
                axisDependency = YAxis.AxisDependency.RIGHT
                isHighlightEnabled = true
                highLightColor = COLOR_HIGHLIGHT
                highlightLineWidth = HIGHLIGHT_LINE_WIDTH
            }
            LineData(rawDataSet, calibratedDataSet)
        } else {
            LineData(rawDataSet)
        }

        chart.data = lineData

        // Keep the marker popup in sync with the same dayStartMs reference that
        // was used to generate every Entry.x value above. Without this, the
        // marker falls back to its default xReferenceMs = 0L and interprets the
        // relative x-value as minutes since the Unix epoch, producing obviously
        // wrong popup values such as 01/02 and shifted times.
        syncMarkerReference(chart, dayStartMs)

        chart.legend.apply {
            isEnabled = !isOverview
            textColor = COLOR_AXIS_TEXT
            form = Legend.LegendForm.LINE
            horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
            verticalAlignment = Legend.LegendVerticalAlignment.TOP
            orientation = Legend.LegendOrientation.VERTICAL
            setDrawInside(true)
            xOffset = LEGEND_X_OFFSET
            yOffset = LEGEND_Y_OFFSET
            formLineWidth = LINE_WIDTH_MAIN
            formSize = LEGEND_FORM_SIZE
            xEntrySpace = LEGEND_X_ENTRY_SPACE
            yEntrySpace = LEGEND_Y_ENTRY_SPACE
            // wordWrapEnabled = true // Not available in this MPAndroidChart version
        }

        chart.invalidate()
    }


    /**
     * Synchronizes the chart marker with the same midnight reference that was
     * used when converting absolute reading timestamps into relative chart X
     * values.
     *
     * Why this helper exists:
     * - The plotting layer stores each [Entry.x] as "minutes since [dayStartMs]"
     *   to avoid Float precision loss in MPAndroidChart.
     * - [GlucoseMarkerView] later converts the tapped [Entry.x] back into an
     *   absolute timestamp with the formula
     *   `timestampMs = (e.x * MS_PER_MIN) + xReferenceMs`.
     * - If [xReferenceMs] is left at its default value of `0L`, the popup will
     *   interpret the point as minutes after 1970-01-01 00:00:00 UTC and show a
     *   completely wrong calendar date/time (for example 01/02 with a shifted
     *   clock time in GMT+8).
     *
     * Why this runs during *every* render instead of initDetail():
     * - The user can switch between Today and previous days after the chart has
     *   already been initialized.
     * - The correct marker reference therefore depends on the currently rendered
     *   day, not on one-time chart setup.
     *
     * Safe behavior:
     * - If the chart has no marker, nothing happens.
     * - If some future code replaces the marker with a different MarkerView
     *   implementation, the safe cast prevents a ClassCastException and simply
     *   leaves that marker untouched.
     *
     * @param chart The MPAndroidChart instance currently being rendered.
     * @param dayStartMs Midnight of the displayed day, in epoch milliseconds.
     */
    private fun syncMarkerReference(chart: LineChart, dayStartMs: Long) {
        val glucoseMarker = chart.marker as? GlucoseMarkerView ?: return
        glucoseMarker.xReferenceMs = dayStartMs
    }


    /**
     * Applies low/high threshold lines to the axis that is actually visible on screen.
     *
     * xDrip reads the current threshold preferences and adds those guide lines to the
     * rendered graph itself, so the user always sees the newest values immediately.
     * This bridge helper follows the same idea.
     *
     * @param axis The MPAndroidChart Y axis that owns the visible threshold lines.
     * @param outputUnit Current graph unit. Stored thresholds are always mg/dL and are
     * converted here only for display.
     * @param lowThresholdMgdl Current low threshold from settings, in mg/dL.
     * @param highThresholdMgdl Current high threshold from settings, in mg/dL.
     */
    private fun applyThresholdLimitLines(
        axis: YAxis,
        outputUnit: String,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double
    ) {
        val isMmol = outputUnit == "mmol"
        val lowLineValue = if (isMmol) (lowThresholdMgdl / MMOL_FACTOR).toFloat() else lowThresholdMgdl.toFloat()
        val highLineValue = if (isMmol) (highThresholdMgdl / MMOL_FACTOR).toFloat() else highThresholdMgdl.toFloat()

        axis.removeAllLimitLines()
        axis.addLimitLine(LimitLine(lowLineValue, "Low Alarm").apply {
            lineColor = COLOR_LOW
            lineWidth = LIMIT_LINE_WIDTH
            textColor = COLOR_LOW
            textSize = LIMIT_LINE_TEXT_SIZE
            enableDashedLine(LIMIT_LINE_DASH_LENGTH, LIMIT_LINE_DASH_SPACE, 0f)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
        })
        axis.addLimitLine(LimitLine(highLineValue, "High Alarm").apply {
            lineColor = COLOR_HIGH
            lineWidth = LIMIT_LINE_WIDTH
            textColor = COLOR_HIGH
            textSize = LIMIT_LINE_TEXT_SIZE
            enableDashedLine(LIMIT_LINE_DASH_LENGTH, LIMIT_LINE_DASH_SPACE, 0f)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        })

        DebugTrace.t(
            DebugCategory.PLOTTING,
            "CHART-THRESHOLDS",
            "axis=right unit=$outputUnit low=$lowThresholdMgdl high=$highThresholdMgdl"
        )
    }

    // ─── Y axis dynamic range ────────────────────────────────────────────

    private fun updateYRange(
        chart: LineChart,
        list: List<BgReadingEntity>,
        outputUnit: String,
        showCalibrationOverlay: Boolean = false,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double
    ) {
        val isMmol = outputUnit == "mmol"
        val allValuesMgdl = buildList {
            list.forEach { row ->
                add(row.calibratedValueMgdl.toDouble())
                if (showCalibrationOverlay) add(row.calculatedValueMgdl.toDouble())
            }
        }
        val minMgdl = minOf(DEFAULT_MIN_MGDL, allValuesMgdl.minOrNull() ?: DEFAULT_MIN_MGDL, lowThresholdMgdl - 10.0)
        val maxMgdl = maxOf(DEFAULT_MAX_MGDL, allValuesMgdl.maxOrNull() ?: DEFAULT_MAX_MGDL, highThresholdMgdl + 10.0)
        val axisMin = if (isMmol) (minMgdl / MMOL_FACTOR).toFloat() else minMgdl.toFloat()
        val axisMax = if (isMmol) (maxMgdl / MMOL_FACTOR).toFloat() else maxMgdl.toFloat()

        /**
         * Keep both axes on the same numeric range, but only draw threshold lines on the
         * visible right axis.
         *
         */
        chart.axisLeft.apply {
            axisMinimum = axisMin
            axisMaximum = axisMax
            removeAllLimitLines()
        }
        chart.axisRight.apply {
            axisMinimum = axisMin
            axisMaximum = axisMax
        }
        applyThresholdLimitLines(
            axis = chart.axisRight,
            outputUnit = outputUnit,
            lowThresholdMgdl = lowThresholdMgdl,
            highThresholdMgdl = highThresholdMgdl
        )
        chart.data?.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    // ─── X axis (round-hour labels) ─────────────────────────────────────

    /**
     * Detail chart X-axis.
     * 24h → full day 00:00–24:00 (labels every 4h)
     * 12h → 12:00–24:00 for past day, or snapped window for today (every 2h)
     * 6h  → snapped window (every 1h)
     * 3h  → snapped window (every 1h)
     */
    private fun configureXAxisDetail(
        chart: LineChart,
        dayStartMs: Long,
        windowHours: Int,
        isToday: Boolean
    ) {
        val dayEndMs = dayStartMs + 24L * HOUR_MS

        val stepHours = when {
            windowHours <= 6 -> 1
            windowHours <= 12 -> 2
            else -> 4
        }

        val displayFrom: Long
        val displayTo: Long

        if (windowHours >= 24) {
            // Full day: 00:00 – 24:00
            displayFrom = dayStartMs
            displayTo = dayEndMs
        } else if (isToday) {
            // Today smaller window: snap around "now"
            val now = System.currentTimeMillis()
            val snapTo = snapUp(now, stepHours)
            val snapFrom = maxOf(dayStartMs, snapTo - windowHours * HOUR_MS)
            displayFrom = snapFrom
            displayTo = snapTo
        } else {
            // Past day smaller window: last N hours of the day
            displayFrom = dayEndMs - windowHours * HOUR_MS
            displayTo = dayEndMs
        }

        applyXAxis(chart, displayFrom, displayTo, stepHours, referenceMs = dayStartMs)
    }

    /** Overview chart: always full day 00:00–24:00, labels every 6h. */
    private fun configureXAxisOverview(chart: LineChart, dayStartMs: Long) {
        applyXAxis(chart, dayStartMs, dayStartMs + 24L * HOUR_MS, 6, referenceMs = dayStartMs)
    }

    /** Snap a timestamp UP to the next whole `stepHours` boundary. */
    private fun snapUp(ms: Long, stepHours: Int): Long {
        val cal = GregorianCalendar().apply { timeInMillis = ms }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val next = if (m == 0 && s == 0) {
            // Already on the hour — snap to next step boundary
            ((h + stepHours - 1) / stepHours) * stepHours
        } else {
            // Not on the hour — round up
            ((h / stepHours) + 1) * stepHours
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis + next * HOUR_MS
    }

    /** Apply X-axis settings with evenly-spaced round-hour labels.
     *  @param referenceMs  x-axis epoch origin (same as dayStartMs passed to [renderChart]).
     */
    private fun applyXAxis(chart: LineChart, fromMs: Long, toMs: Long, stepHours: Int, referenceMs: Long) {
        val rangeStartMin = toXValue(fromMs, referenceMs)
        val rangeEndMin = toXValue(toMs, referenceMs)
        val totalHours = ((toMs - fromMs) / HOUR_MS).toInt()
        val labelCount = (totalHours / stepHours) + 1

        val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        DebugTrace.v(
            DebugCategory.PLOTTING,
            "CHART-AXIS"
        ) {
            "xMin=%.4f xMax=%.4f refMs=%d fromMs=%d toMs=%d".format(
                rangeStartMin, rangeEndMin, referenceMs, fromMs, toMs
            )
        }

        chart.xAxis.apply {
            axisMinimum = rangeStartMin
            axisMaximum = rangeEndMin
            granularity = (stepHours * 60).toFloat()
            setLabelCount(labelCount.coerceIn(2, 10), true)

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    // Reconstruct absolute epoch-ms from relative x-value
                    val ms = (value.toDouble() * MS_PER_MIN + referenceMs).toLong()
                    return hourFmt.format(Date(ms))
                }
            }
        }
    }
}
