package tw.yourcompany.cgmbridge.feature.ui.shell

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import tw.yourcompany.cgmbridge.databinding.ActivitySetupBinding
import tw.yourcompany.cgmbridge.feature.keepalive.NotificationPollScheduler
import tw.yourcompany.cgmbridge.feature.keepalive.GuardianServiceLauncher
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.platform.BatteryOptimizationHelper
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.platform.NotificationAccessChecker

/**
 * First-launch setup and warning screen.
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

        binding.btnOk.setOnClickListener {
            // ── Battery optimization enforcement (modeled after xDrip+ Home.checkBatteryOptimization()) ──
            if (!BatteryOptimizationHelper.isIgnoringOptimizations(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization Required")
                    .setMessage(
                        "This app must be excluded from battery optimization to reliably receive CGM data. " +
                        "Without this, Android may stop delivering notifications after a few minutes.\n\n" +
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
     * Refresh button states when returning from system settings screens.
     */
    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            val isIgnoring = BatteryOptimizationHelper.isIgnoringOptimizations(this)
            val hasAccess = NotificationAccessChecker.isNotificationAccessGranted(this)
            DebugTrace.t(DebugCategory.SETTING, "SETUP-RESUME", "battery_opt_ignored=$isIgnoring notif_access=$hasAccess")
        }
    }

    /**
     * Saves preferences, starts keep-alive services, and opens the main screen.
     *
     * This is a keep-alive startup entry point — it arms the AlarmManager heartbeat
     * (Layer 3) immediately after first-time setup. This ensures the watchdog is
     * active even before the user has received their first CGM notification.
     *
     * ### Execution order:
     * 1. Save unit/role preferences
     * 2. Mark setup as accepted (skip this screen on future launches)
     * 3. Start guardian foreground service (Layer 8)
     * 4. Arm heartbeat alarm (Layer 3)
     * 5. Navigate to MainActivity
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
            "unit=${prefs.outputUnit} role=${prefs.role}"
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
