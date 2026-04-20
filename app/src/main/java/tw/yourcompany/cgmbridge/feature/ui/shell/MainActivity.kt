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
import tw.yourcompany.cgmbridge.databinding.ActivityMainBinding
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationDialogHelper
import tw.yourcompany.cgmbridge.feature.input.notification.GlucoseUnitConverter
import tw.yourcompany.cgmbridge.feature.input.notification.SlopeDirectionCalculator
import tw.yourcompany.cgmbridge.feature.keepalive.GuardianServiceLauncher
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.platform.NotificationAccessChecker
import tw.yourcompany.cgmbridge.feature.statistics.ChartHelper
import tw.yourcompany.cgmbridge.feature.statistics.GlucoseVariabilityCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Main screen — mirrors xDrip+ Home layout:
 * - BG value (right-aligned, large text) + Unicode arrow
 * - Minutes ago + delta (left-aligned info block)
 * - Date selector (7 days) + Time window chips (3/6/12/24h)
 * - Detail chart (zoomable, with crosshair marker)
 * - Overview chart (full-day preview)
 * - Glucose Variability statisticized within stats (SD, CV, Min, Max, HbA1c est., Range)
 */
class MainActivity : AppCompatActivity() {

    // TIR range defaults (mg/dL). TODO: make user-configurable in settings.
    private companion object {
        const val TIR_LOW_MGDL = 70.0
        const val TIR_HIGH_MGDL = 180.0
        const val TITR_LOW_MGDL = 70.0
        const val TITR_HIGH_MGDL = 140.0
    }

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var prefs: AppPrefs

    private var latestReadingsCache: List<BgReadingEntity> = emptyList()
    private var overviewCache: List<BgReadingEntity> = emptyList()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        currentOutputUnit = prefs.outputUnit
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs.registerChangeListener(prefsChangeListener)

        // Init both charts
        ChartHelper.initDetail(binding.glucoseChartDetail, currentOutputUnit)
        ChartHelper.initOverview(binding.glucoseChartOverview, currentOutputUnit)

        // Bottom navigation (rev1)
        setupBottomNav()
        markSelectedTab(isGraph = true)

        // ── Date selector buttons ──
        dateButtons = listOf(
            binding.dateBtn0, binding.dateBtn1, binding.dateBtn2, binding.dateBtn3,
            binding.dateBtn4, binding.dateBtn5, binding.dateBtn6
        )
        setupDateButtons()

        // ── Time window chips ──
        binding.chip3h.setOnClickListener {
            vm.observe3h(); currentWindowHours = 3; updateChipState(3)
        }
        binding.chip6h.setOnClickListener {
            vm.observe6h(); currentWindowHours = 6; updateChipState(6)
        }
        binding.chip12h.setOnClickListener {
            vm.observe12h(); currentWindowHours = 12; updateChipState(12)
        }
        binding.chip24h.setOnClickListener {
            vm.observe24h(); currentWindowHours = 24; updateChipState(24)
        }
        updateChipState(24)

        if (NotificationAccessChecker.isNotificationAccessGranted(this)) {
            GuardianServiceLauncher.start(this, "ui-launch")
        }

        DebugTrace.t(
            DebugCategory.KEEPALIVE,
            "UI-STATE",
            "notif_access=${NotificationAccessChecker.isNotificationAccessGranted(this)} unit=${prefs.outputUnit} role=${prefs.role}"
        )

