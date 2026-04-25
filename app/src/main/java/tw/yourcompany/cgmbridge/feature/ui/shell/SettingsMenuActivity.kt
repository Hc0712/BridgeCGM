package tw.yourcompany.cgmbridge.feature.ui.shell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.yourcompany.cgmbridge.R
import tw.yourcompany.cgmbridge.core.constants.CoreConstants
import tw.yourcompany.cgmbridge.core.platform.BugReportExporter
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.prefs.MultiSourceSettings
import tw.yourcompany.cgmbridge.core.source.SourceIdentity
import tw.yourcompany.cgmbridge.core.source.TransportType
import tw.yourcompany.cgmbridge.databinding.ActivitySettingsMenuBinding
import tw.yourcompany.cgmbridge.feature.alarm.AlarmSettingsActivity
import tw.yourcompany.cgmbridge.feature.calibration.CalibrationDialogHelper

/**
 * Settings menu screen.
 *
 * This patched version adds the missing “Input Setting” flow required by the multi-source product
 * spec. The user can now choose one exact primary input source by selecting:
 * - vendor name,
 * - protocol / transport type.
 *
 * The resulting source is stored as a normalized `sourceId` and is then reused by the graph,
 * calibration, alarm, and stale-primary logic.
 */
class SettingsMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsMenuBinding
    private lateinit var multiSourceSettings: MultiSourceSettings
    /** Shared preferences used by the calibration tool dialog. */
    private lateinit var prefs: AppPrefs

    private val saveReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        exportBugReport(uri)
    }

    /**
     * Initializes the settings menu, wires all buttons, and restores the current primary-source
     * selection helper.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)
        multiSourceSettings = MultiSourceSettings(this)

        setupBottomNav()
        markSelectedTab(isSettings = true)

        binding.menuMainSettings.setOnClickListener {
            val intent = Intent(this, SetupActivity::class.java)
            intent.putExtra("forceShow", true)
            startActivity(intent)
        }
        binding.menuInputSource.setOnClickListener { showPrimaryInputDialog() }
        binding.menuOutputSource.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.menuAlarmSettings.setOnClickListener {
            startActivity(Intent(this, AlarmSettingsActivity::class.java))
        }
        binding.menuReportBug.setOnClickListener {
            saveReportLauncher.launch(BugReportExporter.defaultFileName())
        }
    }

    /**
     * Opens a two-step chooser for the primary input source.
     *
     * Step 1: choose vendor.
     * Step 2: choose protocol.
     *
     * The UI is intentionally simple and dialog-based so the feature can be added without creating
     * a new layout or activity.
     */
    private fun showPrimaryInputDialog() {
        val vendors: Array<String> = CoreConstants.CGM_VENDORS
        val protocols = arrayOf(
            "Notification", "Bluetooth", "WIFI", "NightScout", "Apple Health", "Health Connect"
        )
        val current = currentSelectionState(
            vendors,
            arrayOf("notification", "bluetooth", "wifi", "nightscout", "apple health", "health connect")
        )
        var vendorIndex = current.first
        var protocolIndex = current.second

        // Build custom view with two RadioGroups
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val vendorTitle = android.widget.TextView(this).apply {
            text = getString(R.string.select_cgm_vendor)
            textSize = 18f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(vendorTitle)

        val vendorGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        vendors.forEachIndexed { i: Int, v: String ->
            val btn = android.widget.RadioButton(this).apply {
                text = v.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                isChecked = i == vendorIndex
                id = 1000 + i // assign unique id
            }
            vendorGroup.addView(btn)
        }
        // Ensure only one vendor can be selected at a time
        vendorGroup.setOnCheckedChangeListener { _, checkedId ->
            vendorIndex = checkedId - 1000
        }
        layout.addView(vendorGroup)

        val protocolTitle = android.widget.TextView(this).apply {
            text = getString(R.string.select_protocol)
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }
        layout.addView(protocolTitle)

        val protocolGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        protocols.forEachIndexed { i: Int, p: String ->
            val btn = android.widget.RadioButton(this).apply {
                text = p
                isChecked = i == protocolIndex
                isEnabled = i == 0 // Only Notification is enabled
                id = 2000 + i // assign unique id
            }
            protocolGroup.addView(btn)
        }
        protocolGroup.setOnCheckedChangeListener { _, checkedId ->
            val idx = checkedId - 2000
            if (idx in protocols.indices && protocolGroup.getChildAt(idx).isEnabled) {
                protocolIndex = idx
            }
        }
        layout.addView(protocolGroup)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Input")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                savePrimaryInput(
                    vendors[vendorIndex],
                    protocols[protocolIndex].lowercase().replace(" ", "")
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
    }

    /**
     * Converts the currently stored `sourceId` into preselected vendor/protocol indexes for the
     * dialog UI.
     */
    private fun currentSelectionState(vendors: Array<String>, protocols: Array<String>): Pair<Int, Int> {
        val sourceId = multiSourceSettings.primaryInputSourceId ?: return 0 to 0
        val parts = sourceId.split(":")
        if (parts.size < 2) return 0 to 0
        val protocol = parts[0].lowercase()
        val vendor = parts[1].lowercase()
        val vendorIndex = vendors.indexOfFirst { it.equals(vendor, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        val protocolIndex = protocols.indexOfFirst { it.equals(protocol, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        return vendorIndex to protocolIndex
    }

    /**
     * Persists the selected primary source and shows a user-facing confirmation toast.
     *
     * Only notification transport is implemented today. Other protocols are still accepted as a
     * future-facing selection because the product spec wants the identity model to be ready now.
     */
    private fun savePrimaryInput(vendor: String, protocol: String) {
        val transport = TransportType.fromUserValue(protocol)
        val sourceId = SourceIdentity.buildSourceId(transport, vendor, null)
        multiSourceSettings.primaryInputSourceId = sourceId

        val message = if (transport == TransportType.NOTIFICATION) {
            "Primary input set to $sourceId"
        } else {
            "Primary input set to $sourceId. Only notification is implemented now; $protocol is reserved for future work."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Starts the app-scoped bug-report export and temporarily disables the report button while the
     * export is running.
     */
    private fun exportBugReport(uri: Uri) {
        binding.reportProgress.visibility = View.VISIBLE
        binding.menuReportBug.isEnabled = false
        lifecycleScope.launch {
            try {
                BugReportExporter.export(this@SettingsMenuActivity, uri)
                Toast.makeText(this@SettingsMenuActivity, getString(R.string.report_bug_saved), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    this@SettingsMenuActivity,
                    getString(R.string.report_bug_failed, t.message ?: t::class.java.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.reportProgress.visibility = View.GONE
                binding.menuReportBug.isEnabled = true
            }
        }
    }

    /**
     * Wires the bottom navigation for the settings screen.
     *
     * User-experience rule:
     * the five bottom-navigation buttons should behave like parallel entry points, so switching to
     * Settings must not silently downgrade the Tool button into a placeholder action. Reusing the
     * same calibration dialog helper as MainActivity keeps the Tool button consistent across screens
     * without adding a new navigation destination or background state.
     */
    private fun setupBottomNav() {
        binding.bottomNav.btnNavGraph.setOnClickListener { finish() }
        binding.bottomNav.btnNavStatistics.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavCarbInsulin.setOnClickListener {
            Toast.makeText(this, getString(R.string.future_work), Toast.LENGTH_SHORT).show()
        }
        binding.bottomNav.btnNavTools.setOnClickListener {
            // Settings screen does not keep a live latest-reading cache, so it opens the
            // menu without a primary reading header value.
            CalibrationDialogHelper.showCalibrationMenu(this, prefs, latestPrimaryReading = null)
        }
    }

    /** Highlights the selected bottom-nav tab. */
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
