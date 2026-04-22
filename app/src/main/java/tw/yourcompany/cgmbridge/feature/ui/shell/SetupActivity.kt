package tw.yourcompany.cgmbridge.feature.ui.shell

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.platform.BatteryOptimizationHelper
import tw.yourcompany.cgmbridge.core.platform.NotificationAccessChecker
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.databinding.ActivitySetupBinding
import tw.yourcompany.cgmbridge.feature.alarm.AlarmNotificationPermissionHelper
import tw.yourcompany.cgmbridge.feature.keepalive.GuardianServiceLauncher
import tw.yourcompany.cgmbridge.feature.keepalive.NotificationPollScheduler

/**
 * First-launch setup and warning screen.
 *
 * This patch keeps all permission entry points in one place so the user can complete setup from a
 * single menu:
 * - Notification listener access
 * - Battery optimization exclusion
 * - Alarm notification enablement
 *
 * The new “Enable notification” button is added programmatically so this patch can be applied
 * without requiring a companion XML layout change.
 */
class SetupActivity : AppCompatActivity() {

    /** ViewBinding for this screen. */
    private lateinit var binding: ActivitySetupBinding

    /** SharedPreferences wrapper for stored choices. */
    private lateinit var prefs: AppPrefs

    /**
     * Initializes the setup screen or skips it when already accepted.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)

        val forceShow = intent.getBooleanExtra("forceShow", false)
        if (prefs.setupAccepted && !forceShow) {
            startMainAndFinish()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.radioMgdl.isChecked = prefs.outputUnit == "mgdl"
        binding.radioMmol.isChecked = prefs.outputUnit == "mmol"
        binding.radioHost.isChecked = prefs.role == "host"
        binding.radioReceiver.isChecked = prefs.role == "receiver"

        binding.btnNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnBatteryOptimization.setOnClickListener {
            startActivity(BatteryOptimizationHelper.buildIgnoreOptimizationsIntent(this))
        }

        addEnableNotificationButtonIfNeeded()

        binding.btnOk.setOnClickListener {
            // Keep the original battery-optimization warning because the watchdog and listener
            // stability depend on it for long-running use.
            if (!BatteryOptimizationHelper.isIgnoringOptimizations(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization Required")
                    .setMessage(
                        "This app must be excluded from battery optimization to reliably receive CGM data. " +
                            "Without this, Android may stop delivering notifications after a few minutes." +
                            "Please tap 'Allow' on the next screen."
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(BatteryOptimizationHelper.buildIgnoreOptimizationsIntent(this))
                    }
                    .setNegativeButton("Skip (Not Recommended)") { _, _ ->
                        completeSetup()
                    }
                    .setCancelable(false)
                    .show()
                return@setOnClickListener
            }

            completeSetup()
        }
    }

    /**
     * Inserts the new “Enable notification” button beside the existing permission buttons.
     *
     * Why this is done in code instead of XML:
     * - the uploaded task asked for a source-only patch zip;
     * - the existing setup layout content was not part of the editable context here;
     * - cloning the existing button style from code keeps the UI close to the current screen.
     *
     * The method is defensive:
     * - if the parent container cannot be resolved as a [ViewGroup], the app simply skips the
     *   extra button instead of crashing;
     * - the new button copies layout params from the battery button so spacing remains consistent.
     */
    private fun addEnableNotificationButtonIfNeeded() {
        val parent = binding.btnBatteryOptimization.parent as? ViewGroup ?: return

        val enableNotificationButton = Button(this).apply {
            text = getString(R.string.open_alarm_notification_enable)
            isAllCaps = binding.btnBatteryOptimization.isAllCaps
            // Copy the original button text size directly in PX to avoid the deprecated
            // DisplayMetrics.scaledDensity field while preserving the same rendered size.
            setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.btnBatteryOptimization.textSize)
            setOnClickListener {
                AlarmNotificationPermissionHelper.showEnableNotificationDialog(this@SetupActivity)
            }
        }

        binding.btnBatteryOptimization.background?.constantState?.newDrawable()?.mutate()?.let {
            enableNotificationButton.background = it
        }
        enableNotificationButton.setTextColor(binding.btnBatteryOptimization.currentTextColor)

        enableNotificationButton.layoutParams = cloneLayoutParams(binding.btnBatteryOptimization.layoutParams)

        val insertIndex = parent.indexOfChild(binding.btnBatteryOptimization) + 1
        if (insertIndex in 1..parent.childCount) {
            parent.addView(enableNotificationButton, insertIndex)
        } else {
            parent.addView(enableNotificationButton)
        }
    }

    /**
     * Creates a safe copy of the original button layout params.
     *
     * Android view parents require the correct concrete LayoutParams subclass. Reusing the original
     * instance directly can fail because one view cannot own the same LayoutParams object as
     * another view. This helper preserves margins and width/height semantics while avoiding that
     * reuse bug.
     */
    private fun cloneLayoutParams(source: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        return when (source) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(source)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(source)
            else -> ViewGroup.LayoutParams(source)
        }
    }

    /** Refresh button-related diagnostics after the user returns from system settings screens. */
    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            val isIgnoring = BatteryOptimizationHelper.isIgnoringOptimizations(this)
            val hasAccess = NotificationAccessChecker.isNotificationAccessGranted(this)
            DebugTrace.t(
                DebugCategory.SETTING,
                "SETUP-RESUME",
                "battery_opt_ignored=$isIgnoring notif_access=$hasAccess"
            )
        }
    }

    /**
     * Saves preferences, starts keep-alive services, and opens the main screen.
     *
     * This is a keep-alive startup entry point — it arms the AlarmManager heartbeat immediately
     * after first-time setup so the watchdog is active before the first CGM notification arrives.
     */
    private fun completeSetup() {
        prefs.outputUnit = if (binding.radioMmol.isChecked) "mmol" else "mgdl"
        prefs.role = if (binding.radioReceiver.isChecked) "receiver" else "host"
        prefs.setupAccepted = true

        DebugTrace.t(
            DebugCategory.SETTING,
            "SETUP-OK",
            "notif_access=${NotificationAccessChecker.isNotificationAccessGranted(this)} " +
                "ignore_batt=${BatteryOptimizationHelper.isIgnoringOptimizations(this)} " +
                "unit=${prefs.outputUnit}"
        )

        GuardianServiceLauncher.start(this, "setup-ok")
        NotificationPollScheduler.schedule(this)
        startMainAndFinish()
    }

    /** Opens the main screen and finishes the setup screen. */
    private fun startMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
