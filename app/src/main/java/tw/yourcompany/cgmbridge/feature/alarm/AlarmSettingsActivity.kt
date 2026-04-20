package tw.yourcompany.cgmbridge.feature.alarm

import android.media.AudioManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.databinding.ActivityAlarmSettingsBinding
import java.util.Locale

/**
 * Reminder Setting screen.
 *
 * Important behavioral contract required by this task:
 *  - tapping threshold / method / interval / duration rows must NEVER play alarm;
 *  - switching an alarm OFF must stop playback and clear its runtime state;
 *  - switching an alarm ON must play that alarm sound exactly once.
 */
class AlarmSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmSettingsBinding
    private lateinit var prefs: AppPrefs

    private companion object {
        const val MMOL_FACTOR = 18.0
        const val DEFAULT_MAX_MGDL = 250.0
    }

    private var suppressSwitchCallback = false

    private fun maybeWarnMutedAlarmVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            Toast.makeText(this, getString(R.string.alarm_volume_muted_hint), Toast.LENGTH_LONG).show()
        }
    }

    /** Plays exactly one manual confirmation sound when the user turns an alarm ON. */
    private fun playManualEnableSound(kind: AlarmKind) {
        maybeWarnMutedAlarmVolume()
        when (kind) {
            AlarmKind.HIGH -> AlarmSoundPlayer.playByName(this, "high", prefs.alarmHighDurationSec)
            AlarmKind.LOW -> AlarmSoundPlayer.playByName(this, "low", prefs.alarmLowDurationSec)
            AlarmKind.URGENT_LOW -> AlarmSoundPlayer.playByName(this, "urgent", prefs.alarmUrgentLowDurationSec)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        bindViews()
        renderAll()

        onBackPressedDispatcher.addCallback(this) {
            saveAndFinish()
        }
    }

    private fun bindViews() {
        binding.btnBack.setOnClickListener { saveAndFinish() }

        binding.switchHigh.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            handleEnableToggle(AlarmKind.HIGH, isChecked)
        }
        binding.switchLow.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            handleEnableToggle(AlarmKind.LOW, isChecked)
        }
        binding.switchUrgentLow.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            handleEnableToggle(AlarmKind.URGENT_LOW, isChecked)
        }

        // IMPORTANT: row clicks only edit values. They must never play sound.
        binding.rowHighThreshold.setOnClickListener {
            val isMmol = prefs.outputUnit == "mmol"
            val min = if (isMmol) 140.0 / MMOL_FACTOR else 140.0
            val max = if (isMmol) DEFAULT_MAX_MGDL / MMOL_FACTOR else DEFAULT_MAX_MGDL
            showDoubleInput(getString(R.string.alarm_high_threshold), prefs.glucoseForDisplay(prefs.dHighBlood), min, max) { value ->
                prefs.dHighBlood = prefs.glucoseFromDisplay(value)
                renderAll()
            }
        }
        binding.rowHighMethod.setOnClickListener {
            prefs.alarmHighMethod = "sound"
            Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
            renderAll()
        }
        binding.rowHighInterval.setOnClickListener {
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmHighIntervalMin, 1, 1440) {
                prefs.alarmHighIntervalMin = it
                renderAll()
            }
        }
        binding.rowHighDuration.setOnClickListener {
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmHighDurationSec, 1, 120) {
                prefs.alarmHighDurationSec = it
                renderAll()
            }
        }

        binding.rowLowThreshold.setOnClickListener {
            val isMmol = prefs.outputUnit == "mmol"
            val min = if (isMmol) 54.0 / MMOL_FACTOR else 54.0
            val max = if (isMmol) 108.0 / MMOL_FACTOR else 108.0
            showDoubleInput(getString(R.string.alarm_low_threshold), prefs.glucoseForDisplay(prefs.dLowBlood), min, max) { value ->
                prefs.dLowBlood = prefs.glucoseFromDisplay(value)
                if (prefs.dUrgentLowBlood > prefs.dLowBlood) prefs.dUrgentLowBlood = prefs.dLowBlood
                renderAll()
            }
        }
        binding.rowUrgentLowThreshold.setOnClickListener {
            val isMmol = prefs.outputUnit == "mmol"
            val minMgdl = 39.0
            val maxMgdl = minOf(prefs.dLowBlood, 80.0)
            val min = if (isMmol) minMgdl / MMOL_FACTOR else minMgdl
            val max = if (isMmol) maxMgdl / MMOL_FACTOR else maxMgdl
            showDoubleInput(getString(R.string.alarm_urgent_low_threshold), prefs.glucoseForDisplay(prefs.dUrgentLowBlood), min, max) { value ->
                prefs.dUrgentLowBlood = minOf(prefs.glucoseFromDisplay(value), prefs.dLowBlood)
                renderAll()
            }
        }
        binding.rowLowMethod.setOnClickListener {
            Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
        }
        binding.rowUrgentLowMethod.setOnClickListener {
            Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
        }
        binding.rowLowInterval.setOnClickListener {
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmLowIntervalMin, 1, 1440) {
                prefs.alarmLowIntervalMin = it
                renderAll()
            }
        }
        binding.rowLowDuration.setOnClickListener {
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmLowDurationSec, 1, 120) {
                prefs.alarmLowDurationSec = it
                renderAll()
            }
        }
        binding.rowUrgentLowInterval.setOnClickListener {
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmUrgentLowIntervalMin, 1, 1440) {
                prefs.alarmUrgentLowIntervalMin = it
                renderAll()
            }
        }
        binding.rowUrgentLowDuration.setOnClickListener {
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmUrgentLowDurationSec, 1, 120) {
                prefs.alarmUrgentLowDurationSec = it
                renderAll()
            }
        }
    }

    /**
     * Handles the exact workflow required for the On/Off switches.
     *
     * OFF:
     *  - persist disabled state
     *  - stop any currently playing audio
     *  - clear the corresponding replay timer and runtime state
     *
     * ON:
     *  - persist enabled state
     *  - play one immediate confirmation sound
     *  - do not mark the alarm active here; the glucose evaluator owns that state
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
        return if (prefs.outputUnit == "mmol") getString(R.string.alarm_value_mmol, valueMgdl / MMOL_FACTOR)
        else getString(R.string.alarm_value_mgdl, valueMgdl)
    }

    private fun showDoubleInput(title: String, currentDisplayValue: Double, minDisplay: Double, maxDisplay: Double, onValid: (Double) -> Unit) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (prefs.outputUnit == "mmol") String.format(Locale.US, "%.1f", currentDisplayValue) else String.format(Locale.US, "%.0f", currentDisplayValue))
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

    private fun showIntInput(title: String, currentValue: Int, minValue: Int, maxValue: Int, onValid: (Int) -> Unit) {
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
