package com.github.bocovp.geigergpx

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

enum class DoseRateFormatting(
    val preferenceLabel: String,
    val sampleValue: String,
    val unit: String
) {
    ABSOLUTE_USV("Absolute error in μSv/h", "0.15 ± 0.05 μSv/h", "μSv/h"),
    RELATIVE_USV("Relative error in μSv/h", "0.15 μSv/h ± 10%", "μSv/h"),
    INTERVAL_USV("Interval in μSv/h", "0.12 … 0.14 μSv/h", "μSv/h"),
    ABSOLUTE_CPS("Absolute error in cps", "15.1 ± 1.5 cps", "cps"),
    RELATIVE_CPS("Relative error in cps", "15.1 cps ± 10%", "cps"),
    INTERVAL_CPS("Interval in cps", "12.5 … 14.2 cps", "cps");

    val isDoseRate: Boolean get() = unit == "μSv/h"
    val isRelative: Boolean get() = this == RELATIVE_USV || this == RELATIVE_CPS
    val isInterval: Boolean get() = this == INTERVAL_USV || this == INTERVAL_CPS

    fun correspondingCps(): DoseRateFormatting = when (this) {
        ABSOLUTE_USV -> ABSOLUTE_CPS
        RELATIVE_USV -> RELATIVE_CPS
        INTERVAL_USV -> INTERVAL_CPS
        else -> this
    }

    fun nextSameUnit(): DoseRateFormatting = when (this) {
        ABSOLUTE_USV -> RELATIVE_USV
        RELATIVE_USV -> INTERVAL_USV
        INTERVAL_USV -> ABSOLUTE_USV
        ABSOLUTE_CPS -> RELATIVE_CPS
        RELATIVE_CPS -> INTERVAL_CPS
        INTERVAL_CPS -> ABSOLUTE_CPS
    }

    companion object {
        private val VALUES = values()
        val allLabels: List<String> = VALUES.map { it.preferenceLabel }

        fun fromLabel(label: String?): DoseRateFormatting? = VALUES.firstOrNull { it.preferenceLabel == label }
        fun fromPrefs(prefs: SharedPreferences): DoseRateFormatting =
            fromLabel(prefs.getString(SettingsKeys.KEY_DOSE_RATE_FORMATTING, null)) ?: ABSOLUTE_USV

        fun validForSensitivity(formatting: DoseRateFormatting, sensitivity: Double): DoseRateFormatting =
            if (sensitivity == 1.0) formatting.correspondingCps() else formatting

        fun normalizePrefsForSensitivity(prefs: SharedPreferences, sensitivity: Double): DoseRateFormatting {
            val current = fromPrefs(prefs)
            val normalized = validForSensitivity(current, sensitivity)
            if (normalized != current) {
                prefs.edit { putString(SettingsKeys.KEY_DOSE_RATE_FORMATTING, normalized.preferenceLabel) }
            }
            return normalized
        }

        fun format(ci: ConfidenceInterval, counts: Int, sensitivity: Double, decimalDigits: Int, formatting: DoseRateFormatting): String {
            val scaled = if (formatting.isDoseRate) ci.scale(1.0 / sensitivity) else ci
            return when {
                formatting.isInterval -> "${scaled.toIntervalText(decimalDigits)} ${formatting.unit}"
                formatting.isRelative -> {
                    val relative = ConfidenceInterval.relativeErrPercent(counts)
                    val percentText = if (relative == null) "0%" else String.format(Locale.US, "%.0f%%", relative)
                    String.format(Locale.US, "%.${decimalDigits}f %s ± %s", scaled.mean, formatting.unit, percentText)
                }
                else -> "${scaled.toPlusMinusText(decimalDigits)} ${formatting.unit}"
            }
        }
    }
}
