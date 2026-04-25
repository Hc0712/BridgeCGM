package com.north7.bridgecgm.feature.statistics

import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.north7.bridgecgm.core.db.BgReadingEntity
import com.north7.bridgecgm.core.db.CgmSourceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Configures and renders the two glucose charts on the main screen.
 *
 * Rendering rules after the multi-source fix:
 * - detail chart (main graph): render one dataset per visible source and add a calibrated overlay
 *   only for the selected primary source when calibration is enabled;
 * - overview chart (mini graph): render only the selected primary source;
 * - all x-values are stored as minutes since midnight to avoid Float precision loss in
 *   MPAndroidChart.
 */
object ChartHelper {
    private const val MS_PER_MIN = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val COLOR_GRID = 0x33FFFFFF
    private const val COLOR_AXIS_TEXT = 0xFFAAAAAA.toInt()
    private const val COLOR_BG_MAIN = 0xFF212121.toInt()
    private const val COLOR_BG_OVERVIEW = 0xFF1A1A1A.toInt()
    private const val COLOR_CIRCLE_HOLE = 0xFF212121.toInt()
    private const val COLOR_HIGHLIGHT = 0xAAFFFFFF.toInt()
    private const val LIMIT_LINE_WIDTH = 1.0f
    private const val LIMIT_LINE_TEXT_SIZE = 8f
    private const val HIGHLIGHT_LINE_WIDTH = 0.8f
    private const val WINDOW_EDGE_LINE_WIDTH = 1.1f
    private const val WINDOW_EDGE_TEXT_SIZE = 7f
    private const val WINDOW_EDGE_COLOR = 0xAA80CBC4.toInt()
    private const val MMOL_FACTOR =
        com.north7.bridgecgm.core.constants.GlucoseConstants.MMOL_FACTOR
    private const val DEFAULT_MIN_MGDL =
        com.north7.bridgecgm.core.constants.GlucoseConstants.DEFAULT_MIN_MGDL
    private const val DEFAULT_MAX_MGDL =
        com.north7.bridgecgm.core.constants.GlucoseConstants.DEFAULT_MAX_MGDL

    /**
     * Converts an absolute timestamp into a float-safe x-axis value measured in minutes since the
     * current chart reference time, usually midnight of the selected day.
     */
    private fun toXValue(timestampMs: Long, referenceMs: Long): Float =
        ((timestampMs - referenceMs).toDouble() / MS_PER_MIN).toFloat()

    /**
     * One-time setup for the interactive detail chart.
     *
     * The method also installs the marker view so tapped points can be converted back into an
     * absolute timestamp using the same midnight reference that was used during rendering.
     */
    fun initDetail(chart: LineChart, outputUnit: String) {
        chart.setBackgroundColor(COLOR_BG_MAIN)
        chart.description.isEnabled = false
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
        chart.axisLeft.isEnabled = false
        chart.axisRight.isEnabled = true
        chart.marker = GlucoseMarkerView(chart.context, outputUnit)
        setupAxes(chart, outputUnit, isOverview = false)
    }

