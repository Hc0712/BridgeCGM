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
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs

/**
 * Shared UI helper used by the Tool button to show the calibration menu.
 *
 * Patch goals implemented in this file:
 * 1) The first line of the menu shows the latest RAW glucose from the current PRIMARY input.
 * 2) The input-setting warning stays directly below the enable switch and is rendered in red.
 * 3) Reset buttons are removed from the menu.
 * 4) Toggling the enable switch or switching algorithm resets Level Shift / Slop / Shift to 0.
 * 5) Level Shift and Linear Transform are mutually exclusive, but only Level Shift is currently
 *    implemented. Selecting Linear Transform shows only a "Not implemented yet" warning.
 */
object CalibrationDialogHelper {

    /**
     * Shows the calibration menu.
     *
     * @param latestPrimaryReading Latest reading for the current PRIMARY input source.
     * The top line uses only [BgReadingEntity.rawValueMgdl] from this object.
     */
    fun showCalibrationMenu(
        activity: AppCompatActivity,
        prefs: AppPrefs,
        latestPrimaryReading: BgReadingEntity? = null,
        onChanged: () -> Unit = {}
    ) {
        val density = activity.resources.displayMetrics.density
        val outerPadding = (16 * density).toInt()
        val sectionPadding = (12 * density).toInt()
        val blockMargin = (12 * density).toInt()
        val compactGap = (8 * density).toInt()

        var pendingEnabled = prefs.calibrationEnabled
        var pendingAlgorithm = try {
            CalibrationSettings.Algorithm.valueOf(prefs.calibrationAlgorithm)
        } catch (_: Exception) {
            CalibrationSettings.Algorithm.LEVEL_SHIFT
        }
        var pendingLevelShift = prefs.calibrationLevelShift
        var pendingLinearSlope = prefs.calibrationLinearSlope
        var pendingLinearShift = prefs.calibrationLinearShift
        var lastValidLevelShift = pendingLevelShift

        /** Formats a double for compact UI display without trailing zeros. */
        fun formatDouble(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString().trimEnd('0').trimEnd('.')
            }
        }

