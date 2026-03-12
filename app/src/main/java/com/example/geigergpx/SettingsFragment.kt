package com.example.geigergpx

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

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

    private var calibrationDetector: AudioBeepDetector? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs, rootKey)

        val maxSpeed = findPreference<EditTextPreference>("max_speed_kmh")
        maxSpeed?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        maxSpeed?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val spacing = findPreference<EditTextPreference>("point_spacing_m")
        spacing?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        spacing?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val minCounts = findPreference<EditTextPreference>("min_counts_per_point")
        minCounts?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        minCounts?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val maxTime = findPreference<EditTextPreference>("max_time_without_counts_s")
        maxTime?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        maxTime?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val coeff = findPreference<EditTextPreference>("cps_to_usvh")
        coeff?.setOnBindEditTextListener { edit ->
            edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        coeff?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        val tagType = findPreference<ListPreference>("dose_tag_type")
        tagType?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        val chooseFolder = findPreference<Preference>("gpx_folder_picker")
        chooseFolder?.setOnPreferenceClickListener {
            folderPicker.launch(null)
            true
        }

        updateFolderSummary()

        val thresholdPref = findPreference<Preference>("threshold_calibration")
        thresholdPref?.summary = buildThresholdSummary()
        thresholdPref?.setOnPreferenceClickListener {
            startCalibrationDialog(thresholdPref)
            true
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

    private fun buildThresholdSummary(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val value = prefs.getFloat(KEY_AUDIO_THRESHOLD, Float.NaN)
        return if (value.isNaN()) {
            "Not calibrated"
        } else {
            "Current threshold: %.2e".format(value)
        }
    }

    private fun startCalibrationDialog(thresholdPref: Preference) {
        val total = 10
        val activity = requireActivity()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Calibration")
            .setMessage("Calibrating... 0/$total")
            .setNegativeButton("Cancel") { d, _ ->
                calibrationDetector?.stop()
                calibrationDetector = null
                d.dismiss()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        calibrationDetector = AudioBeepDetector.startCalibration(
            requireContext(),
            onProgress = { current, totalCount ->
                activity.runOnUiThread {
                    dialog.setMessage("Calibrating... $current/$totalCount")
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
    }

    companion object {
        const val KEY_GPX_TREE_URI = "gpx_tree_uri"
        const val KEY_AUDIO_THRESHOLD = "audio_threshold"
    }
}

