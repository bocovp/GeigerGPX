package com.github.bocovp.geigergpx

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

enum class DoseRateDimension(
    val preferenceLabel: String,
    val unit: String
) {
    USV_H("μSv/h", "μSv/h"),
    CPS("cps", "cps");

    companion object {
        private const val KEY = SettingsKeys.KEY_DOSE_RATE_DIMENSION
        private const val LOCK_EPSILON = 1e-9
        private val VALUES = values()
        val allLabels: List<String> = VALUES.map { it.preferenceLabel }

        fun fromLabel(label: String?): DoseRateDimension? = VALUES.firstOrNull { it.preferenceLabel == label }

        fun fromPrefs(prefs: SharedPreferences, sensitivity: Double? = null): DoseRateDimension {
            val dimension = fromLabel(prefs.getString(KEY, null))
                ?: legacyDimensionFromFormatting(prefs.getString(SettingsKeys.KEY_DOSE_RATE_FORMATTING, null))
                ?: USV_H
            return if (isLockedToCps(sensitivity)) CPS else dimension
        }

        fun isLockedToCps(sensitivity: Double?): Boolean = sensitivity != null && kotlin.math.abs(sensitivity - 1.0) < LOCK_EPSILON

        fun normalizePrefsForSensitivity(prefs: SharedPreferences, sensitivity: Double): DoseRateDimension {
            val current = fromPrefs(prefs, sensitivity)
            if (isLockedToCps(sensitivity)) prefs.edit { putString(KEY, CPS.preferenceLabel) }
            return current
        }

        private fun legacyDimensionFromFormatting(label: String?): DoseRateDimension? = when {
            label == null -> null
            label.contains("cps", ignoreCase = true) -> CPS
            label.contains("Sv/h", ignoreCase = true) -> USV_H
            else -> null
        }
    }
}

enum class DoseRateErrorFormat {
    ABSOLUTE,
    RELATIVE,
    INTERVAL;

    fun next(): DoseRateErrorFormat = when (this) {
        ABSOLUTE -> RELATIVE
        RELATIVE -> INTERVAL
        INTERVAL -> ABSOLUTE
    }

    companion object {
        fun fromPrefs(prefs: SharedPreferences): DoseRateErrorFormat =
            runCatching { valueOf(prefs.getString(SettingsKeys.KEY_DOSE_RATE_ERROR_FORMAT, null) ?: ABSOLUTE.name) }
                .getOrDefault(ABSOLUTE)

        fun save(prefs: SharedPreferences, format: DoseRateErrorFormat) {
            prefs.edit { putString(SettingsKeys.KEY_DOSE_RATE_ERROR_FORMAT, format.name) }
        }
    }
}

object DoseRateFormatter {
    fun format(ci: ConfidenceInterval, sensitivity: Double, decimalDigits: Int, dimension: DoseRateDimension, errorFormat: DoseRateErrorFormat): String {
        val counts = ci.sampleCount
        if (counts < 2) return "0 ${dimension.unit}"
        val scaled = scale(ci, sensitivity, dimension)
        return when (errorFormat) {
            DoseRateErrorFormat.INTERVAL -> "${scaled.toIntervalText(decimalDigits)} ${dimension.unit}"
            DoseRateErrorFormat.RELATIVE -> {
                val relative = ConfidenceInterval.relativeErrPercent(counts)
                val percentText = if (relative == null) "0%" else String.format(Locale.US, "%.0f%%", relative)
                String.format(Locale.US, "%.${decimalDigits}f %s ± %s", scaled.mean, dimension.unit, percentText)
            }
            DoseRateErrorFormat.ABSOLUTE -> "${scaled.toPlusMinusText(decimalDigits)} ${dimension.unit}"
        }
    }

    fun scale(ci: ConfidenceInterval, sensitivity: Double, dimension: DoseRateDimension): ConfidenceInterval =
        if (dimension == DoseRateDimension.USV_H) ci.scale(if (sensitivity > 0.0) 1.0 / sensitivity else 0.0) else ci

    fun valueFromDoseRate(doseRate: Double, sensitivity: Double, dimension: DoseRateDimension): Double =
        if (dimension == DoseRateDimension.CPS) RadiationCalibration.cpsFromDoseRate(doseRate, sensitivity) else doseRate

    fun doseRateFromDisplayValue(value: Double, sensitivity: Double, dimension: DoseRateDimension): Double =
        if (dimension == DoseRateDimension.CPS) RadiationCalibration.doseRateFromCps(value, sensitivity) else value
}