        /** Creates the section background used to highlight the active algorithm. */
        fun sectionBackground(selected: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(0xFFF7F7F7.toInt())
                setStroke(
                    if (selected) (2 * density).toInt() else (1 * density).toInt(),
                    if (selected) 0xFF455A64.toInt() else 0xFFB0BEC5.toInt()
                )
            }
        }

        /** Creates one card-like section container. */
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

        /**
         * Resets all temporary calibration inputs.
         *
         * Requirement: when toggling calibration or switching algorithm,
         * Slop and Shift must reset to 0. To keep the UI and persisted state aligned,
         * Level Shift is also reset to 0.
         */
        fun resetTemporaryValues(
            levelShiftInput: EditText,
            slopeInput: EditText,
            linearShiftInput: EditText,
            levelRadio: RadioButton,
            linearRadio: RadioButton,
            selectLevel: Boolean
        ) {
            pendingLevelShift = 0.0
            pendingLinearSlope = 0.0
            pendingLinearShift = 0.0
            lastValidLevelShift = 0.0
            levelShiftInput.setText(formatDouble(pendingLevelShift))
            slopeInput.setText(formatDouble(pendingLinearSlope))
            linearShiftInput.setText(formatDouble(pendingLinearShift))
            levelRadio.isChecked = selectLevel
            linearRadio.isChecked = !selectLevel
        }

        /**
         * Validates the Level Shift value and keeps the most recent valid entry.
         *
         * Only Level Shift is currently functional, so this is the only editable value that must
         * be validated before saving.
         */
        fun validateLevelShift(levelShiftInput: EditText, showError: Boolean = true): Double? {
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

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ===== Top line: latest raw glucose from PRIMARY input =====
        val latestRawHeader = TextView(activity).apply {
            text = latestPrimaryReading?.rawValueMgdl?.let { "Raw blood glucose : $it mg/dL" } ?: "Raw blood glucose : -- mg/dL"
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, blockMargin)
        }
        rootLayout.addView(latestRawHeader)

        // Calibration is allowed only after a primary input source is configured.
        val multiSourceSettings = tw.yourcompany.cgmbridge.core.prefs.MultiSourceSettings(activity)
        val primaryInputSet = !multiSourceSettings.primaryInputSourceId.isNullOrBlank()

        val enableSwitch = SwitchCompat(activity).apply {
            text = activity.getString(R.string.calibration_enable_disable)
            isChecked = pendingEnabled
            textSize = 16f
            isEnabled = primaryInputSet
        }
        rootLayout.addView(enableSwitch)

        // Warning must stay directly below the switch and be shown in red.
        val inputSettingNotice = TextView(activity).apply {
            text = activity.getString(R.string.calibration_notice_input_setting)
            setPadding(0, 0, 0, blockMargin)
            textSize = 12f
            setTextColor(0xFFFF5252.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
        }
        rootLayout.addView(inputSettingNotice)

        val algorithmTitle = TextView(activity).apply {
            text = activity.getString(R.string.calibration_select_algorithm)
            textSize = 16f
            setPadding(0, blockMargin, 0, 0)
            setTypeface(typeface, Typeface.BOLD)
        }
        rootLayout.addView(algorithmTitle)

        val levelSection = createSectionLayout()
        val linearSection = createSectionLayout()

        // ===== Level Shift section (implemented) =====
        val levelRadio = RadioButton(activity).apply {
            text = activity.getString(R.string.calibration_algorithm_level_shift_formula)
            isChecked = pendingAlgorithm == CalibrationSettings.Algorithm.LEVEL_SHIFT
            textSize = 15f
        }
        levelSection.addView(levelRadio)

        val levelInputLabel = TextView(activity).apply {
            text = activity.getString(R.string.calibration_shift_label)
            setPadding(0, compactGap, 0, (4 * density).toInt())
        }
        levelSection.addView(levelInputLabel)

        val levelShiftInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = activity.getString(R.string.calibration_shift_hint)
            setText(formatDouble(pendingLevelShift))
            setSelection(text.length)
        }
        levelSection.addView(levelShiftInput)

        // ===== Linear Transform section (reserved, not implemented) =====
        val linearRadio = RadioButton(activity).apply {
            text = activity.getString(R.string.calibration_algorithm_linear_formula)
            isChecked = pendingAlgorithm == CalibrationSettings.Algorithm.LINEAR_TRANSFORM
            textSize = 15f
        }
        linearSection.addView(linearRadio)

        // Keep Slop / Shift / Graph visible in one row, but non-functional until implemented.
        val linearRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, compactGap, 0, 0)
        }

        val slopeColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = compactGap
            }
        }
        val slopeLabel = TextView(activity).apply {
            text = activity.getString(R.string.calibration_slope_label)
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val slopeInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(formatDouble(pendingLinearSlope))
            isEnabled = false
        }
        slopeColumn.addView(slopeLabel)
        slopeColumn.addView(slopeInput)

        val shiftColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = compactGap
            }
        }
        val linearShiftLabel = TextView(activity).apply {
            text = activity.getString(R.string.calibration_shift_label)
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val linearShiftInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(formatDouble(pendingLinearShift))
            isEnabled = false
        }
        shiftColumn.addView(linearShiftLabel)
        shiftColumn.addView(linearShiftInput)

        val graphButton = Button(activity).apply {
            text = "Graph"
            isEnabled = false
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * density).toInt()
            }
        }

        linearRow.addView(slopeColumn)
        linearRow.addView(shiftColumn)
        linearRow.addView(graphButton)
        linearSection.addView(linearRow)

        val linearWarning = TextView(activity).apply {
            text = "Not implemented yet"
            setPadding(0, compactGap, 0, 0)
            textSize = 13f
            setTextColor(0xFFFF5252.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }
        linearSection.addView(linearWarning)

        /**
         * Refreshes visible state so both algorithm sections stay visible,
         * but only one is selected and only one can be active.
         */
        fun refreshSectionStates() {
            val levelSelected = pendingAlgorithm == CalibrationSettings.Algorithm.LEVEL_SHIFT
            val linearSelected = pendingAlgorithm == CalibrationSettings.Algorithm.LINEAR_TRANSFORM

            levelRadio.isChecked = levelSelected
            linearRadio.isChecked = linearSelected

            levelSection.background = sectionBackground(levelSelected)
            linearSection.background = sectionBackground(linearSelected)

            val canEditLevel = pendingEnabled && primaryInputSet && levelSelected
            levelInputLabel.alpha = if (canEditLevel) 1f else 0.5f
            levelShiftInput.alpha = if (canEditLevel) 1f else 0.5f
            levelShiftInput.isEnabled = canEditLevel

            // Linear transform remains visible but never editable because not implemented yet.
            val linearAlpha = if (pendingEnabled && linearSelected) 1f else 0.8f
            slopeLabel.alpha = linearAlpha
            slopeInput.alpha = linearAlpha
            linearShiftLabel.alpha = linearAlpha
            linearShiftInput.alpha = linearAlpha
            graphButton.alpha = linearAlpha
            linearWarning.alpha = linearAlpha

            slopeInput.isEnabled = false
            linearShiftInput.isEnabled = false
            graphButton.isEnabled = false
        }

        levelShiftInput.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateLevelShift(levelShiftInput, showError = true)
            }
        }

        // Switch toggling resets values per requirement.
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            pendingEnabled = checked
            resetTemporaryValues(
                levelShiftInput = levelShiftInput,
                slopeInput = slopeInput,
                linearShiftInput = linearShiftInput,
                levelRadio = levelRadio,
                linearRadio = linearRadio,
                selectLevel = pendingAlgorithm == CalibrationSettings.Algorithm.LEVEL_SHIFT
            )
            refreshSectionStates()
        }

        // Algorithm switching also resets temporary values and keeps only one algorithm active.
        levelRadio.setOnClickListener {
            pendingAlgorithm = CalibrationSettings.Algorithm.LEVEL_SHIFT
            resetTemporaryValues(
                levelShiftInput = levelShiftInput,
                slopeInput = slopeInput,
                linearShiftInput = linearShiftInput,
                levelRadio = levelRadio,
                linearRadio = linearRadio,
                selectLevel = true
            )
            refreshSectionStates()
        }

        linearRadio.setOnClickListener {
            pendingAlgorithm = CalibrationSettings.Algorithm.LINEAR_TRANSFORM
            resetTemporaryValues(
                levelShiftInput = levelShiftInput,
                slopeInput = slopeInput,
                linearShiftInput = linearShiftInput,
                levelRadio = levelRadio,
                linearRadio = linearRadio,
                selectLevel = false
            )
            refreshSectionStates()
            Toast.makeText(activity, "Not implemented yet", Toast.LENGTH_SHORT).show()
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
                marginEnd = compactGap
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
            if (!pendingEnabled) {
                prefs.calibrationEnabled = false
                prefs.calibrationAlgorithm = CalibrationSettings.Algorithm.LEVEL_SHIFT.name
                prefs.calibrationLevelShift = 0.0
                prefs.calibrationLinearSlope = 0.0
                prefs.calibrationLinearShift = 0.0
                onChanged()
                dialog.dismiss()
                return@setOnClickListener
            }

            // Linear Transform must not become effective until implemented.
            if (pendingAlgorithm == CalibrationSettings.Algorithm.LINEAR_TRANSFORM) {
                Toast.makeText(activity, "Not implemented yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (validateLevelShift(levelShiftInput, showError = true) == null) {
                return@setOnClickListener
            }

            prefs.calibrationEnabled = true
            prefs.calibrationAlgorithm = CalibrationSettings.Algorithm.LEVEL_SHIFT.name
            prefs.calibrationLevelShift = pendingLevelShift
            prefs.calibrationLinearSlope = 0.0
            prefs.calibrationLinearShift = 0.0
            onChanged()
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}