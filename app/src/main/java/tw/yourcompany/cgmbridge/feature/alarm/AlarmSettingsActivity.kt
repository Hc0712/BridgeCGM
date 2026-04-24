package tw.yourcompany.cgmbridge.feature.alarm

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.constants.GlucoseConstants
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Reminder settings screen.
 *
 * Why this file was rewritten:
 * - the uploaded source snapshot did not expose the previous view-binding implementation,
 *   so this version builds the required settings UI programmatically to keep the patch
 *   self-contained and compilable;
 * - every edit is saved only after the user confirms with OK;
 * - threshold edits are validated against the required per-alert ranges in mg/dL while
 *   still respecting the currently selected display unit (mg/dL or mmol/L);
 * - after a successful save, the screen plays a preview sound that reflects the latest
 *   persisted configuration for the edited alert group.
 */
class AlarmSettingsActivity : AppCompatActivity() {
    override fun onPause() {
        super.onPause()
        saveAllSettings()
    }

    /** Persistent app settings wrapper used by the Reminder screen. */
    private lateinit var prefs: AppPrefs

    /** Root container that holds the dynamically created controls. */
    private lateinit var contentContainer: LinearLayout

    /** UI holder for the High alert section. */
    private lateinit var highSection: AlarmSectionViews

    /** UI holder for the Low alert section. */
    private lateinit var lowSection: AlarmSectionViews

    /** UI holder for the Urgent Low alert section. */
    private lateinit var urgentSection: AlarmSectionViews

    // Temporary (unsaved) settings for all fields
    private var tempAlarmHighEnabled: Boolean = false
    private var tempAlarmLowEnabled: Boolean = false
    private var tempAlarmUrgentLowEnabled: Boolean = false
    private var tempDHighBlood: Double = 0.0
    private var tempDLowBlood: Double = 0.0
    private var tempDUrgentLowBlood: Double = 0.0
    private var tempAlarmHighIntervalMin: Int = 0
    private var tempAlarmLowIntervalMin: Int = 0
    private var tempAlarmUrgentLowIntervalMin: Int = 0
    private var tempAlarmHighDurationSec: Int = 0
    private var tempAlarmLowDurationSec: Int = 0
    private var tempAlarmUrgentLowDurationSec: Int = 0
    private var tempAlarmHighMethod: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        // Initialize temp values from prefs
        tempAlarmHighEnabled = prefs.alarmHighEnabled
        tempAlarmLowEnabled = prefs.alarmLowEnabled
        tempAlarmUrgentLowEnabled = prefs.alarmUrgentLowEnabled
        tempDHighBlood = prefs.dHighBlood
        tempDLowBlood = prefs.dLowBlood
        tempDUrgentLowBlood = prefs.dUrgentLowBlood
        tempAlarmHighIntervalMin = prefs.alarmHighIntervalMin
        tempAlarmLowIntervalMin = prefs.alarmLowIntervalMin
        tempAlarmUrgentLowIntervalMin = prefs.alarmUrgentLowIntervalMin
        tempAlarmHighDurationSec = prefs.alarmHighDurationSec
        tempAlarmLowDurationSec = prefs.alarmLowDurationSec
        tempAlarmUrgentLowDurationSec = prefs.alarmUrgentLowDurationSec
        tempAlarmHighMethod = prefs.alarmHighMethod

        buildScreen()
        refreshAllSections()

