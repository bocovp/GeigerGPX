package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

class SettingsFragment : PreferenceFragmentCompat() {
    private val settingsPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_CPS_TO_USVH || key == AppSettings.KEY_DOSE_RATE_AVG_TIMESTAMPS_N || key == AppSettings.KEY_ALERT_DOSE_RATE) {
            updateAlertDoseRateSummary()
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)

            AppSettings.from(requireContext()).setGpxTreeUri(uri)

            updateFolderSummary()
        }
    }

    private var calibrationDetector: CalibrationSession? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs, rootKey)

        val maxSpeed = findPreference<EditTextPreference>("max_speed_kmh")
        maxSpeed?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        maxSpeed?.summaryProvider = summaryWithUnit("km/h")

        val spacing = findPreference<EditTextPreference>("point_spacing_m")
        spacing?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        spacing?.summaryProvider = summaryWithUnit("m")

        val minCounts = findPreference<EditTextPreference>("min_counts_per_point")
        minCounts?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        minCounts?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val maxTime = findPreference<EditTextPreference>("max_time_without_counts_s")
        maxTime?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        maxTime?.summaryProvider = summaryWithUnit("s")

        val maxTimeWithoutGps = findPreference<EditTextPreference>("max_time_without_gps_s")
        maxTimeWithoutGps?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        maxTimeWithoutGps?.summaryProvider = summaryWithUnit("s")

        val coeff = findPreference<EditTextPreference>("cps_to_usvh")
        coeff?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        coeff?.summaryProvider = summaryWithUnit("μSv/h per cps")

        val avgTimestamps = findPreference<ListPreference>("dose_rate_avg_timestamps_n")
        avgTimestamps?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        val alertDoseRate = findPreference<EditTextPreference>("alert_dose_rate")
        alertDoseRate?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        updateAlertDoseRateSummary()

        val saveInEle = findPreference<SwitchPreferenceCompat>("save_dose_rate_in_ele")
        saveInEle?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
            if (pref.isChecked) "Enabled" else "Disabled"
        }

        val chooseFolder = findPreference<Preference>("gpx_folder_picker")
        chooseFolder?.setOnPreferenceClickListener {
            folderPicker.launch(null)
            true
        }

        updateFolderSummary()

        val thresholdPref = findPreference<LongPressPreference>("threshold_calibration")
        val bluetoothThresholdPref = findPreference<LongPressPreference>("bluetooth_threshold_calibration")
        val useBluetoothMic = findPreference<SwitchPreferenceCompat>(AppSettings.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE)

        thresholdPref?.let { setupThresholdPreference(it, false) }
        bluetoothThresholdPref?.let { setupThresholdPreference(it, true) }

        useBluetoothMic?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
            if (pref.isChecked) "Enabled" else "Disabled"
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(settingsPrefListener)
        updateAlertDoseRateSummary()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(settingsPrefListener)
        super.onPause()
    }

    private fun summaryWithUnit(unit: String): Preference.SummaryProvider<EditTextPreference> {
        return Preference.SummaryProvider { pref ->
            val value = pref.text.orEmpty()
            if (value.isBlank()) "Not set" else "$value $unit"
        }
    }

    private fun updateFolderSummary() {
        val chooseFolder = findPreference<Preference>("gpx_folder_picker") ?: return
        val uriStr = AppSettings.from(requireContext()).getGpxTreeUriString()
        if (uriStr.isNullOrBlank()) {
            chooseFolder.summary = "Not set (uses app folder)"
            return
        }
        launchUi {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val uri = android.net.Uri.parse(uriStr)
                    val context = context ?: return@withContext uriStr
                    val doc = DocumentFile.fromTreeUri(context, uri)
                    doc?.name ?: uriStr
                }
                chooseFolder.summary = summary
            } catch (_: Exception) {
                chooseFolder.summary = uriStr
            }
        }
    }

    private fun updateAlertDoseRateSummary() {
        findPreference<EditTextPreference>("alert_dose_rate")?.summary = buildAlertDoseRateSummary()
    }

    private fun buildAlertDoseRateSummary(): String {
        val appSettings = AppSettings.from(requireContext())
        val alertValue = appSettings.getAlertDoseRate()
        val normalizedAlert = if (alertValue == 0.0) 0.0 else alertValue
        if (normalizedAlert <= 0.0) {
            return "Not set"
        }

        val avgTimestamps = appSettings.getDoseRateAvgWindowSize()
        val coeff = appSettings.getCpsToUsvhCoefficient()
        val falseAlarmRate = ConfidenceInterval.getFalseAlarmRate(normalizedAlert, avgTimestamps, coeff)
        val unit = if (coeff == 1.0) "cps" else "μSv/h"
        return String.format(
            java.util.Locale.US,
            "%.2f %s      False alarms: %.1f / hour",
            normalizedAlert,
            unit,
            falseAlarmRate
        )
    }

    private fun toDb(intensity: Float): Double {
        return 10.0 * log10(intensity.toDouble() / 100.0)
    }

    private fun fromDb(value: Float): Double {
        return 10.0.pow(value / 10.0) * 100.0
    }

    private fun thresholdKey(bluetooth: Boolean): String {
        return if (bluetooth) AppSettings.KEY_BLUETOOTH_AUDIO_THRESHOLD else AppSettings.KEY_AUDIO_THRESHOLD
    }

    private fun defaultThreshold(bluetooth: Boolean): Float {
        return if (bluetooth) {
            AudioInputManager.DEFAULT_BLUETOOTH_MAG_THRESHOLD
        } else {
            AudioInputManager.DEFAULT_MAG_THRESHOLD
        }
    }

    private fun buildThresholdSummary(bluetooth: Boolean): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val value = prefs.getFloat(thresholdKey(bluetooth), Float.NaN)
        return if (value.isNaN()) {
            "Uncalibrated (%.2f dB)".format(java.util.Locale.US, toDb(defaultThreshold(bluetooth)))
        } else {
            "Current threshold: %.2f dB".format(java.util.Locale.US, toDb(value))
            //100.0 is just an arbitrary constant
        }
    }

    private fun setupThresholdPreference(pref: LongPressPreference, bluetooth: Boolean) {
        pref.summary = buildThresholdSummary(bluetooth)
        pref.setOnPreferenceClickListener {
            if (bluetooth && !AudioInputManager.isBluetoothMicAvailable(requireContext())) {
                Toast.makeText(requireContext(), "Bluetooth microphone not available.", Toast.LENGTH_SHORT).show()
                true
            } else {
                startCalibrationDialog(pref, bluetooth)
                true
            }
        }
        pref.onLongClick = {
            showManualThresholdDialog(pref, bluetooth)
        }
    }

    private fun startCalibrationDialog(thresholdPref: LongPressPreference, bluetooth: Boolean) {
        val dialog = AlertDialog.Builder(requireActivity())
            .setTitle(if (bluetooth) "Calibration (bluetooth)" else "Calibration")
            .setMessage("Estimating signal level...")
            .setNegativeButton("Cancel") { d, _ ->
                calibrationDetector?.stop()
                calibrationDetector = null
                d.dismiss()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        calibrationDetector = CalibrationSession(
            context = requireContext(),
            useBluetoothMicIfAvailable = bluetooth,
            thresholdPreferenceKey = thresholdKey(bluetooth),
            fallbackThreshold = defaultThreshold(bluetooth),
            onAudioStatus = { status, errorCode ->
                launchUi {
                    if (errorCode != AudioInputManager.AUDIO_STATUS_WORKING) {
                        dialog.setMessage(status)
                    }
                }
            },
            onProgress = { phase, current, totalCount ->
                launchUi {
                    if (phase == 2) {
                        dialog.setMessage("Calibrating... $current/$totalCount")
                    }
                }
            },
            onFinished = { _ ->
                launchUi {
                    calibrationDetector = null
                    dialog.dismiss()
                    thresholdPref.summary = buildThresholdSummary(bluetooth)
                    Toast.makeText(
                        requireContext(),
                        "Calibration finished.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        calibrationDetector?.start()
    }

    private fun launchUi(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun showManualThresholdDialog(thresholdPref: LongPressPreference, bluetooth: Boolean) {
        val context = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val thresholdKey = thresholdKey(bluetooth)
        val current = prefs.getFloat(thresholdKey, Float.NaN)

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "e.g. 42.1"
            if (!current.isNaN()) {
                setText("%.2f".format(java.util.Locale.US, toDb(current)))
                setSelection(text.length)
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Set threshold manually")
            .setMessage("Enter a positive threshold value.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().toFloatOrNull()
                if (value != null && value > 0f && value.isFinite()) {
                    // Going back from "our" deciBells to intensity
                    val value2 = fromDb(value);
                    prefs.edit().putFloat(thresholdKey, value2.toFloat()).apply()
                    thresholdPref.summary = buildThresholdSummary(bluetooth)
                    Toast.makeText(context, "Threshold updated.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid threshold value.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        calibrationDetector?.stop()
        calibrationDetector = null
        super.onDestroyView()
    }

}