    /**
     * One-time setup for the non-interactive overview chart.
     *
     * This chart acts as the mini graph and therefore intentionally disables touch interaction and
     * the legend.
     */
    fun initOverview(chart: LineChart, outputUnit: String) {
        chart.setBackgroundColor(COLOR_BG_OVERVIEW)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)
        chart.setNoDataText("")
        chart.setTouchEnabled(false)
        chart.setPinchZoom(false)
        chart.isDragEnabled = false
        chart.isScaleXEnabled = false
        chart.isScaleYEnabled = false
        chart.axisLeft.isEnabled = false
        chart.axisRight.isEnabled = true
        setupAxes(chart, outputUnit, isOverview = true)
    }

    /**
     * Shared axis style setup.
     *
     * The right axis is used because the original project already renders visible glucose data on
     * that side. The left axis is kept disabled to reduce clutter.
     */
    private fun setupAxes(chart: LineChart, outputUnit: String, isOverview: Boolean) {
        val isMmol = outputUnit == "mmol"
        val yMin =
            if (isMmol) (DEFAULT_MIN_MGDL / MMOL_FACTOR).toFloat() else DEFAULT_MIN_MGDL.toFloat()
        val yMax =
            if (isMmol) (DEFAULT_MAX_MGDL / MMOL_FACTOR).toFloat() else DEFAULT_MAX_MGDL.toFloat()

        chart.axisRight.apply {
            axisMinimum = yMin
            axisMaximum = yMax
            textColor = COLOR_AXIS_TEXT
            gridColor = COLOR_GRID
            setDrawGridLines(true)
            setDrawAxisLine(false)
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = COLOR_AXIS_TEXT
            gridColor = COLOR_GRID
            setDrawAxisLine(false)
        }
    }

    /**
     * Renders the main chart (detail graph).
     *
     * The chart receives the full reading list for the current time window plus the source registry.
     * It then builds one dataset per visible source and adds the optional primary calibrated
     * overlay.
     *
     * Alarm label contract for main graph:
     *   - Pass "High Alarm" or "Low Alarm" if enabled.
     *   - Pass "High Alarm\n(Disabled)" or "Low Alarm\n(Disabled)" if disabled.
     *   - Pass "High Alarm\n(Enabled)" or "Low Alarm\n(Enabled)" if enabled and you want to show status.
     *   - The caller is responsible for generating the correct label string with line breaks and capitalization.
     */
    fun renderDetail(
        chart: LineChart,
        rows: List<BgReadingEntity>,
        sources: List<CgmSourceEntity>,
        outputUnit: String,
        dayStartMs: Long,
        timeWindowHours: Int,
        isToday: Boolean,
        primarySourceId: String?,
        calibrationEnabled: Boolean,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double,
        lowAlarmLabel: String,
        highAlarmLabel: String,
        resetToDefaultTimeWindow: Boolean = false
    ) {
        configureXAxisDetail(chart, dayStartMs, timeWindowHours, isToday)
        val series = MultiSourceChartSupport.buildMainGraphSeries(
            rows = rows,
            sources = sources,
            primaryInputSourceId = primarySourceId,
            calibrationEnabled = calibrationEnabled
        )
        renderSeries(
            chart = chart,
            seriesList = series,
            outputUnit = outputUnit,
            dayStartMs = dayStartMs,
            isOverview = false
        )
        updateYRange(
            chart,
            rows,
            outputUnit,
            calibrationEnabled,
            lowThresholdMgdl,
            highThresholdMgdl,
            lowAlarmLabel,
            highAlarmLabel
        )
        if (resetToDefaultTimeWindow) {
            resetDetailViewportToDefaultTimeWindow(chart, dayStartMs, timeWindowHours, isToday)
        }
    }

    /**
     * Renders the mini graph (overview chart).
     *
     * Only the selected primary source is drawn. If the user has not selected a primary source yet
     * or the selected source has no rows in the current day, the chart is cleared.
     *
     * Alarm label contract for mini graph:
     *   - Always pass the fixed strings "High Alarm" and "Low Alarm" for the two horizontal lines.
     *   - Do NOT append status or line breaks for the mini graph.
     */
    fun renderOverview(
        chart: LineChart,
        rows: List<BgReadingEntity>,
        sources: List<CgmSourceEntity>,
        outputUnit: String,
        dayStartMs: Long,
        highlightWindowHours: Int,
        isToday: Boolean,
        primarySourceId: String?,
        calibrationEnabled: Boolean,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double,
        lowAlarmLabel: String,
        highAlarmLabel: String
    ) {
        configureXAxisOverview(chart, dayStartMs)
        val primarySource = sources.firstOrNull { it.sourceId == primarySourceId }
        val primaryRows =
            if (primarySourceId.isNullOrBlank()) emptyList() else rows.filter { it.sourceId == primarySourceId }
        val mini = MultiSourceChartSupport.buildMiniGraphSeries(
            primaryRows,
            primarySource,
            calibrationEnabled
        )
        renderSeries(
            chart = chart,
            seriesList = listOfNotNull(mini),
            outputUnit = outputUnit,
            dayStartMs = dayStartMs,
            isOverview = true
        )
        updateYRange(
            chart,
            primaryRows,
            outputUnit,
            calibrationEnabled,
            lowThresholdMgdl,
            highThresholdMgdl,
            lowAlarmLabel,
            highAlarmLabel
        )
    }

    /**
     * Converts logical series into MPAndroidChart datasets.
     *
     * Detail-chart behavior:
     * - the legend is enabled so different sources can be identified visually;
     * - raw series use a thinner line;
     * - calibrated overlay uses a thicker line and a slightly larger point marker.
     *
     * Overview-chart behavior:
     * - the legend stays off;
     * - touch highlighting is disabled.
     */
    private fun renderSeries(
        chart: LineChart,
        seriesList: List<MultiSourceChartSupport.GraphSeries>,
        outputUnit: String,
        dayStartMs: Long,
        isOverview: Boolean
    ) {
        if (seriesList.isEmpty()) {
            chart.clear()
            chart.legend.isEnabled = !isOverview
            chart.invalidate()
            return
        }

        val isMmol = outputUnit == "mmol"
        val dataSets = seriesList.map { series ->
            val entries = series.rows.map { row ->
                val value =
                    if (series.useCalibratedValue) row.calibratedValueMgdl else row.rawValueMgdl
                val y = if (isMmol) value / MMOL_FACTOR.toFloat() else value.toFloat()
                Entry(toXValue(row.timestampMs, dayStartMs), y).apply { data = row }
            }
            val lineColor = series.colorArgb
            LineDataSet(entries, series.label).apply {
                color = lineColor
                lineWidth = if (series.useCalibratedValue) 2.2f else if (isOverview) 1.2f else 1.6f
                setDrawValues(false)
                setDrawCircles(true)
                circleRadius =
                    if (series.useCalibratedValue) 4.0f else if (isOverview) 2.2f else 3.0f
                circleHoleRadius = if (isOverview) 0.6f else 1.0f
                circleHoleColor = COLOR_CIRCLE_HOLE
                setCircleColor(lineColor)
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(false)
                axisDependency = YAxis.AxisDependency.RIGHT
                isHighlightEnabled = !isOverview
                highLightColor = COLOR_HIGHLIGHT
                highlightLineWidth = HIGHLIGHT_LINE_WIDTH
                if (!isOverview && !series.useCalibratedValue && seriesList.any { it.sourceId == series.sourceId && it.useCalibratedValue }) {
                    enableDashedLine(10f, 6f, 0f)
                }
            }
        }

        chart.data = LineData(dataSets)
        syncMarkerReference(chart, dayStartMs)
        chart.legend.apply {
            isEnabled = !isOverview
            textColor = COLOR_AXIS_TEXT
            form = Legend.LegendForm.LINE
            horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
            verticalAlignment = Legend.LegendVerticalAlignment.TOP
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
        }
        chart.invalidate()
    }

    /**
     * Synchronizes the marker popup with the same midnight reference used when building x-values.
     * Without this, the marker would interpret the relative x-axis value as minutes since Unix
     * epoch and display the wrong date/time.
     */
    private fun syncMarkerReference(chart: LineChart, dayStartMs: Long) {
        val glucoseMarker = chart.marker as? GlucoseMarkerView ?: return
        glucoseMarker.xReferenceMs = dayStartMs
    }

    /**
     * Applies low/high threshold lines to the visible axis.
     * Thresholds are stored in mg/dL and converted only for display when the current UI unit is
     * mmol/L.
     */
    private fun applyThresholdLimitLines(
        axis: YAxis,
        outputUnit: String,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double,
        lowAlarmLabel: String,
        highAlarmLabel: String
    ) {
        val isMmol = outputUnit == "mmol"
        val lowLineValue =
            if (isMmol) (lowThresholdMgdl / MMOL_FACTOR).toFloat() else lowThresholdMgdl.toFloat()
        val highLineValue =
            if (isMmol) (highThresholdMgdl / MMOL_FACTOR).toFloat() else highThresholdMgdl.toFloat()

        axis.removeAllLimitLines()

        axis.addLimitLine(LimitLine(lowLineValue, lowAlarmLabel).apply {
            lineColor = 0xFFFF5252.toInt()
            lineWidth = LIMIT_LINE_WIDTH
            textColor = 0xFFFF5252.toInt()
            textSize = LIMIT_LINE_TEXT_SIZE
        })
        axis.addLimitLine(LimitLine(highLineValue, highAlarmLabel).apply {
            lineColor = 0xFFFFC107.toInt()
            lineWidth = LIMIT_LINE_WIDTH
            textColor = 0xFFFFC107.toInt()
            textSize = LIMIT_LINE_TEXT_SIZE
        })
    }

    /**
     * Updates the visible Y-axis range so all plotted rows and threshold lines remain inside view.
     */
    private fun updateYRange(
        chart: LineChart,
        list: List<BgReadingEntity>,
        outputUnit: String,
        showCalibrationOverlay: Boolean,
        lowThresholdMgdl: Double,
        highThresholdMgdl: Double,
        lowAlarmLabel: String,
        highAlarmLabel: String
    ) {
        val isMmol = outputUnit == "mmol"
        val allValuesMgdl = buildList {
            list.forEach { row ->
                add(row.calibratedValueMgdl.toDouble())
                if (showCalibrationOverlay) add(row.rawValueMgdl.toDouble())
            }
        }
        val minMgdl = min(
            DEFAULT_MIN_MGDL,
            min(allValuesMgdl.minOrNull() ?: DEFAULT_MIN_MGDL, lowThresholdMgdl - 10.0)
        )
        val maxMgdl = max(
            DEFAULT_MAX_MGDL,
            max(allValuesMgdl.maxOrNull() ?: DEFAULT_MAX_MGDL, highThresholdMgdl + 10.0)
        )
        val axisMin = if (isMmol) (minMgdl / MMOL_FACTOR).toFloat() else minMgdl.toFloat()
        val axisMax = if (isMmol) (maxMgdl / MMOL_FACTOR).toFloat() else maxMgdl.toFloat()
        chart.axisRight.axisMinimum = axisMin
        chart.axisRight.axisMaximum = axisMax
        applyThresholdLimitLines(chart.axisRight, outputUnit, lowThresholdMgdl, highThresholdMgdl, lowAlarmLabel, highAlarmLabel)
    }



