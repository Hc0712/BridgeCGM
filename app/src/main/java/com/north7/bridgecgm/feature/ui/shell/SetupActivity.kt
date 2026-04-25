package com.north7.bridgecgm.feature.ui.shell

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace
import com.north7.bridgecgm.core.platform.BatteryOptimizationHelper
import com.north7.bridgecgm.core.platform.NotificationAccessChecker
import com.north7.bridgecgm.core.prefs.AppPrefs
import com.north7.bridgecgm.databinding.ActivitySetupBinding
import com.north7.bridgecgm.feature.alarm.AlarmNotificationPermissionHelper
import com.north7.bridgecgm.feature.keepalive.GuardianServiceLauncher
import com.north7.bridgecgm.feature.keepalive.NotificationPollScheduler

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

        binding.btnEnableNotification.setOnClickListener {
            AlarmNotificationPermissionHelper.showEnableNotificationDialog(this)
        }

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
