package tw.yourcompany.cgmbridge.feature.calibration

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationSettings

/**
 * Shared UI helper used by the Tool button to show the calibration menu.
 */
object CalibrationDialogHelper {

    fun showCalibrationMenu(
        activity: AppCompatActivity,
        prefs: AppPrefs,
        onChanged: () -> Unit = {}
    ) {
        val density = activity.resources.displayMetrics.density
        val outerPadding = (16 * density).toInt()
        val sectionPadding = (12 * density).toInt()
        val blockMargin = (12 * density).toInt()

        var pendingEnabled = prefs.calibrationEnabled
        var pendingAlgorithm = CalibrationSettings.Algorithm.LEVEL_SHIFT
        var pendingLevelShift = prefs.calibrationLevelShift
        var pendingLinearSlope = prefs.calibrationLinearSlope
        var pendingLinearShift = prefs.calibrationLinearShift
        var lastValidLevelShift = pendingLevelShift

        fun formatDouble(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString().trimEnd('0').trimEnd('.')
            }
        }

        fun sectionBackground(selected: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(0xFFF7F7F7.toInt())
                setStroke(if (selected) (2 * density).toInt() else (1 * density).toInt(), if (selected) 0xFF455A64.toInt() else 0xFFB0BEC5.toInt())
            }
        }

        fun createSectionLayout(): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(sectionPadding, sectionPadding, sectionPadding, sectionPadding)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = blockMargin
                }
            }
        }

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val enableSwitch = SwitchCompat(activity).apply {
            text = activity.getString(R.string.calibration_enable_disable)
            isChecked = pendingEnabled
            textSize = 16f
        }
        rootLayout.addView(enableSwitch)

        val algorithmTitle = TextView(activity).apply {
            text = activity.getString(R.string.calibration_select_algorithm)
            textSize = 16f
            setPadding(0, blockMargin, 0, 0)
            setTypeface(typeface, Typeface.BOLD)
        }
        rootLayout.addView(algorithmTitle)

        val levelSection = createSectionLayout()
        val linearSection = createSectionLayout()

        val levelRadio = RadioButton(activity).apply {
            text = activity.getString(R.string.calibration_algorithm_level_shift_formula)
            isChecked = true
            textSize = 15f
        }
        levelSection.addView(levelRadio)

        val levelInputLabel = TextView(activity).apply {
            text = activity.getString(R.string.calibration_shift_label)
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        levelSection.addView(levelInputLabel)

        val levelShiftInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = activity.getString(R.string.calibration_shift_hint)
            setText(formatDouble(pendingLevelShift))
            setSelection(text.length)
        }
        levelSection.addView(levelShiftInput)

        val linearRadio = RadioButton(activity).apply {
            text = activity.getString(R.string.calibration_algorithm_linear_formula)
            isEnabled = false
            isClickable = false
            isFocusable = false
            textSize = 15f
        }
        linearSection.addView(linearRadio)

        val slopeLabel = TextView(activity).apply {
            text = activity.getString(R.string.calibration_slope_label)
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        linearSection.addView(slopeLabel)

        val slopeInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(formatDouble(pendingLinearSlope))
            isEnabled = false
        }
        linearSection.addView(slopeInput)

        val linearShiftLabel = TextView(activity).apply {
            text = activity.getString(R.string.calibration_shift_label)
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        linearSection.addView(linearShiftLabel)

        val linearShiftInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(formatDouble(pendingLinearShift))
            isEnabled = false
        }
        linearSection.addView(linearShiftInput)

        fun refreshSectionStates() {
            levelSection.background = sectionBackground(true)
            linearSection.background = sectionBackground(false)
            levelShiftInput.isEnabled = pendingEnabled
            levelInputLabel.alpha = if (pendingEnabled) 1f else 0.5f
            levelShiftInput.alpha = if (pendingEnabled) 1f else 0.5f
        }

        fun resetTemporaryValues() {
            pendingAlgorithm = CalibrationSettings.Algorithm.LEVEL_SHIFT
            pendingLevelShift = 0.0
            pendingLinearSlope = 1.0
            pendingLinearShift = 0.0
            lastValidLevelShift = 0.0
            levelRadio.isChecked = true
            levelShiftInput.setText(formatDouble(pendingLevelShift))
            slopeInput.setText(formatDouble(pendingLinearSlope))
            linearShiftInput.setText(formatDouble(pendingLinearShift))
        }

        fun validateLevelShift(showError: Boolean = true): Double? {
            val raw = levelShiftInput.text?.toString()?.trim().orEmpty()
            val value = if (raw.isBlank()) 0.0 else raw.toDoubleOrNull()
            if (value == null) {
                if (showError) {
                    levelShiftInput.error = activity.getString(R.string.calibration_shift_number_only)
                }
                levelShiftInput.setText(formatDouble(lastValidLevelShift))
                levelShiftInput.setSelection(levelShiftInput.text?.length ?: 0)
                return null
            }
            if (value < -30.0 || value > 30.0) {
                if (showError) {
                    Toast.makeText(activity, activity.getString(R.string.calibration_over_range), Toast.LENGTH_SHORT).show()
                }
                levelShiftInput.setText(formatDouble(lastValidLevelShift))
                levelShiftInput.setSelection(levelShiftInput.text?.length ?: 0)
                return null
            }
            lastValidLevelShift = value
            pendingLevelShift = value
            return value
        }

        levelShiftInput.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateLevelShift(showError = true)
            }
        }

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            pendingEnabled = checked
            resetTemporaryValues()
            refreshSectionStates()
        }

        val note1 = TextView(activity).apply {
            text = activity.getString(R.string.calibration_notice_line1)
            setPadding(0, blockMargin, 0, (6 * density).toInt())
        }
        val note2 = TextView(activity).apply {
            text = activity.getString(R.string.calibration_notice_line2)
            setPadding(0, 0, 0, blockMargin)
        }

        rootLayout.addView(levelSection)
        rootLayout.addView(linearSection)
        rootLayout.addView(note1)
        rootLayout.addView(note2)

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val okButton = Button(activity).apply {
            text = activity.getString(android.R.string.ok)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        val cancelButton = Button(activity).apply {
            text = activity.getString(android.R.string.cancel)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonRow.addView(okButton)
        buttonRow.addView(cancelButton)
        rootLayout.addView(buttonRow)

        refreshSectionStates()

        val scrollView = ScrollView(activity).apply {
            addView(rootLayout)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.calibration_menu_title)
            .setView(scrollView)
            .create()

        okButton.setOnClickListener {
            if (validateLevelShift(showError = true) == null) {
                return@setOnClickListener
            }

            if (!pendingEnabled) {
                prefs.calibrationEnabled = false
                prefs.calibrationAlgorithm = CalibrationSettings.Algorithm.LEVEL_SHIFT.name
                prefs.calibrationLevelShift = 0.0
                prefs.calibrationLinearSlope = 1.0
                prefs.calibrationLinearShift = 0.0
            } else {
                prefs.calibrationEnabled = true
                prefs.calibrationAlgorithm = pendingAlgorithm.name
                prefs.calibrationLevelShift = pendingLevelShift
                prefs.calibrationLinearSlope = pendingLinearSlope
                prefs.calibrationLinearShift = pendingLinearShift
            }
            onChanged()
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