        observeData()
    }

    override fun onResume() {
        super.onResume()
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

    // ─── Date Selector ──────────────────────────────────────────────────

    private fun setupDateButtons() {
        val dateFmt = SimpleDateFormat("MM/dd", Locale.getDefault())
        for (i in dateButtons.indices) {
            val btn = dateButtons[i]
            if (i == 0) {
                btn.text = "Today"
            } else {
                val cal = GregorianCalendar().apply {
                    add(Calendar.DAY_OF_YEAR, -i)
                }
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

    private fun updateDateButtonState(selectedOffset: Int) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        for (i in dateButtons.indices) {
            val btn = dateButtons[i]
            btn.setTextColor(if (i == selectedOffset) sel else nor)
            btn.setTypeface(
                null,
                if (i == selectedOffset) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL
            )
        }
    }

    // ─── Observers ───────────────────────────────────────────────────────

    private fun observeData() {
        // Detail chart data (date + time window filtered)
        vm.latestReadings.observe(this) { list ->
            latestReadingsCache = list.orEmpty()
            renderAll()
        }
        // Overview chart data (full day for selected date)
        vm.overviewReadings.observe(this) { list ->
            overviewCache = list.orEmpty()
            renderOverview()
        }
    }

    private fun renderAll() {
        renderBgInfo(latestReadingsCache)
        renderDetailChart(latestReadingsCache)
        renderOverview()
        renderStats(if (latestReadingsCache.isEmpty()) null else calculateStats(latestReadingsCache))
    }

    // ─── BG Value + Arrow + Delta + Minutes Ago ──────────────────────────

    private fun renderBgInfo(list: List<BgReadingEntity>) {
        if (list.isEmpty()) {
            binding.latestValueText.text = "--"
            binding.latestValueText.setTextColor(0xFFFFFFFF.toInt())
            binding.directionArrowText.text = ""
            binding.minutesAgoText.text = ""
            binding.deltaText.text = ""
            return
        }

        val latest = list.maxBy { it.timestampMs }

        // ── BG Value ──
        val mgdl = latest.CalibratedValueMgdl
        val displayValue = if (currentOutputUnit == "mmol") {
            GlucoseUnitConverter.mgdlToMmolString(mgdl)
        } else {
            mgdl.toString()
        }
        binding.latestValueText.text = displayValue

        // Color by range (xDrip: lowMark=70, highMark=170)
        val bgColor = when {
            mgdl < 70   -> 0xFFFF5252.toInt()
            mgdl <= 170  -> 0xFF4CAF50.toInt()
            else         -> 0xFFFFC107.toInt()
        }
        binding.latestValueText.setTextColor(bgColor)

        // ── Slope / Direction / Delta ──
        val slope = SlopeDirectionCalculator.calculate(list)

        val arrow = SlopeDirectionCalculator.directionToArrow(slope.directionName)
        binding.directionArrowText.text = arrow
        binding.directionArrowText.setTextColor(bgColor)

        binding.minutesAgoText.text = if (slope.minutesAgo <= 0) {
            "Just now"
        } else {
            "${slope.minutesAgo} min ago"
        }

        if (!slope.isValid || slope.deltaPerFiveMin.isNaN()) {
            binding.deltaText.text = "Delta: ???"
        } else {
            val delta5 = slope.deltaPerFiveMin
            if (currentOutputUnit == "mmol") {
                val deltaMMol = delta5 / 18.0
                binding.deltaText.text = String.format(Locale.US, "Delta: %+.2f mmol/L per 5-min", deltaMMol)
            } else {
                binding.deltaText.text = String.format(Locale.US, "Delta: %+.1f mg/dL per 5-min", delta5)
            }
        }
    }

    // ─── Charts ──────────────────────────────────────────────────────────

    private fun renderDetailChart(list: List<BgReadingEntity>) {
        ChartHelper.renderDetail(
            binding.glucoseChartDetail,
            list,
            currentOutputUnit,
            vm.dayStartMs(),
            currentWindowHours,
            vm.isToday(),
            prefs.calibrationEnabled,
            prefs.dLowBlood,
            prefs.dHighBlood
        )
    }

    private fun renderOverview() {
        val data = if (overviewCache.isNotEmpty()) overviewCache else latestReadingsCache
        ChartHelper.renderOverview(
            binding.glucoseChartOverview,
            data,
            currentOutputUnit,
            vm.dayStartMs(),
            currentWindowHours,
            vm.isToday(),
            prefs.dLowBlood,
            prefs.dHighBlood
        )
    }
    // ─── Statistics (xDrip-aligned glucose variability block) ────────────

    /**
     * Calculates the currently visible Glucose Variability statistics.
     *
     * The selected chip (3h / 6h / 12h / 24h) already controls [latestReadingsCache],
     * so this method simply forwards the visible rows to the dedicated calculator.
     * Keeping the math in a dedicated file makes the formulas easier to audit
     * against xDrip and avoids hidden UI-side inconsistencies.
     */
    private fun calculateStats(list: List<BgReadingEntity>): GlucoseVariabilityCalculator.Result? {
        return GlucoseVariabilityCalculator.calculate(
            readings = list,
            lowThresholdMgdl = prefs.dLowBlood,
            highThresholdMgdl = prefs.dHighBlood
        )
    }

    /**
     * Renders the 6-item Glucose Variability section on the main graph screen.
     *
     * Displayed items:
     * 1. SD
     * 2. CV(%)
     * 3. Minimum Value
     * 4. Maximum Value
     * 5. HbA1c est.
     * 6. Range(in/high/low)
     *
     * Range thresholds come from Alarm Settings ([AppPrefs.dLowBlood] and
     * [AppPrefs.dHighBlood]). That means changing alarm thresholds updates the
     * label and the in/high/low breakdown on this screen as well.
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
     * Formats an alarm threshold for the dynamic Range label.
     *
     * The label intentionally shows the actual threshold values so the user can
     * immediately see which "in/high/low" cut-offs are being used without opening
     * Alarm Settings again.
     */
    private fun formatGlucoseThresholdForStatsLabel(valueMgdl: Double): String {
        return if (currentOutputUnit == "mmol") {
            String.format(Locale.US, "%.1f mmol/L", valueMgdl / 18.0)
        } else {
            String.format(Locale.US, "%.0f mg/dL", valueMgdl)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun updateChipState(hours: Int) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        val chips = listOf(binding.chip3h to 3, binding.chip6h to 6, binding.chip12h to 12, binding.chip24h to 24)
        for ((chip, h) in chips) {
            chip.setTextColor(if (hours == h) sel else nor)
            chip.setTypeface(
                null,
                if (hours == h) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }


    private fun setupBottomNav() {
        // Graph: already on this screen.
        binding.bottomNav.btnNavGraph.setOnClickListener {
            // optional: scroll to top
            binding.root.scrollTo(0, 0)
        }

        binding.bottomNav.btnNavStatistics.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavCarbInsulin.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavTools.setOnClickListener {
            CalibrationDialogHelper.showCalibrationMenu(this, prefs) {
                renderAll()
            }
        }
        binding.bottomNav.btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsMenuActivity::class.java))
        }
    }

    private fun markSelectedTab(isGraph: Boolean) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        binding.bottomNav.btnNavGraph.setTextColor(if (isGraph) sel else nor)
        binding.bottomNav.btnNavSettings.setTextColor(if (isGraph) nor else sel)
        binding.bottomNav.btnNavStatistics.setTextColor(nor)
        binding.bottomNav.btnNavCarbInsulin.setTextColor(nor)
        binding.bottomNav.btnNavTools.setTextColor(nor)
    }
}
