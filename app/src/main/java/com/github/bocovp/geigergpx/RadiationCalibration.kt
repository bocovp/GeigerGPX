package com.github.bocovp.geigergpx

import android.content.SharedPreferences
import java.util.Locale

object RadiationCalibration {
    const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_LEGACY_CPS_TO_USVH = "cps_to_usvh"
    const val DEFAULT_SENSITIVITY = 10.0

    fun sensitivityFromPrefs(prefs: SharedPreferences): Double {
        return try {
            DeviceConfigManager.sensitivityFromPrefs(prefs)
        } catch (_: IllegalStateException) {
            val sensitivity = prefs.getString(KEY_SENSITIVITY, null)?.toDoubleOrNull()
            if (sensitivity != null && sensitivity > 0.0) return sensitivity

            val legacyCoefficient = prefs.getString(KEY_LEGACY_CPS_TO_USVH, null)?.toDoubleOrNull()
            if (legacyCoefficient != null && legacyCoefficient > 0.0) return 1.0 / legacyCoefficient

            DEFAULT_SENSITIVITY
        }
    }

    fun doseRateFromCps(cps: Double, sensitivity: Double): Double {
        return if (sensitivity > 0.0) cps / sensitivity else 0.0
    }

    fun cpsFromDoseRate(doseRate: Double, sensitivity: Double): Double {
        return doseRate * sensitivity
    }

    fun formatSensitivity(value: Double): String {
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded % 1.0 == 0.0) {
            "%.1f".format(Locale.US, rounded)
        } else {
            "%s".format(Locale.US, rounded.toString())
        }
    }
}
