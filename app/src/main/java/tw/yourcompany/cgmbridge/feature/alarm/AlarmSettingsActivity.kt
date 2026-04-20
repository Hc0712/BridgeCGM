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
import tw.yourcompany.cgmbridge.databinding.ActivityAlarmSettingsBinding
import tw.yourcompany.cgmbridge.feature.alarm.AlarmSoundPlayer
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import java.util.Locale

class AlarmSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmSettingsBinding
    private lateinit var prefs: AppPrefs

    private companion object {
        const val MMOL_FACTOR = 18.0
        const val DEFAULT_MAX_MGDL = 250.0
    }

    private fun maybeWarnMutedAlarmVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            Toast.makeText(this, getString(R.string.alarm_volume_muted_hint), Toast.LENGTH_LONG).show()
        }
    }

    private fun playHighPreview() {
        maybeWarnMutedAlarmVolume()
        AlarmSoundPlayer.playByName(this, "high", prefs.alarmHighDurationSec)
    }

    private fun playLowPreview() {
        maybeWarnMutedAlarmVolume()
        AlarmSoundPlayer.playByName(this, "low", prefs.alarmLowDurationSec)
    }

    private fun playUrgentPreview() {
        maybeWarnMutedAlarmVolume()
        AlarmSoundPlayer.playByName(this, "urgent", prefs.alarmUrgentLowDurationSec)
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

        binding.switchHigh.setOnCheckedChangeListener { _, isChecked -> prefs.alarmHighEnabled = isChecked }
        binding.switchLow.setOnCheckedChangeListener { _, isChecked -> prefs.alarmLowEnabled = isChecked }
        binding.switchUrgentLow.setOnCheckedChangeListener { _, isChecked -> prefs.alarmUrgentLowEnabled = isChecked }

        binding.rowHighThreshold.setOnClickListener {
            playHighPreview()
            val isMmol = prefs.outputUnit == "mmol"
            val min = if (isMmol) 140.0 / MMOL_FACTOR else 140.0
            val max = if (isMmol) DEFAULT_MAX_MGDL / MMOL_FACTOR else DEFAULT_MAX_MGDL
            showDoubleInput(getString(R.string.alarm_high_threshold), prefs.glucoseForDisplay(prefs.dHighBlood), min, max) { value ->
                prefs.dHighBlood = prefs.glucoseFromDisplay(value)
                renderAll()
            }
        }

        binding.rowHighMethod.setOnClickListener {
            playHighPreview()
            prefs.alarmHighMethod = "sound"
            Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
            renderAll()
        }
        binding.rowHighInterval.setOnClickListener {
            playHighPreview()
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmHighIntervalMin, 1, 1440) {
                prefs.alarmHighIntervalMin = it
                renderAll()
            }
        }
        binding.rowHighDuration.setOnClickListener {
            playHighPreview()
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmHighDurationSec, 1, 120) {
                prefs.alarmHighDurationSec = it
                renderAll()
            }
        }

        binding.rowLowThreshold.setOnClickListener {
            playLowPreview()
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
            playUrgentPreview()
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
            playLowPreview()
            Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
        }
        binding.rowUrgentLowMethod.setOnClickListener {
            playUrgentPreview()
            Toast.makeText(this, getString(R.string.alarm_future_work), Toast.LENGTH_SHORT).show()
        }
        binding.rowLowInterval.setOnClickListener {
            playLowPreview()
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmLowIntervalMin, 1, 1440) {
                prefs.alarmLowIntervalMin = it
                renderAll()
            }
        }
        binding.rowLowDuration.setOnClickListener {
            playLowPreview()
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmLowDurationSec, 1, 120) {
                prefs.alarmLowDurationSec = it
                renderAll()
            }
        }
        binding.rowUrgentLowInterval.setOnClickListener {
            playUrgentPreview()
            showIntInput(getString(R.string.alarm_reminder_interval), prefs.alarmUrgentLowIntervalMin, 1, 1440) {
                prefs.alarmUrgentLowIntervalMin = it
                renderAll()
            }
        }
        binding.rowUrgentLowDuration.setOnClickListener {
            playUrgentPreview()
            showIntInput(getString(R.string.alarm_alert_duration), prefs.alarmUrgentLowDurationSec, 1, 120) {
                prefs.alarmUrgentLowDurationSec = it
                renderAll()
            }
        }
    }

    private fun renderAll() {
        binding.switchHigh.isChecked = prefs.alarmHighEnabled
        binding.switchLow.isChecked = prefs.alarmLowEnabled
        binding.switchUrgentLow.isChecked = prefs.alarmUrgentLowEnabled
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