/**
 * Restores the detail-chart viewport to the default chip-selected time window after the user
 * has previously pinch-zoomed or panned the graph.
 *
 * Behavior rules implemented here:
 * - Today: show the latest N hours ending at the current time, clamped to the selected day's
 *   midnight. Example: at 03:00, tapping 3h restores 00:00 -> 03:00.
 * - Past days: show the final N hours of the selected day, matching the existing query logic in
 *   MainViewModel.
 *
 * The method is intentionally one-shot. It resets the current transform once, but it does not
 * disable future manual zooming or panning by the user.
 */
    /**
     * Synchronizes the mini graph with the currently visible viewport of the main graph.
     *
     * Why this helper lives in ChartHelper instead of MainActivity:
     * - the x-values for both charts are already normalized here as minutes since the selected
     *   day start, so this layer can translate viewport edges into stable overview x positions
     *   without changing the existing activity/view-model responsibilities;
     * - the overview chart needs only two extra X-axis limit lines, which is a very small,
     *   low-risk extension of the existing chart rendering logic.
     *
     * Behavior:
     * - whenever the main chart viewport changes, two vertical lines are drawn on the mini graph
     *   at the visible start and visible end positions of the main chart;
     * - each line is labeled with the corresponding time edge so users can read the main-graph
     *   start/end directly from the mini graph;
     * - when either chart has no data, the indicator lines are removed to avoid stale markers.
     */
    fun syncOverviewViewportWindow(
        detailChart: LineChart,
        overviewChart: LineChart,
        dayStartMs: Long
    ) {
        val overviewData = overviewChart.data
        val detailData = detailChart.data
        val xAxis = overviewChart.xAxis
        xAxis.removeAllLimitLines()

        if (overviewData == null || overviewData.dataSetCount == 0 ||
            detailData == null || detailData.dataSetCount == 0) {
            overviewChart.invalidate()
            return
        }

        val axisMin = xAxis.axisMinimum
        val axisMax = xAxis.axisMaximum
        val visibleStart = detailChart.lowestVisibleX.coerceIn(axisMin, axisMax)
        val visibleEnd = detailChart.highestVisibleX.coerceIn(axisMin, axisMax)

        if (!visibleStart.isFinite() || !visibleEnd.isFinite() || visibleEnd < visibleStart) {
            overviewChart.invalidate()
            return
        }

        xAxis.addLimitLine(
            buildOverviewViewportEdgeLine(
                xValue = visibleStart,
                label = formatOverviewViewportEdgeLabel(visibleStart, dayStartMs),
                isStartEdge = true
            )
        )
        xAxis.addLimitLine(
            buildOverviewViewportEdgeLine(
                xValue = visibleEnd,
                label = formatOverviewViewportEdgeLabel(visibleEnd, dayStartMs),
                isStartEdge = false
            )
        )
        overviewChart.invalidate()
    }

    /**
     * Creates one vertical edge indicator for the mini graph.
     *
     * The two labels use opposite horizontal anchors so short windows (for example 00:00–03:00)
     * still show both time strings without immediately overlapping each other.
     */
    private fun buildOverviewViewportEdgeLine(
        xValue: Float,
        label: String,
        isStartEdge: Boolean
    ): LimitLine = LimitLine(xValue, label).apply {
        lineColor = WINDOW_EDGE_COLOR
        lineWidth = WINDOW_EDGE_LINE_WIDTH
        textColor = WINDOW_EDGE_COLOR
        textSize = WINDOW_EDGE_TEXT_SIZE
        labelPosition = if (isStartEdge) {
            LimitLine.LimitLabelPosition.RIGHT_TOP
        } else {
            LimitLine.LimitLabelPosition.LEFT_TOP
        }
    }

    /**
     * Formats one mini-graph edge label from the normalized x-axis value.
     *
     * The overview chart spans exactly one selected day. When the viewport reaches the far-right
     * edge we prefer the human-readable "24:00" label instead of wrapping back to the next day's
     * "00:00", because the user is looking at the end of the current day window.
     */
    private fun formatOverviewViewportEdgeLabel(xValue: Float, dayStartMs: Long): String {
        val clamped = xValue.coerceIn(0f, (24 * 60).toFloat())
        if (clamped >= (24 * 60).toFloat() - 0.001f) return "24:00"
        val timestampMs = (clamped.toDouble() * MS_PER_MIN + dayStartMs).toLong()
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }

