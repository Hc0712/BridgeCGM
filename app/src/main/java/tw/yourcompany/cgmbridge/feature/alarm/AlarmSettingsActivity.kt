package tw.yourcompany.cgmbridge.feature.alarm

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.constants.GlucoseConstants.MMOL_FACTOR
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.databinding.ActivityAlarmSettingsBinding
import java.util.Locale

/**
 * Reminder Setting screen.
 *
 * Patch-specific behavior change:
 * - this screen no longer owns the “Enable alarm notifications” permission prompt;
 * - that dialog was moved to SetupActivity so all required permissions can be granted together;
 * - this screen now focuses only on alarm thresholds, intervals, durations, and test playback;
 * - repeated notification taps reuse the same activity instance so the dialog can be be brought
 *   back to the foreground without creating duplicate floating windows.
 */
class AlarmSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmSettingsBinding
    private lateinit var prefs: AppPrefs

    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)
        applyNotificationLaunchPresentationIfNeeded(intent)

        onBackPressedDispatcher.addCallback(this) { saveAndFinish() }
        bindViews()
        renderAll()
    }

    /**
     * Called when Android routes a new notification tap into the existing singleTask instance.
     *
     * The activity is intentionally reused instead of recreated so the Reminder settings dialog can
     * be brought to the foreground without stacking duplicate floating windows. Re-applying the
     * lightweight presentation hints keeps the reused window feeling focused on OEM builds where a
     * floating activity can briefly appear behind the current top window before focus settles.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNotificationLaunchPresentationIfNeeded(intent)
    }

    /**
     * Re-applies a small amount of window presentation state when Reminder settings is opened from
     * the alarm notification.
     *
     * Important constraints:
     * - This method stays inside the normal Activity window APIs.
     * - It does not request overlay permissions or alter the window type.
     * - The goal is simply to help the existing dialog-themed Activity win focus and feel visibly
     *   foregrounded after Android delivers a direct notification launch or onNewIntent() reuse.
     */
    private fun applyNotificationLaunchPresentationIfNeeded(intent: Intent?) {
        if (intent?.getBooleanExtra(
                AlarmSettingsLaunchIntentFactory.EXTRA_FROM_NOTIFICATION_ACTION,
                false
            ) != true
        ) {
            return
        }

        val activityWindow = window ?: return
        val attrs = activityWindow.attributes
        attrs.dimAmount = maxOf(attrs.dimAmount, 0.6f)
        activityWindow.attributes = attrs

        activityWindow.decorView.post {
            if (!isFinishing && !isDestroyed) {
                activityWindow.decorView.requestFocus()
            }
        }
    }

    private fun bindViews() {
        binding.btnBack.setOnClickListener { saveAndFinish() }

        binding.switchHigh.setOnCheckedChangeListener { _, enabled ->
            if (!suppressSwitchCallback) handleEnableToggle(AlarmKind.HIGH, enabled)
        }
        binding.switchLow.setOnCheckedChangeListener { _, enabled ->
            if (!suppressSwitchCallback) handleEnableToggle(AlarmKind.LOW, enabled)
        }
        binding.switchUrgentLow.setOnCheckedChangeListener { _, enabled ->
            if (!suppressSwitchCallback) handleEnableToggle(AlarmKind.URGENT_LOW, enabled)
        }

        binding.rowHighThreshold.setOnClickListener {
            showDoubleInput(
                title = getString(R.string.alarm_high_threshold),
                currentDisplayValue = prefs.glucoseForDisplay(prefs.dHighBlood),
                minDisplay = prefs.glucoseForDisplay(40.0),
                maxDisplay = prefs.glucoseForDisplay(400.0)
            ) { displayValue ->
                prefs.dHighBlood = prefs.glucoseFromDisplay(displayValue)
                renderAll()
            }
        }
        binding.rowLowThreshold.setOnClickListener {
            showDoubleInput(
                title = getString(R.string.alarm_low_threshold),
                currentDisplayValue = prefs.glucoseForDisplay(prefs.dLowBlood),
                minDisplay = prefs.glucoseForDisplay(40.0),
                maxDisplay = prefs.glucoseForDisplay(250.0)
            ) { displayValue ->
                prefs.dLowBlood = prefs.glucoseFromDisplay(displayValue)
                renderAll()
            }
        }
        binding.rowUrgentLowThreshold.setOnClickListener {
            showDoubleInput(
                title = getString(R.string.alarm_urgent_low_threshold),
                currentDisplayValue = prefs.glucoseForDisplay(prefs.dUrgentLowBlood),
                minDisplay = prefs.glucoseForDisplay(40.0),
                maxDisplay = prefs.glucoseForDisplay(150.0)
            ) { displayValue ->
                prefs.dUrgentLowBlood = prefs.glucoseFromDisplay(displayValue)
                renderAll()
            }
        }

        binding.rowHighMethod.setOnClickListener {
            Toast.makeText(this, getString(R.string.alarm_sound_label), Toast.LENGTH_SHORT).show()
        }
        binding.rowLowMethod.setOnClickListener {
            Toast.makeText(this, getString(R.string.alarm_sound_label), Toast.LENGTH_SHORT).show()
        }
        binding.rowUrgentLowMethod.setOnClickListener {
            Toast.makeText(this, getString(R.string.alarm_sound_label), Toast.LENGTH_SHORT).show()
        }

        binding.rowHighInterval.setOnClickListener {
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmHighIntervalMin, 1, 120) {
                prefs.alarmHighIntervalMin = it
                renderAll()
            }
        }
        binding.rowLowInterval.setOnClickListener {
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmLowIntervalMin, 1, 120) {
                prefs.alarmLowIntervalMin = it
                renderAll()
            }
        }
        binding.rowUrgentLowInterval.setOnClickListener {
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmUrgentLowIntervalMin, 1, 120) {
                prefs.alarmUrgentLowIntervalMin = it
                renderAll()
            }
        }

        binding.rowHighDuration.setOnClickListener {
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmHighDurationSec, 1, 60) {
                prefs.alarmHighDurationSec = it
                renderAll()
            }
        }
        binding.rowLowDuration.setOnClickListener {
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmLowDurationSec, 1, 60) {
                prefs.alarmLowDurationSec = it
                renderAll()
            }
        }
        binding.rowUrgentLowDuration.setOnClickListener {
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmUrgentLowDurationSec, 1, 60) {
                prefs.alarmUrgentLowDurationSec = it
                renderAll()
            }
        }
    }

    /**
     * Handles the exact workflow required for the On/Off switches.
     *
     * OFF:
     * - persist disabled state
     * - stop any currently playing audio
     * - clear the corresponding replay timer and runtime state
     *
     * ON:
     * - persist enabled state
     * - play one immediate confirmation sound
     * - do not request notification permission here anymore; SetupActivity owns that flow now
     * - do not mark the alarm active here; the glucose evaluator owns that state
     */
    private fun handleEnableToggle(kind: AlarmKind, enabled: Boolean) {
        when (kind) {
            AlarmKind.HIGH -> prefs.alarmHighEnabled = enabled
            AlarmKind.LOW -> prefs.alarmLowEnabled = enabled
            AlarmKind.URGENT_LOW -> prefs.alarmUrgentLowEnabled = enabled
        }

        if (enabled) {
            playManualEnableSound(kind)
        } else {
            val rule = when (kind) {
                AlarmKind.HIGH -> AlarmConfig.high(prefs)
                AlarmKind.LOW -> AlarmConfig.low(prefs)
                AlarmKind.URGENT_LOW -> AlarmConfig.urgentLow(prefs)
            }
            ReminderAlertEvaluator.clearOne(this, prefs, rule)
            AlarmSoundPlayer.stop()
        }

        renderAll()
    }

    private fun playManualEnableSound(kind: AlarmKind) {
        when (kind) {
            AlarmKind.HIGH -> AlarmSoundPlayer.playByName(this, "high", prefs.alarmHighDurationSec)
            AlarmKind.LOW -> AlarmSoundPlayer.playByName(this, "low", prefs.alarmLowDurationSec)
            AlarmKind.URGENT_LOW -> AlarmSoundPlayer.playByName(this, "urgent", prefs.alarmUrgentLowDurationSec)
        }
    }

    private fun renderAll() {
        suppressSwitchCallback = true
        try {
            binding.switchHigh.isChecked = prefs.alarmHighEnabled
            binding.switchLow.isChecked = prefs.alarmLowEnabled
            binding.switchUrgentLow.isChecked = prefs.alarmUrgentLowEnabled
        } finally {
            suppressSwitchCallback = false
        }

        binding.tvHighThresholdValue.text = formatGlucoseValue(prefs.dHighBlood)
        binding.tvLowThresholdValue.text = formatGlucoseValue(prefs.dLowBlood)
        binding.tvUrgentLowThresholdValue.text = formatGlucoseValue(prefs.dUrgentLowBlood)
        binding.tvHighMethodValue.text = formatMethodValue(prefs.alarmHighMethod)
        binding.tvLowMethodValue.text = getString(R.string.alarm_sound_label)
        binding.tvUrgentLowMethodValue.text = getString(R.string.alarm_sound_label)
        binding.tvHighIntervalValue.text = getString(R.string.alarm_minutes_suffix, prefs.alarmHighIntervalMin)
        binding.tvHighDurationValue.text = getString(R.string.alarm_seconds_suffix, prefs.alarmHighDurationSec)
        binding.tvLowIntervalValue.text = getString(R.string.alarm_minutes_suffix, prefs.alarmLowIntervalMin)
        binding.tvLowDurationValue.text = getString(R.string.alarm_seconds_suffix, prefs.alarmLowDurationSec)
        binding.tvUrgentLowIntervalValue.text = getString(R.string.alarm_minutes_suffix, prefs.alarmUrgentLowIntervalMin)
        binding.tvUrgentLowDurationValue.text = getString(R.string.alarm_seconds_suffix, prefs.alarmUrgentLowDurationSec)
    }

    private fun formatMethodValue(stored: String): String = when (stored.trim().lowercase(Locale.US)) {
        "sound" -> getString(R.string.alarm_sound_label)
        else -> stored
    }

    private fun formatGlucoseValue(valueMgdl: Double): String {
        return if (prefs.outputUnit == "mmol") {
            getString(R.string.alarm_value_mmol, valueMgdl / MMOL_FACTOR)
        } else {
            getString(R.string.alarm_value_mgdl, valueMgdl)
        }
    }

    private fun showDoubleInput(
        title: String,
        currentDisplayValue: Double,
        minDisplay: Double,
        maxDisplay: Double,
        onValid: (Double) -> Unit
    ) {
        val editText = EditText(this).apply {
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
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = editText.text.toString().toDoubleOrNull()
                if (value == null || value < minDisplay || value > maxDisplay) {
                    Toast.makeText(this, getString(R.string.alarm_invalid_range), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onValid(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIntInput(
        title: String,
        currentValue: Int,
        minValue: Int,
        maxValue: Int,
        onValid: (Int) -> Unit
    ) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value == null || value < minValue || value > maxValue) {
                    Toast.makeText(this, getString(R.string.alarm_invalid_range), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onValid(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveAndFinish() {
        AlarmSoundPlayer.stop()
        finish()
    }
}
