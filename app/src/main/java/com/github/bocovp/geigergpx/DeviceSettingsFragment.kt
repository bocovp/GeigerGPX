package com.github.bocovp.geigergpx

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

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
        DeviceConfigManager.KEY_FOUR_BEEP_TOL,
        DeviceConfigManager.KEY_COUNTS_PER_BEEP
    )

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DeviceConfigManager.KEY_DEVICE_NAME || (key != null && key in preferenceKeys)) refreshUi()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.device_prefs, rootKey)
        setupInteractions()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun getUnitFor(key: String): String {
        return when (key) {
            RadiationCalibration.KEY_SENSITIVITY -> "cps per μSv/h"
            DeviceConfigManager.KEY_FREQ_LOW,
            DeviceConfigManager.KEY_FREQ_MAIN,
            DeviceConfigManager.KEY_FREQ_HIGH -> "Hz"
            DeviceConfigManager.KEY_DURATION,
            DeviceConfigManager.KEY_WINDOW_SIZE,
            DeviceConfigManager.KEY_STEP_SIZE,
            DeviceConfigManager.KEY_ONE_BEEP_TOL,
            DeviceConfigManager.KEY_TWO_BEEP_TOL,
            DeviceConfigManager.KEY_THREE_BEEP_TOL,
            DeviceConfigManager.KEY_FOUR_BEEP_TOL -> "s"
            else -> ""
        }
    }

    private fun refreshUi() {
        val device = DeviceConfigManager.currentDevice(requireContext()) ?: return
        val isCustom = device.isCustom

        val choosePref = findPreference<Preference>("choose_device")
        choosePref?.summary = if (isCustom) "Custom Device" else "Built-in Device"

        val renamePref = findPreference<Preference>("device_name_pref")
        renamePref?.summary = device.name

        preferenceKeys.forEach { key ->
            val value = DeviceConfigManager.getPropertyValue(device, key)
            setEditValue(key, value, isCustom)
        }
    }

    private fun setEditValue(key: String, value: String, enabled: Boolean) {
        val pref = findPreference<EditTextPreference>(key) ?: return
        pref.isEnabled = enabled

        val unit = getUnitFor(key)
        // Format places the dimension safely after the value
        val formattedSummary = if (unit.isNotEmpty()) "$value $unit" else value

        pref.text = value
        pref.summary = formattedSummary
    }

    private fun setupInteractions() {
        val choosePref = findPreference<Preference>("choose_device")
        choosePref?.setOnPreferenceClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, DeviceListFragment())
                .addToBackStack(null)
                .commit()
            true
        }

        val renamePref = findPreference<Preference>("device_name_pref")
        renamePref?.setOnPreferenceClickListener {
            val device = DeviceConfigManager.currentDevice(requireContext()) ?: return@setOnPreferenceClickListener true
            if (!device.isCustom) {
                Toast.makeText(context, "Built-in devices cannot be renamed", Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            val input = EditText(requireContext())
            input.setText(device.name)
            input.setSelection(input.text.length)

            AlertDialog.Builder(requireContext())
                .setTitle("Rename device")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty() && newName != device.name) {
                        val success = DeviceConfigManager.renameActiveDevice(requireContext(), newName)
                        if (success) {
                            refreshUi()
                        } else {
                            Toast.makeText(requireContext(), "Name already exists", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        preferenceKeys.forEach { key ->
            val pref = findPreference<Preference>(key)
            if (pref is EditTextPreference) {
                pref.isPersistent = false // We handle persistence manually via XML
                pref.setOnPreferenceChangeListener { preference, newValue ->
                    val device = DeviceConfigManager.currentDevice(requireContext())
                    if (device != null && device.isCustom) {
                        DeviceConfigManager.updateActiveDeviceProperty(requireContext(), key, newValue.toString())
                        val updatedValue = DeviceConfigManager.getPropertyValue(DeviceConfigManager.currentDevice(requireContext())!!, key)

                        val unit = getUnitFor(key)
                        val formattedSummary = if (unit.isNotEmpty()) "$updatedValue $unit" else updatedValue

                        (preference as EditTextPreference).text = updatedValue
                        preference.summary = formattedSummary
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}