private fun resetDetailViewportToDefaultTimeWindow(
    chart: LineChart,
    dayStartMs: Long,
    timeWindowHours: Int,
    isToday: Boolean
) {
    val totalDayMinutes = (24 * 60).toFloat()
    val requestedMinutes = (timeWindowHours.coerceIn(1, 24) * 60).toFloat()
    val visibleMinutes = requestedMinutes.coerceAtMost(totalDayMinutes)

    // Drop any previous pinch-zoom / drag transform first so the chip tap becomes a true reset.
    chart.fitScreen()

    val endMinutes = if (isToday) {
        ((System.currentTimeMillis() - dayStartMs).coerceIn(0L, 24L * HOUR_MS)).toFloat() /
            MS_PER_MIN.toFloat()
    } else {
        totalDayMinutes
    }
    val startMinutes = (endMinutes - visibleMinutes).coerceAtLeast(0f)
    val fullSpanMinutes = (chart.xAxis.axisMaximum - chart.xAxis.axisMinimum)
        .takeIf { it > 0f }
        ?: totalDayMinutes

    if (visibleMinutes < fullSpanMinutes) {
        val scaleX = (fullSpanMinutes / visibleMinutes).coerceAtLeast(1f)
        chart.zoom(scaleX, 1f, 0f, 0f)
    }

    chart.moveViewToX(startMinutes)
    chart.highlightValues(null)
    chart.invalidate()
}

    /**
     * Configures the main-chart x-axis.
     *
     * 24h uses the full day. Smaller windows use a snapped range around “now” for today or the
     * last N hours for past days.
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
            displayFrom = dayStartMs
            displayTo = dayEndMs
        } else if (isToday) {
            val now = System.currentTimeMillis()
            val snapTo = snapUp(now, stepHours)
            val snapFrom = max(dayStartMs, snapTo - windowHours * HOUR_MS)
            displayFrom = snapFrom
            displayTo = snapTo
        } else {
            displayFrom = dayEndMs - windowHours * HOUR_MS
            displayTo = dayEndMs
        }

        applyXAxis(chart, displayFrom, displayTo, stepHours, referenceMs = dayStartMs)
    }

    /** Overview chart: always full day with labels every 6 hours. */
    private fun configureXAxisOverview(chart: LineChart, dayStartMs: Long) {
        applyXAxis(chart, dayStartMs, dayStartMs + 24L * HOUR_MS, 6, referenceMs = dayStartMs)
    }

    /** Snaps a timestamp upward to the next whole step-hour boundary. */
    private fun snapUp(ms: Long, stepHours: Int): Long {
        val cal = GregorianCalendar().apply { timeInMillis = ms }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val s = cal.get(java.util.Calendar.SECOND)
        val next = if (m == 0 && s == 0) {
            ((h + stepHours - 1) / stepHours) * stepHours
        } else {
            ((h / stepHours) + 1) * stepHours
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis + next * HOUR_MS
    }

    /**
     * Applies x-axis bounds and a round-hour label formatter.
     * The formatter reconstructs an absolute timestamp from the relative x-axis value.
     */
    private fun applyXAxis(
        chart: LineChart,
        fromMs: Long,
        toMs: Long,
        stepHours: Int,
        referenceMs: Long
    ) {
        val rangeStartMin = toXValue(fromMs, referenceMs)
        val rangeEndMin = toXValue(toMs, referenceMs)
        val totalHours = ((toMs - fromMs) / HOUR_MS).toInt()
        val labelCount = (totalHours / stepHours) + 1
        val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        chart.xAxis.apply {
            axisMinimum = rangeStartMin
            axisMaximum = rangeEndMin
            granularity = (stepHours * 60).toFloat()
            setLabelCount(labelCount.coerceIn(2, 10), true)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val timestampMs = (value.toDouble() * MS_PER_MIN + referenceMs).toLong()
                    return hourFmt.format(Date(timestampMs))
                }
            }
        }
    }
}
