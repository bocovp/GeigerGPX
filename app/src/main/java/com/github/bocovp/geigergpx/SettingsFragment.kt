package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import kotlin.math.log10
import kotlin.math.pow
import kotlin.text.format

class SettingsFragment : PreferenceFragmentCompat() {
    private val settingsPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "cps_to_usvh" || key == "dose_rate_avg_timestamps_n" || key == "alert_dose_rate") {
            updateAlertDoseRateSummary()
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)

            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putString(KEY_GPX_TREE_URI, uri.toString())
                .apply()

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
        val useBluetoothMic = findPreference<SwitchPreferenceCompat>(KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE)

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
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val uriStr = prefs.getString(KEY_GPX_TREE_URI, null)
        if (uriStr.isNullOrBlank()) {
            chooseFolder.summary = "Not set (uses app folder)"
            return
        }
        val uri = android.net.Uri.parse(uriStr)
        val doc = DocumentFile.fromTreeUri(requireContext(), uri)
        chooseFolder.summary = doc?.name ?: uriStr
    }

    private fun updateAlertDoseRateSummary() {
        findPreference<EditTextPreference>("alert_dose_rate")?.summary = buildAlertDoseRateSummary()
    }

    private fun buildAlertDoseRateSummary(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val alertValue = prefs.getString("alert_dose_rate", "0")?.toDoubleOrNull() ?: 0.0
        val normalizedAlert = if (alertValue == 0.0) 0.0 else alertValue
        if (normalizedAlert <= 0.0) {
            return "Not set"
        }

        val avgTimestamps = prefs.getString("dose_rate_avg_timestamps_n", "10")?.toIntOrNull() ?: 10
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
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
        return if (bluetooth) KEY_BLUETOOTH_AUDIO_THRESHOLD else KEY_AUDIO_THRESHOLD
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
        val activity = requireActivity()

        val dialog = AlertDialog.Builder(activity)
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
                activity.runOnUiThread {
                    if (errorCode != AudioInputManager.AUDIO_STATUS_WORKING) {
                        dialog.setMessage(status)
                    }
                }
            },
            onProgress = { phase, current, totalCount ->
                activity.runOnUiThread {
                    if (phase == 2) {
                        dialog.setMessage("Calibrating... $current/$totalCount")
                    }
                }
            },
            onFinished = { _ ->
                activity.runOnUiThread {
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

    companion object {
        const val KEY_GPX_TREE_URI = "gpx_tree_uri"
        const val KEY_AUDIO_THRESHOLD = "audio_threshold"
        const val KEY_BLUETOOTH_AUDIO_THRESHOLD = "bluetooth_audio_threshold"
        const val KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE = "use_bluetooth_mic_if_available"
    }
}
