package com.github.bocovp.geigergpx

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class DeviceSettingsFragment : PreferenceFragmentCompat() {
    private val preferenceKeys = listOf(
        RadiationCalibration.KEY_SENSITIVITY,
        DeviceConfigManager.KEY_FREQ_LOW,
        DeviceConfigManager.KEY_FREQ_MAIN,
        DeviceConfigManager.KEY_FREQ_HIGH,
        DeviceConfigManager.KEY_DURATION,
        DeviceConfigManager.KEY_DOMINANCE_THRESHOLD,
        DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END,
        DeviceConfigManager.KEY_WINDOW_SIZE,
        DeviceConfigManager.KEY_STEP_SIZE,
        DeviceConfigManager.KEY_ONE_BEEP_TOL,
        DeviceConfigManager.KEY_TWO_BEEP_TOL,
        DeviceConfigManager.KEY_THREE_BEEP_TOL,
        DeviceConfigManager.KEY_FOUR_BEEP_TOL
    )

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DeviceConfigManager.KEY_DEVICE_NAME || (key != null && key in preferenceKeys)) refreshSummaries()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        DeviceConfigManager.init(requireContext())

        val category = PreferenceCategory(requireContext()).apply {
            title = "Device"
            isIconSpaceReserved = false
        }
        preferenceScreen.addPreference(category)

        category.addPreference(Preference(requireContext()).apply {
            key = DeviceConfigManager.KEY_DEVICE_NAME
            title = "Device"
            setOnPreferenceClickListener {
                showDeviceChooser()
                true
            }
        })

        addEdit(category, RadiationCalibration.KEY_SENSITIVITY, "Sensitivity", "cps per μSv/h", decimal = true, signed = true)
        category.addPreference(Preference(requireContext()).apply {
            key = "beep_detector"
            title = "Beep detector"
            isEnabled = false
        })
        addEdit(category, DeviceConfigManager.KEY_FREQ_LOW, "Low frequency", "Hz", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_FREQ_MAIN, "Main frequency", "Hz", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_FREQ_HIGH, "High frequency", "Hz", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_DURATION, "Beep duration", "s", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_DOMINANCE_THRESHOLD, "Dominance threshold", null, decimal = true)
        addEdit(category, DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END, "Dominance threshold fade-out", null, decimal = true)
        addEdit(category, DeviceConfigManager.KEY_WINDOW_SIZE, "Window size", null, decimal = false)
        addEdit(category, DeviceConfigManager.KEY_STEP_SIZE, "Step size", null, decimal = false)
        addEdit(category, DeviceConfigManager.KEY_ONE_BEEP_TOL, "Single beep duration tolerance", "s", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_TWO_BEEP_TOL, "Double beep duration tolerance", "s", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_THREE_BEEP_TOL, "Triple beep duration tolerance", "s", decimal = true)
        addEdit(category, DeviceConfigManager.KEY_FOUR_BEEP_TOL, "Quadruple beep duration tolerance", "s", decimal = true)

        refreshSummaries()
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.supportActionBar?.title = "Device"
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        refreshSummaries()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    private fun addEdit(
        category: PreferenceCategory,
        key: String,
        titleText: String,
        unit: String?,
        decimal: Boolean,
        signed: Boolean = false
    ) {
        category.addPreference(EditTextPreference(requireContext()).apply {
            key = key
            title = titleText
            dialogTitle = if (unit == null) titleText else "$titleText ($unit)"
            setOnBindEditTextListener { edit ->
                var input = InputType.TYPE_CLASS_NUMBER
                if (decimal) input = input or InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (signed) input = input or InputType.TYPE_NUMBER_FLAG_SIGNED
                edit.inputType = input
            }
        })
    }

    private fun showDeviceChooser() {
        val app = requireActivity().application as GeigerGpxApp
        if (app.trackingRepository.isTracking.value || app.trackingRepository.measurementModeEnabled.value) {
            Toast.makeText(requireContext(), "Cannot change device while recording or measurement mode is active.", Toast.LENGTH_LONG).show()
            return
        }

        val devices = DeviceConfigManager.devices(requireContext())
        val names = devices.map { it.name } + DeviceConfigManager.CUSTOM_DEVICE_NAME
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val current = names.indexOf(DeviceConfigManager.currentDeviceName(prefs)).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("Choose device")
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                DeviceConfigManager.selectDevice(requireContext(), names[which])
                refreshSummaries()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshSummaries() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val custom = DeviceConfigManager.isCustom(prefs)
        val config = DeviceConfigManager.displayConfig(requireContext())
        val device = DeviceConfigManager.currentDevice(prefs)

        findPreference<Preference>(DeviceConfigManager.KEY_DEVICE_NAME)?.summary = DeviceConfigManager.currentDeviceName(prefs)
        findPreference<Preference>("beep_detector")?.summary = DeviceConfigManager.detectorName(requireContext())

        setEditValue(RadiationCalibration.KEY_SENSITIVITY, DeviceConfigManager.formatNumber(device?.sensitivity ?: DeviceConfigManager.sensitivityFromPrefs(prefs)), custom)
        setEditValue(DeviceConfigManager.KEY_FREQ_LOW, DeviceConfigManager.formatNumber(config.freqLow.toDouble()), custom)
        setEditValue(DeviceConfigManager.KEY_FREQ_MAIN, DeviceConfigManager.formatNumber(config.freqMain.toDouble()), custom)
        setEditValue(DeviceConfigManager.KEY_FREQ_HIGH, DeviceConfigManager.formatNumber(config.freqHigh.toDouble()), custom)
        setEditValue(DeviceConfigManager.KEY_DURATION, DeviceConfigManager.formatNumber(config.duration), custom)
        setEditValue(DeviceConfigManager.KEY_DOMINANCE_THRESHOLD, DeviceConfigManager.formatNumber(config.dominanceThreshold.toDouble()), custom)
        setEditValue(DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END, DeviceConfigManager.formatNumber(config.dominanceThresholdEnd.toDouble()), custom)
        setEditValue(DeviceConfigManager.KEY_WINDOW_SIZE, DeviceConfigManager.formatInt(config.windowSize), custom)
        setEditValue(DeviceConfigManager.KEY_STEP_SIZE, DeviceConfigManager.formatInt(config.stepSize), custom)
        setEditValue(DeviceConfigManager.KEY_ONE_BEEP_TOL, DeviceConfigManager.formatNumber(config.oneBeepTol), custom)
        setEditValue(DeviceConfigManager.KEY_TWO_BEEP_TOL, DeviceConfigManager.formatNumber(config.twoBeepTol), custom)
        setEditValue(DeviceConfigManager.KEY_THREE_BEEP_TOL, DeviceConfigManager.formatNumber(config.threeBeepTol), custom)
        setEditValue(DeviceConfigManager.KEY_FOUR_BEEP_TOL, DeviceConfigManager.formatNumber(config.fourBeepTol), custom)
    }

    private fun setEditValue(key: String, value: String, enabled: Boolean) {
        val pref = findPreference<EditTextPreference>(key) ?: return
        pref.isEnabled = enabled
        val unit = when (key) {
            RadiationCalibration.KEY_SENSITIVITY -> "cps per μSv/h"
            DeviceConfigManager.KEY_FREQ_LOW,
            DeviceConfigManager.KEY_FREQ_MAIN,
            DeviceConfigManager.KEY_FREQ_HIGH -> "Hz"
            DeviceConfigManager.KEY_DURATION,
            DeviceConfigManager.KEY_ONE_BEEP_TOL,
            DeviceConfigManager.KEY_TWO_BEEP_TOL,
            DeviceConfigManager.KEY_THREE_BEEP_TOL,
            DeviceConfigManager.KEY_FOUR_BEEP_TOL -> "s"
            else -> null
        }
        pref.summary = if (unit == null) value else "$value $unit"
    }
}
