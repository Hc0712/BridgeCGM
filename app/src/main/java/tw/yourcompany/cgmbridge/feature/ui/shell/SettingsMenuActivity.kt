package tw.yourcompany.cgmbridge.feature.ui.shell

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.databinding.ActivitySettingsMenuBinding
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.platform.BugReportExporter
import tw.yourcompany.cgmbridge.feature.alarm.AlarmSettingsActivity
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationDialogHelper

/**
 * Settings menu screen (first revision).
 *
 * Contains placeholder entries for future work:
 * - Main Settings
 * - Input Source
 * - Output Source
 *
 * Implements "Report Bug" for non-developer users:
 * - Uses Storage Access Framework (system file picker) to let user choose a save location.
 * - Creates an "App Diagnostics" ZIP (app logs + app-level stack traces + app-level dumps).
 *
 * Note: A normal app cannot capture a full system bugreport/logcat/dumpsys on most devices.
 * This screen therefore produces a useful app-scoped report, and optionally guides the user
 * to the system Developer Options where the OS can create a full bugreport zip.
 *
 * Adds Alarm Setting entry and keeps Report Bug export support.
 */
class SettingsMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsMenuBinding

    private val saveReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        exportBugReport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.menuMainSettings.setOnClickListener { openAlarmPermissionTools() }

        setupBottomNav()
        markSelectedTab(isSettings = true)

        binding.menuMainSettings.setOnClickListener {
            // Launch SetupActivity so user can re-do setup, force showing it
            val intent = Intent(this, SetupActivity::class.java)
            intent.putExtra("forceShow", true)
            startActivity(intent)
        }
        binding.menuInputSource.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.menuOutputSource.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.menuAlarmSettings.setOnClickListener {
            startActivity(Intent(this, AlarmSettingsActivity::class.java))
        }
        binding.menuReportBug.setOnClickListener {
            val defaultName = BugReportExporter.defaultFileName()
            saveReportLauncher.launch(defaultName)
        }
    }


    private fun openAlarmPermissionTools() {
        val nm = getSystemService(NotificationManager::class.java)
        val dndGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            nm?.isNotificationPolicyAccessGranted == true
        } else true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_alarm_tools_title))
            .setMessage(getString(R.string.settings_alarm_tools_message, if (dndGranted) getString(R.string.settings_state_granted) else getString(R.string.settings_state_not_granted)))
            .setPositiveButton(R.string.settings_open_sound_settings) { _, _ -> startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            .setNeutralButton(R.string.settings_open_dnd_access) { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            .setNegativeButton(R.string.settings_open_app_notification_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, packageName) })
            }
            .show()
    }

    private fun exportBugReport(uri: Uri) {
        binding.reportProgress.visibility = View.VISIBLE
        binding.menuReportBug.isEnabled = false

        lifecycleScope.launch {
            try {
                BugReportExporter.export(this@SettingsMenuActivity, uri)
                Toast.makeText(this@SettingsMenuActivity, getString(R.string.report_bug_saved), Toast.LENGTH_LONG).show()

                // Optional: guide user to the system bug report UI (requires Developer Options).
                // Many OEMs hide this unless Developer Options is enabled.
                // If the intent fails, we silently ignore.
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    // ignore
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@SettingsMenuActivity,
                    getString(R.string.report_bug_failed, e.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.reportProgress.visibility = View.GONE
                binding.menuReportBug.isEnabled = true
            }
        }
    }

    private fun setupBottomNav() {
        // Graph: return to main graph screen.
        binding.bottomNav.btnNavGraph.setOnClickListener {
            finish()
        }

        binding.bottomNav.btnNavStatistics.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavCarbInsulin.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavTools.setOnClickListener {
            CalibrationDialogHelper.showCalibrationMenu(this, AppPrefs(this))
        }
        binding.bottomNav.btnNavSettings.setOnClickListener {
            // Already here
        }
    }

    private fun markSelectedTab(isSettings: Boolean) {
        val sel = 0xFF4CAF50.toInt()
        val nor = 0xFF888888.toInt()
        binding.bottomNav.btnNavGraph.setTextColor(if (isSettings) nor else sel)
        binding.bottomNav.btnNavSettings.setTextColor(if (isSettings) sel else nor)
        binding.bottomNav.btnNavStatistics.setTextColor(nor)
        binding.bottomNav.btnNavCarbInsulin.setTextColor(nor)
        binding.bottomNav.btnNavTools.setTextColor(nor)
    }
}
