/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package tw.yourcompany.cgmbridge.feature.ui.shell

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.platform.NotificationAccessChecker
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.prefs.MultiSourceSettings
import tw.yourcompany.cgmbridge.databinding.ActivityMainBinding
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationDialogHelper
import tw.yourcompany.cgmbridge.feature.input.notification.GlucoseUnitConverter
import tw.yourcompany.cgmbridge.feature.input.notification.SlopeDirectionCalculator
import tw.yourcompany.cgmbridge.feature.keepalive.GuardianServiceLauncher
import tw.yourcompany.cgmbridge.feature.statistics.ChartHelper
import tw.yourcompany.cgmbridge.feature.statistics.GlucoseVariabilityCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Main screen — mirrors xDrip+ Home layout:
 * - BG value (right-aligned, large text) + Unicode arrow
 * - Minutes ago + delta (left-aligned info block)
 * - Date selector (7 days) + Time window chips (3/6/12/24h)
 * - Detail chart (zoomable, with crosshair marker)
 * - Overview chart (full-day preview / mini graph)
 * - Glucose variability statistics
 *
 * Multi-source behavior after this patch:
 * - the detail chart shows all visible sources at the same time;
 * - the overview chart shows only the selected primary source;
 * - the large top BG value also prefers the selected primary source when one exists.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var prefs: AppPrefs
    private lateinit var multiSourceSettings: MultiSourceSettings

    private var latestReadingsCache: List<BgReadingEntity> = emptyList()
    private var overviewCache: List<BgReadingEntity> = emptyList()
    private var sourcesCache: List<CgmSourceEntity> = emptyList()
    private var primarySourceId: String? = null
    private var currentOutputUnit: String = "mgdl"
    private var currentWindowHours: Int = 24
    private var currentDateOffset: Int = 0

    /** Date button views (index 0 = today, 6 = 6 days ago). */
    private lateinit var dateButtons: List<TextView>

    private val graphSettingKeys = setOf(
        "dLowBloodMgdl",
        "dHighBloodMgdl",
        "outputUnit"
    )

    /**
     * Re-renders the charts when a graph-relevant preference changes.
     *
     * We listen only to graph-related keys so unrelated settings do not cause unnecessary redraws.
     */
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key !in graphSettingKeys) return@OnSharedPreferenceChangeListener
        val latestUnit = prefs.outputUnit
        if (latestUnit != currentOutputUnit) {
            currentOutputUnit = latestUnit
            ChartHelper.initDetail(binding.glucoseChartDetail, currentOutputUnit)
            ChartHelper.initOverview(binding.glucoseChartOverview, currentOutputUnit)
        }
        renderAll()
    }

    /**
     * Creates the screen, wires the navigation / chips / observers, and prepares both charts.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        multiSourceSettings = MultiSourceSettings(this)
        primarySourceId = multiSourceSettings.primaryInputSourceId
        currentOutputUnit = prefs.outputUnit
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs.registerChangeListener(prefsChangeListener)

        ChartHelper.initDetail(binding.glucoseChartDetail, currentOutputUnit)
        ChartHelper.initOverview(binding.glucoseChartOverview, currentOutputUnit)

        setupBottomNav()
        markSelectedTab(isGraph = true)

        dateButtons = listOf(
            binding.dateBtn0, binding.dateBtn1, binding.dateBtn2, binding.dateBtn3,
            binding.dateBtn4, binding.dateBtn5, binding.dateBtn6
        )
        setupDateButtons()

        binding.chip3h.setOnClickListener { vm.observe3h(); currentWindowHours = 3; updateChipState(3) }
        binding.chip6h.setOnClickListener { vm.observe6h(); currentWindowHours = 6; updateChipState(6) }
        binding.chip12h.setOnClickListener { vm.observe12h(); currentWindowHours = 12; updateChipState(12) }
        binding.chip24h.setOnClickListener { vm.observe24h(); currentWindowHours = 24; updateChipState(24) }
        updateChipState(24)

        if (NotificationAccessChecker.isNotificationAccessGranted(this)) {
            GuardianServiceLauncher.start(this, "ui-launch")
        }

        DebugTrace.t(
            DebugCategory.KEEPALIVE,
            "UI-STATE",
            "notif_access=${NotificationAccessChecker.isNotificationAccessGranted(this)} unit=${prefs.outputUnit} role=${prefs.role} primary=$primarySourceId"
        )

        observeData()
    }

    /**
     * Refreshes unit-sensitive chart setup and the currently selected primary source whenever the
     * activity returns to the foreground.
     */
    override fun onResume() {
        super.onResume()
        primarySourceId = multiSourceSettings.primaryInputSourceId
        val latestUnit = prefs.outputUnit
        if (latestUnit != currentOutputUnit) {
            currentOutputUnit = latestUnit
            ChartHelper.initDetail(binding.glucoseChartDetail, currentOutputUnit)
            ChartHelper.initOverview(binding.glucoseChartOverview, currentOutputUnit)
        }
        renderAll()
    }

    override fun onDestroy() {
        prefs.unregisterChangeListener(prefsChangeListener)
        super.onDestroy()
    }

    /**
     * Populates the 7-day date selector and binds each button to the correct historical day.
     */
    private fun setupDateButtons() {
        val dateFmt = SimpleDateFormat("MM/dd", Locale.getDefault())
        for (i in dateButtons.indices) {
            val btn = dateButtons[i]
            if (i == 0) {
                btn.text = "Today"
            } else {
                val cal = GregorianCalendar().apply { add(Calendar.DAY_OF_YEAR, -i) }
                btn.text = dateFmt.format(cal.time)
            }
            btn.setOnClickListener {
                currentDateOffset = i
                vm.selectDate(i)
                updateDateButtonState(i)
            }
        }
        updateDateButtonState(0)
    }

    /** Highlights the selected date button. */
    private fun updateDateButtonState(selectedOffset: Int) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        for (i in dateButtons.indices) {
            val btn = dateButtons[i]
            btn.setTextColor(if (i == selectedOffset) sel else nor)
            btn.setTypeface(
                null,
                if (i == selectedOffset) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    /**
     * Registers all LiveData observers used by the main screen.
     *
     * We observe readings and source metadata separately because the graph needs both pieces:
     * readings provide values and timestamps, while the source registry provides color / label /
     * visibility information.
     */
    private fun observeData() {
        vm.latestReadings.observe(this) { list ->
            latestReadingsCache = list.orEmpty()
            renderAll()
        }
        vm.overviewReadings.observe(this) { list ->
            overviewCache = list.orEmpty()
            renderOverview()
        }
        vm.allSources.observe(this) { list ->
            sourcesCache = list.orEmpty()
            renderAll()
        }
    }

    /**
     * Re-renders every UI block that depends on the current reading/source state.
     */
    private fun renderAll() {
        renderBgInfo(latestReadingsCache)
        renderDetailChart(latestReadingsCache)
        renderOverview()
        renderStats(if (latestReadingsCache.isEmpty()) null else calculateStats(latestReadingsCache))
        updateInputStatusBlocks()
    }

    /**
     * Chooses the row that should drive the large top BG value.
     *
     * Rule:
     * - if a primary source is selected and that source has rows in the current list, use the
     *   latest row from that source;
     * - otherwise, fall back to the latest row across all sources.
     */
    private fun latestForDisplay(list: List<BgReadingEntity>): BgReadingEntity? {
        val primaryRows = primarySourceId?.let { id -> list.filter { it.sourceId == id } }.orEmpty()
        val target = if (primaryRows.isNotEmpty()) primaryRows else list
        return target.maxByOrNull { it.timestampMs }
    }

    /**
     * Renders the latest glucose value, direction arrow, minutes ago, and 5-minute delta.
     *
     * The slope is calculated from the same source-specific subset that drives the displayed top
     * value, preventing a non-primary source from influencing the arrow shown for the primary one.
     */
    private fun renderBgInfo(list: List<BgReadingEntity>) {
        val latest = latestForDisplay(list)
        if (latest == null) {
            binding.latestValueText.text = "--"
            binding.latestValueText.setTextColor(0xFFFFFFFF.toInt())
            binding.directionArrowText.text = ""
            binding.minutesAgoText.text = ""
            binding.deltaText.text = ""
            return
        }

        val rowsForDisplay = if (!primarySourceId.isNullOrBlank()) {
            list.filter { it.sourceId == primarySourceId }
        } else {
            list
        }

        val mgdl = latest.calibratedValueMgdl
        val displayValue = if (currentOutputUnit == "mmol") {
            GlucoseUnitConverter.mgdlToMmolString(mgdl)
        } else {
            mgdl.toString()
        }
        binding.latestValueText.text = displayValue

        val bgColor = when {
            mgdl < 70 -> 0xFFFF5252.toInt()
            mgdl <= 170 -> 0xFF4CAF50.toInt()
            else -> 0xFFFFC107.toInt()
        }
        binding.latestValueText.setTextColor(bgColor)

        val slope = SlopeDirectionCalculator.calculate(rowsForDisplay)
        binding.directionArrowText.text = SlopeDirectionCalculator.directionToArrow(slope.directionName)
        binding.directionArrowText.setTextColor(bgColor)
        binding.minutesAgoText.text = if (slope.minutesAgo <= 0) "Just now" else "${slope.minutesAgo} min ago"

        if (!slope.isValid || slope.deltaPerFiveMin.isNaN()) {
            binding.deltaText.text = "Delta: ???"
        } else if (currentOutputUnit == "mmol") {
            binding.deltaText.text = String.format(Locale.US, "Delta: %+.2f mmol/L per 5-min", slope.deltaPerFiveMin / 18.0)
        } else {
            binding.deltaText.text = String.format(Locale.US, "Delta: %+.1f mg/dL per 5-min", slope.deltaPerFiveMin)
        }
    }

    /**
     * Renders the main graph with all visible sources.
     */
    private fun renderDetailChart(list: List<BgReadingEntity>) {
        val (lowAlarmLabel, highAlarmLabel) = getAlarmLabels(forMainGraph = true)
        ChartHelper.renderDetail(
            binding.glucoseChartDetail,
            list,
            sourcesCache,
            currentOutputUnit,
            vm.dayStartMs(),
            currentWindowHours,
            vm.isToday(),
            primarySourceId,
            prefs.calibrationEnabled,
            prefs.dLowBlood,
            prefs.dHighBlood,
            lowAlarmLabel,
            highAlarmLabel
        )
    }

    /**
     * Renders the mini graph using only the selected primary source.
     */
    private fun renderOverview() {
        val data = if (overviewCache.isNotEmpty()) overviewCache else latestReadingsCache
        val (lowAlarmLabel, highAlarmLabel) = getAlarmLabels(forMainGraph = false)
        ChartHelper.renderOverview(
            binding.glucoseChartOverview,
            data,
            sourcesCache,
            currentOutputUnit,
            vm.dayStartMs(),
            currentWindowHours,
            vm.isToday(),
            primarySourceId,
            prefs.calibrationEnabled,
            prefs.dLowBlood,
            prefs.dHighBlood,
            lowAlarmLabel,
            highAlarmLabel
        )
    }

    /**
     * Returns the correct alarm line labels for the chart, based on alarm enabled state and primary input.
     *
     * @param forMainGraph true for main graph (detail), false for mini graph (overview)
     */
    private fun getAlarmLabels(forMainGraph: Boolean): Pair<String, String> {
        if (!forMainGraph) {
            // Mini graph: always fixed labels
            return "Low Alarm" to "High Alarm"
        }
        val lineBreak = '\u2028'.toString() // Unicode line separator
        val noPrimary = primarySourceId.isNullOrBlank()
        val lowEnabled = prefs.alarmLowEnabled && !noPrimary
        val highEnabled = prefs.alarmHighEnabled && !noPrimary
        val lowLabel = if (lowEnabled) "Low Alarm$lineBreak(Enabled)" else "Low Alarm$lineBreak(Disabled)"
        val highLabel = if (highEnabled) "High Alarm$lineBreak(Enabled)" else "High Alarm$lineBreak(Disabled)"
        return lowLabel to highLabel
    }

    /**
     * Calculates glucose variability statistics for the currently visible detail-chart rows.
     */
    private fun calculateStats(list: List<BgReadingEntity>): GlucoseVariabilityCalculator.Result? {
        return GlucoseVariabilityCalculator.calculate(
            readings = list,
            lowThresholdMgdl = prefs.dLowBlood,
            highThresholdMgdl = prefs.dHighBlood
        )
    }

    /**
     * Renders the statistics block below the charts.
     */
    private fun renderStats(stats: GlucoseVariabilityCalculator.Result?) {
        val windowLabel = "${currentWindowHours}h"
        binding.statsTitleText.text = getString(R.string.home_stats_title_with_window, windowLabel)
        binding.statRangeLabelText.text = getString(
            R.string.stat_range_label_dynamic,
            formatGlucoseThresholdForStatsLabel(prefs.dLowBlood),
            formatGlucoseThresholdForStatsLabel(prefs.dHighBlood)
        )

        if (stats == null) {
            binding.statSdValueText.text = "--"
            binding.statCvValueText.text = "--"
            binding.statMinValueText.text = "--"
            binding.statMaxValueText.text = "--"
            binding.statHba1cValueText.text = "--"
            binding.statRangeValueText.text = "--"
            return
        }

        if (currentOutputUnit == "mmol") {
            binding.statSdValueText.text = getString(R.string.stat_value_mmol, stats.sdMgdl / 18.0)
            binding.statMaxValueText.text = getString(R.string.stat_value_mmol, stats.maxMgdl / 18.0)
            binding.statMinValueText.text = getString(R.string.stat_value_mmol, stats.minMgdl / 18.0)
        } else {
            binding.statSdValueText.text = getString(R.string.stat_value_mgdl, stats.sdMgdl)
            binding.statMaxValueText.text = getString(R.string.stat_value_int_mgdl, stats.maxMgdl)
            binding.statMinValueText.text = getString(R.string.stat_value_int_mgdl, stats.minMgdl)
        }

        binding.statCvValueText.text = getString(R.string.stat_percent_value, stats.cvPercent)
        binding.statHba1cValueText.text = getString(R.string.stat_hba1c_value, stats.estimatedHba1cPercent)
        binding.statRangeValueText.text = getString(
            R.string.stat_range_value,
            stats.inRangePercent,
            stats.highPercent,
            stats.lowPercent
        )
    }

    /**
     * Formats an alarm threshold for the dynamic statistics label.
     */
    private fun formatGlucoseThresholdForStatsLabel(valueMgdl: Double): String {
        return if (currentOutputUnit == "mmol") {
            String.format(Locale.US, "%.1f mmol/L", valueMgdl / 18.0)
        } else {
            String.format(Locale.US, "%.0f mg/dL", valueMgdl)
        }
    }

    /** Highlights the currently selected time-window chip. */
    private fun updateChipState(hours: Int) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        val chips = listOf(binding.chip3h to 3, binding.chip6h to 6, binding.chip12h to 12, binding.chip24h to 24)
        for ((chip, h) in chips) {
            chip.setTextColor(if (hours == h) sel else nor)
            chip.setTypeface(null, if (hours == h) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /**
     * Wires the bottom navigation actions used by this screen.
     */
    private fun setupBottomNav() {
        binding.bottomNav.btnNavGraph.setOnClickListener { binding.root.scrollTo(0, 0) }
        binding.bottomNav.btnNavStatistics.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavCarbInsulin.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavTools.setOnClickListener {
            CalibrationDialogHelper.showCalibrationMenu(this, prefs) { renderAll() }
        }
        binding.bottomNav.btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsMenuActivity::class.java))
        }
    }

    /** Highlights the selected bottom-nav tab. */
    private fun markSelectedTab(isGraph: Boolean) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        binding.bottomNav.btnNavGraph.setTextColor(if (isGraph) sel else nor)
        binding.bottomNav.btnNavSettings.setTextColor(if (isGraph) nor else sel)
        binding.bottomNav.btnNavStatistics.setTextColor(nor)
        binding.bottomNav.btnNavCarbInsulin.setTextColor(nor)
        binding.bottomNav.btnNavTools.setTextColor(nor)
    }

    /**
     * Updates the input status blocks below the main chart to show the current primary and optional input sources.
     * The format matches the main graph legend: "Vendor / transport / Raw" or "Vendor / transport / Calibrated".
     */
    private fun updateInputStatusBlocks() {
        // Find the primary source
        val primarySource = sourcesCache.firstOrNull { it.sourceId == primarySourceId }
        val calibrationEnabled = prefs.calibrationEnabled

        if (primarySource != null) {
            // Build label for primary input with bold prefix
            val primaryLabel = if (calibrationEnabled) {
                "${primarySource.vendorName} / ${primarySource.transportType.lowercase()} / Calibrated"
            } else {
                "${primarySource.vendorName} / ${primarySource.transportType.lowercase()} / Raw"
            }
            val primaryPrefix = "Primary Input : "
            val primaryHtml = primaryPrefix + primaryLabel
            binding.uiPrimaryInputStatus.text = android.text.Html.fromHtml(primaryHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else {
            // Show only the string without prefix if no primary input is selected
            binding.uiPrimaryInputStatus.text = getString(R.string.no_primary_input)
        }



        // Build labels for all other enabled and visible sources (excluding primary)
        val optionalSources = sourcesCache
            .filter { it.enabled && it.visibleOnMainGraph && it.sourceId != primarySourceId }

        if (optionalSources.isNotEmpty()) {
            val optionalLines = mutableListOf<String>()
            optionalSources.forEachIndexed { idx, src ->
                val prefix = "<b>Optional Input${idx + 1} : </b>"
                val label = "${src.vendorName} / ${src.transportType.lowercase()} / Raw"
                // If label contains newlines, add prefix to each line
                label.split("\n").forEach { line ->
                    optionalLines.add("$prefix${line.trim()}")
                }
            }
            val html = optionalLines.joinToString("<br>")
            binding.uiOptionalInputStatus.text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else {
            binding.uiOptionalInputStatus.text = getString(R.string.no_optional_inputs)
        }
    }
}