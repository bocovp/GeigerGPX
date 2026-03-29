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
        coeff?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val avgTimestamps = findPreference<ListPreference>("dose_rate_avg_timestamps_n")
        avgTimestamps?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

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
        thresholdPref?.summary = buildThresholdSummary()
        thresholdPref?.setOnPreferenceClickListener {
            startCalibrationDialog(thresholdPref)
            true
        }
        thresholdPref?.onLongClick = {
            showManualThresholdDialog(thresholdPref)
        }
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

    private fun toDb(intensity: Float): Double {
        return 10.0 * log10(intensity.toDouble() / 100.0)
    }

    private fun fromDb(value: Float): Double {
        return 10.0.pow(value / 10.0) * 100.0
    }

    private fun buildThresholdSummary(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val value = prefs.getFloat(KEY_AUDIO_THRESHOLD, Float.NaN)
        return if (value.isNaN()) {
            "Not calibrated"
        } else {
            "Current threshold: %.2f dB".format(toDb(value))
            //100.0 is just an arbitrary constant
        }
    }

    private fun startCalibrationDialog(thresholdPref: Preference) {
        val activity = requireActivity()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Calibration")
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
                    thresholdPref.summary = buildThresholdSummary()
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

    private fun showManualThresholdDialog(thresholdPref: Preference) {
        val context = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getFloat(KEY_AUDIO_THRESHOLD, Float.NaN)

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "e.g. 42.1"
            if (!current.isNaN()) {
                setText("%.2f".format(toDb(current)))
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
                    prefs.edit().putFloat(KEY_AUDIO_THRESHOLD, value2.toFloat()).apply()
                    thresholdPref.summary = buildThresholdSummary()
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
    }
}