        onBackPressedDispatcher.addCallback(this) {
            saveAllSettingsAndExit()
        }
    }

    /**
     * Builds the entire Reminder settings UI using standard Android views.
     * This keeps the patch independent from any missing/truncated XML identifiers.
     */
    private fun buildScreen() {
        val density = resources.displayMetrics.density
        val outerPadding = (16f * density).toInt()
        val sectionPadding = (14f * density).toInt()
        val rowPadding = (10f * density).toInt()

        val scrollView = ScrollView(this)
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
        }
        scrollView.addView(
            contentContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scrollView)


        // Top row: title + enlarged ">" button, aligned with other arrows/switches
        val rightWidgetWidth = (56 * density).toInt() // Use same width as other rows
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, outerPadding, 0, outerPadding)
        }
        val title = TextView(this).apply {
            text = getString(R.string.reminder_settings_title)
            textSize = 22f
        }
        val saveButton = TextView(this).apply {
            text = "<"
            textSize = 28f // Enlarge the top arrow
            gravity = android.view.Gravity.CENTER
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                saveAllSettingsAndExit()
            }
            // Make it look like a button: rounded corners and background
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(0xFFE0E0E0.toInt()) // light gray background
                setStroke((1 * density).toInt(), 0xFF888888.toInt()) // gray border
            }
        }
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val saveParams = LinearLayout.LayoutParams(rightWidgetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        titleRow.addView(title, titleParams)
        titleRow.addView(saveButton, saveParams)
        contentContainer.addView(titleRow)

        highSection = addAlarmSection(
            title = getString(R.string.alarm_high_title),
            sectionPadding = sectionPadding,
            rowPadding = rowPadding,
            onToggle = { enabled ->
                prefs.alarmHighEnabled = enabled
                refreshSection(AlarmKind.HIGH)
                playPreviewFor(AlarmKind.HIGH)
            },
            onEditThreshold = { showThresholdDialog(AlarmKind.HIGH) },
            onEditMethod = { showMethodDialog(AlarmKind.HIGH) },
            onEditInterval = { showIntDialog(AlarmKind.HIGH, FieldType.INTERVAL) },
            onEditDuration = { showIntDialog(AlarmKind.HIGH, FieldType.DURATION) }
        )

        lowSection = addAlarmSection(
            title = getString(R.string.alarm_low_title),
            sectionPadding = sectionPadding,
            rowPadding = rowPadding,
            onToggle = { enabled ->
                prefs.alarmLowEnabled = enabled
                refreshSection(AlarmKind.LOW)
                playPreviewFor(AlarmKind.LOW)
            },
            onEditThreshold = { showThresholdDialog(AlarmKind.LOW) },
            onEditMethod = { showMethodDialog(AlarmKind.LOW) },
            onEditInterval = { showIntDialog(AlarmKind.LOW, FieldType.INTERVAL) },
            onEditDuration = { showIntDialog(AlarmKind.LOW, FieldType.DURATION) }
        )

        urgentSection = addAlarmSection(
            title = getString(R.string.alarm_urgent_low_title),
            sectionPadding = sectionPadding,
            rowPadding = rowPadding,
            onToggle = { enabled ->
                prefs.alarmUrgentLowEnabled = enabled
                refreshSection(AlarmKind.URGENT_LOW)
                playPreviewFor(AlarmKind.URGENT_LOW)
            },
            onEditThreshold = { showThresholdDialog(AlarmKind.URGENT_LOW) },
            onEditMethod = { showMethodDialog(AlarmKind.URGENT_LOW) },
            onEditInterval = { showIntDialog(AlarmKind.URGENT_LOW, FieldType.INTERVAL) },
            onEditDuration = { showIntDialog(AlarmKind.URGENT_LOW, FieldType.DURATION) }
        )
    }

    /**
     * Adds one alert section to the screen and returns the view references required for
     * later refreshes.
     */
    private fun addAlarmSection(
        title: String,
        sectionPadding: Int,
        rowPadding: Int,
        onToggle: (Boolean) -> Unit,
        onEditThreshold: () -> Unit,
        onEditMethod: () -> Unit,
        onEditInterval: () -> Unit,
        onEditDuration: () -> Unit
    ): AlarmSectionViews {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sectionPadding, sectionPadding, sectionPadding, sectionPadding)
        }
        val layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = sectionPadding
        }
        contentContainer.addView(container, layoutParams)

        // Consistent width for right-side widgets (switch and arrows)
        val density = resources.displayMetrics.density
        val rightWidgetWidth = (56 * density).toInt() // 56dp for switch/arrow alignment

        // Title row with title (left) and switch (right)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, rowPadding, 0, rowPadding)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
        }
        val enabledSwitch = SwitchCompat(this).apply {
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        val titleViewParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val switchParams = LinearLayout.LayoutParams(rightWidgetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        titleRow.addView(titleView, titleViewParams)
        titleRow.addView(enabledSwitch, switchParams)
        container.addView(titleRow)

        val (thresholdView, thresholdRow) = createActionRow(container, rowPadding, onEditThreshold)
        val (methodView, methodRow) = createActionRow(container, rowPadding, onEditMethod)
        val (intervalView, intervalRow) = createActionRow(container, rowPadding, onEditInterval)
        val (durationView, durationRow) = createActionRow(container, rowPadding, onEditDuration)

        return AlarmSectionViews(
            container = container,
            enabledSwitch = enabledSwitch,
            thresholdView = thresholdView,
            methodView = methodView,
            intervalView = intervalView,
            durationView = durationView
        )
    }

    /**
     * Creates a horizontal row with a label/value TextView (left, weight=1) and a right-aligned arrow TextView.
     * Returns a pair: (labelView, rowLayout).
     */
    private fun createActionRow(
        parent: LinearLayout,
        rowPadding: Int,
        onClick: () -> Unit
    ): Pair<TextView, LinearLayout> {
        val density = resources.displayMetrics.density
        val rightWidgetWidth = (56 * density).toInt() // 56dp for switch/arrow alignment
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, rowPadding, 0, rowPadding)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
        val labelView = TextView(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val arrowView = TextView(this).apply {
            text = ">"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(rightWidgetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(labelView)
        row.addView(arrowView)
        parent.addView(row)
        return Pair(labelView, row)
    }

    /** Refreshes every alert section after any alarm value changes. */
    private fun refreshAllSections() {
        refreshSection(AlarmKind.HIGH)
        refreshSection(AlarmKind.LOW)
        refreshSection(AlarmKind.URGENT_LOW)
    }

    /**
     * Redraws one alert section from the latest persisted configuration.
     * The UI always reads from SharedPreferences so preview playback and labels stay in sync.
     */
    private fun refreshSection(kind: AlarmKind) {
        val section = sectionFor(kind)
        val enabled = isEnabled(kind)
        section.enabledSwitch.setOnCheckedChangeListener(null)
        section.enabledSwitch.isChecked = enabled
        section.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            when (kind) {
                AlarmKind.HIGH -> tempAlarmHighEnabled = isChecked
                AlarmKind.LOW -> tempAlarmLowEnabled = isChecked
                AlarmKind.URGENT_LOW -> tempAlarmUrgentLowEnabled = isChecked
            }
            refreshSection(kind)
            playPreviewFor(kind)
        }

        // Set only the label/value text, not the arrow, for each row
        section.thresholdView.text = getThresholdLabel(kind)
        section.methodView.text = getString(R.string.alarm_reminder_method) + ": " + getString(R.string.alarm_sound_label)
        section.intervalView.text = getString(R.string.alarm_reminder_interval) + ": " + getString(R.string.alarm_minutes_suffix, intervalMinutes(kind))
        section.durationView.text = getString(R.string.alarm_alert_duration) + ": " + getString(R.string.alarm_seconds_suffix, durationSeconds(kind))
    }

    /** Returns the correct UI holder for one alert kind. */
    private fun sectionFor(kind: AlarmKind): AlarmSectionViews = when (kind) {
        AlarmKind.HIGH -> highSection
        AlarmKind.LOW -> lowSection
        AlarmKind.URGENT_LOW -> urgentSection
    }

    /** Returns the persisted enabled state for one alert kind. */
    private fun isEnabled(kind: AlarmKind): Boolean = when (kind) {
        AlarmKind.HIGH -> tempAlarmHighEnabled
        AlarmKind.LOW -> tempAlarmLowEnabled
        AlarmKind.URGENT_LOW -> tempAlarmUrgentLowEnabled
    }

    /** Returns the persisted threshold in mg/dL for one alert kind. */
    private fun thresholdMgdl(kind: AlarmKind): Double = when (kind) {
        AlarmKind.HIGH -> tempDHighBlood
        AlarmKind.LOW -> tempDLowBlood
        AlarmKind.URGENT_LOW -> tempDUrgentLowBlood
    }

    /** Persists one threshold value in mg/dL after validation succeeds. */
    private fun saveThresholdMgdl(kind: AlarmKind, valueMgdl: Double) {
        when (kind) {
            AlarmKind.HIGH -> tempDHighBlood = valueMgdl
            AlarmKind.LOW -> tempDLowBlood = valueMgdl
            AlarmKind.URGENT_LOW -> tempDUrgentLowBlood = valueMgdl
        }
    }

    /** Returns the persisted reminder interval in minutes for one alert kind. */
    private fun intervalMinutes(kind: AlarmKind): Int = when (kind) {
        AlarmKind.HIGH -> tempAlarmHighIntervalMin
        AlarmKind.LOW -> tempAlarmLowIntervalMin
        AlarmKind.URGENT_LOW -> tempAlarmUrgentLowIntervalMin
    }

    /** Persists the reminder interval for one alert kind. */
    private fun saveIntervalMinutes(kind: AlarmKind, value: Int) {
        when (kind) {
            AlarmKind.HIGH -> tempAlarmHighIntervalMin = value
            AlarmKind.LOW -> tempAlarmLowIntervalMin = value
            AlarmKind.URGENT_LOW -> tempAlarmUrgentLowIntervalMin = value
        }
    }

    /** Returns the persisted alert duration in seconds for one alert kind. */
    private fun durationSeconds(kind: AlarmKind): Int = when (kind) {
        AlarmKind.HIGH -> tempAlarmHighDurationSec
        AlarmKind.LOW -> tempAlarmLowDurationSec
        AlarmKind.URGENT_LOW -> tempAlarmUrgentLowDurationSec
    }

    /** Persists the alert duration for one alert kind. */
    private fun saveDurationSeconds(kind: AlarmKind, value: Int) {
        when (kind) {
            AlarmKind.HIGH -> tempAlarmHighDurationSec = value
            AlarmKind.LOW -> tempAlarmLowDurationSec = value
            AlarmKind.URGENT_LOW -> tempAlarmUrgentLowDurationSec = value
        }
    }

    /**
     * Builds the threshold row text shown in each alert section.
     * The display unit follows the app-wide output unit preference.
     */
    private fun getThresholdLabel(kind: AlarmKind): String {
        val labelRes = when (kind) {
            AlarmKind.HIGH -> R.string.alarm_high_threshold
            AlarmKind.LOW -> R.string.alarm_low_threshold
            AlarmKind.URGENT_LOW -> R.string.alarm_urgent_low_threshold
        }
        return getString(labelRes) + ": " + formatGlucose(thresholdMgdl(kind))
    }

    /** Shows the threshold input dialog for one alert kind. */
    private fun showThresholdDialog(kind: AlarmKind) {
        val currentDisplayValue = displayValueFor(thresholdMgdl(kind))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(
                if (prefs.outputUnit == "mmol") {
                    String.format(Locale.US, "%.1f", currentDisplayValue)
                } else {
                    String.format(Locale.US, "%.0f", currentDisplayValue)
                }
            )
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(getThresholdDialogTitle(kind))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                val displayValue = entered.toDoubleOrNull()
                if (displayValue == null) {
                    showInvalidRangeWarning()
                    return@setPositiveButton
                }
                val mgdlValue = mgdlValueFrom(displayValue)
                if (!isThresholdInRange(kind, mgdlValue)) {
                    showInvalidRangeWarning()
                    return@setPositiveButton
                }
                saveThresholdMgdl(kind, mgdlValue)
                refreshSection(kind)
                playPreviewFor(kind)
            }
            .show()
    }

    /** Returns the title string used by the threshold edit dialog for one alert kind. */
    private fun getThresholdDialogTitle(kind: AlarmKind): String = when (kind) {
        AlarmKind.HIGH -> getString(R.string.alarm_high_threshold)
        AlarmKind.LOW -> getString(R.string.alarm_low_threshold)
        AlarmKind.URGENT_LOW -> getString(R.string.alarm_urgent_low_threshold)
    }

    /**
     * Shows the method dialog. Only Sound is currently implemented in the source project,
     * so this dialog persists that supported value and still routes through the common
     * preview flow after the user confirms with OK.
     */
    private fun showMethodDialog(kind: AlarmKind) {
        val items = arrayOf(getString(R.string.alarm_sound_label))
        AlertDialog.Builder(this)
            .setTitle(R.string.alarm_reminder_method)
            .setSingleChoiceItems(items, 0, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                when (kind) {
                    AlarmKind.HIGH -> tempAlarmHighMethod = AlarmMethod.SOUND.wireValue
                    AlarmKind.LOW -> { /* Low and urgent-low only support sound in current prefs */ }
                    AlarmKind.URGENT_LOW -> { /* Low and urgent-low only support sound in current prefs */ }
                }
                refreshSection(kind)
                playPreviewFor(kind)
            }
            .show()
    }

    /**
     * Shows an integer input dialog used by both the interval and duration controls.
     * Values are saved only after a positive integer is confirmed with OK.
     */
    private fun showIntDialog(kind: AlarmKind, fieldType: FieldType) {
        val currentValue = when (fieldType) {
            FieldType.INTERVAL -> intervalMinutes(kind)
            FieldType.DURATION -> durationSeconds(kind)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelection(text.length)
        }
        val title = when (fieldType) {
            FieldType.INTERVAL -> getString(R.string.alarm_reminder_interval)
            FieldType.DURATION -> getString(R.string.alarm_alert_duration)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValue = input.text?.toString()?.trim()?.toIntOrNull()
                if (newValue == null || newValue <= 0) {
                    Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                when (fieldType) {
                    FieldType.INTERVAL -> saveIntervalMinutes(kind, newValue)
                    FieldType.DURATION -> saveDurationSeconds(kind, newValue)
                }
                refreshSection(kind)
                playPreviewFor(kind)
            }
            .show()
    }

    /**
     * Plays a preview sound for the edited alert group using the latest persisted values.
     * If the alert group is disabled, the preview does not play and any active preview is
     * stopped to match the requested behavior.
     */
    private fun playPreviewFor(kind: AlarmKind) {
        if (!isEnabled(kind)) {
            AlarmSoundPlayer.stop()
            return
        }
        val method = when (kind) {
            AlarmKind.HIGH -> tempAlarmHighMethod
            AlarmKind.LOW -> AlarmMethod.SOUND.wireValue
            AlarmKind.URGENT_LOW -> AlarmMethod.SOUND.wireValue
        }
        if (method != AlarmMethod.SOUND.wireValue) {
            AlarmSoundPlayer.stop()
            return
        }
        val rawName = when (kind) {
            AlarmKind.HIGH -> "high"
            AlarmKind.LOW -> "low"
            AlarmKind.URGENT_LOW -> "urgent"
        }
        AlarmSoundPlayer.playByName(this, rawName, durationSeconds(kind))

    }

    /**
     * Saves all staged settings to prefs.
     */
    private fun saveAllSettings() {
        prefs.alarmHighEnabled = tempAlarmHighEnabled
        prefs.alarmLowEnabled = tempAlarmLowEnabled
        prefs.alarmUrgentLowEnabled = tempAlarmUrgentLowEnabled
        prefs.dHighBlood = tempDHighBlood
        prefs.dLowBlood = tempDLowBlood
        prefs.dUrgentLowBlood = tempDUrgentLowBlood
        prefs.alarmHighIntervalMin = tempAlarmHighIntervalMin
        prefs.alarmLowIntervalMin = tempAlarmLowIntervalMin
        prefs.alarmUrgentLowIntervalMin = tempAlarmUrgentLowIntervalMin
        prefs.alarmHighDurationSec = tempAlarmHighDurationSec
        prefs.alarmLowDurationSec = tempAlarmLowDurationSec
        prefs.alarmUrgentLowDurationSec = tempAlarmUrgentLowDurationSec
        prefs.alarmHighMethod = tempAlarmHighMethod
    }

    /**
     * Saves all staged settings to prefs and exits the activity.
     */
    private fun saveAllSettingsAndExit() {
        saveAllSettings()
        finish()
    }

    /** Displays the project-provided invalid-range warning toast. */
    private fun showInvalidRangeWarning() {
        Toast.makeText(this, getString(R.string.alarm_invalid_range), Toast.LENGTH_SHORT).show()
    }

    /** Converts a stored mg/dL value into the currently selected display unit. */
    private fun displayValueFor(mgdl: Double): Double {
        return if (prefs.outputUnit == "mmol") mgdl / GlucoseConstants.MMOL_FACTOR else mgdl
    }

    /** Converts a displayed value back into mg/dL for validation and persistence. */
    private fun mgdlValueFrom(displayValue: Double): Double {
        return if (prefs.outputUnit == "mmol") displayValue * GlucoseConstants.MMOL_FACTOR else displayValue
    }

    /** Formats one glucose value for section labels in the currently selected unit. */
    private fun formatGlucose(mgdl: Double): String {
        return if (prefs.outputUnit == "mmol") {
            getString(R.string.alarm_value_mmol, mgdl / GlucoseConstants.MMOL_FACTOR)
        } else {
            getString(R.string.alarm_value_mgdl, mgdl)
        }
    }

    /**
     * Validates the threshold range for the given alert kind.
     *
     * Assumption used for safety:
     * - the numeric range is always inclusive between the smaller and larger endpoint,
     *   even when the task description listed the endpoints in descending order.
     */
    private fun isThresholdInRange(kind: AlarmKind, mgdlValue: Double): Boolean {
        val (lower, upper) = when (kind) {
            AlarmKind.HIGH -> orderedRange(
                GlucoseConstants.MIDDLE_MGDL,
                GlucoseConstants.DEFAULT_MAX_MGDL
            )
            AlarmKind.LOW -> orderedRange(
                GlucoseConstants.MIDDLE_MGDL - 1.0,
                GlucoseConstants.URGENT_LOW_DEFAULT_MGDL + 1.0
            )
            AlarmKind.URGENT_LOW -> orderedRange(
                GlucoseConstants.URGENT_LOW_DEFAULT_MGDL,
                GlucoseConstants.DEFAULT_MIN_MGDL
            )
        }
        return mgdlValue in lower..upper
    }

    /** Returns a numeric range as (min, max) regardless of the argument order. */
    private fun orderedRange(a: Double, b: Double): Pair<Double, Double> = min(a, b) to max(a, b)

    /** Simple enum that distinguishes interval edits from duration edits. */
    private enum class FieldType {
        INTERVAL,
        DURATION
    }

    /** Small holder object that keeps the generated views for one alert section together. */
    private data class AlarmSectionViews(
        val container: LinearLayout,
        val enabledSwitch: SwitchCompat,
        val thresholdView: TextView,
        val methodView: TextView,
        val intervalView: TextView,
        val durationView: TextView
    )
}
