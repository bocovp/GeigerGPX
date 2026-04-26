package com.github.bocovp.geigergpx

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager

class AppSettings private constructor(
    private val prefs: SharedPreferences
) {
    companion object {
        const val KEY_CPS_TO_USVH = "cps_to_usvh"
        const val KEY_DOSE_RATE_AVG_TIMESTAMPS_N = "dose_rate_avg_timestamps_n"
        const val KEY_ALERT_DOSE_RATE = "alert_dose_rate"
        const val KEY_MAX_SPEED_KMH = "max_speed_kmh"
        const val KEY_POINT_SPACING_M = "point_spacing_m"
        const val KEY_MIN_COUNTS_PER_POINT = "min_counts_per_point"
        const val KEY_MAX_TIME_WITHOUT_COUNTS_S = "max_time_without_counts_s"
        const val KEY_MAX_TIME_WITHOUT_GPS_S = "max_time_without_gps_s"
        const val KEY_SAVE_DOSE_RATE_IN_ELE = "save_dose_rate_in_ele"
        const val KEY_GPX_TREE_URI = "gpx_tree_uri"
        const val KEY_AUDIO_THRESHOLD = "audio_threshold"
        const val KEY_BLUETOOTH_AUDIO_THRESHOLD = "bluetooth_audio_threshold"
        const val KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE = "use_bluetooth_mic_if_available"
        private const val LEGACY_KEY_GPX_FOLDER_URI = "gpx_folder_uri"
        private const val LEGACY_KEY_DOSE_RATE_WINDOW_SIZE = "dose_rate_window_size"

        const val DEFAULT_CPS_TO_USVH = 1.0
        const val DEFAULT_ALERT_DOSE_RATE = 0.0
        const val DEFAULT_MAX_SPEED_KMH = 30.0
        const val DEFAULT_POINT_SPACING_M = 5.0
        const val DEFAULT_MIN_COUNTS_PER_POINT = 0
        const val DEFAULT_MAX_TIME_WITHOUT_COUNTS_S = 1.0
        const val DEFAULT_MAX_TIME_WITHOUT_GPS_S = 60.0
        const val DEFAULT_DOSE_RATE_WINDOW_SIZE = 10
        val ALLOWED_DOSE_RATE_WINDOW_SIZES = setOf(5, 10, 20, 50, 100)

        fun from(context: Context): AppSettings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            return AppSettings(prefs)
        }

        fun migrateLegacyKeys(context: Context) {
            from(context).migrateLegacyKeys()
        }
    }

    fun migrateLegacyKeys() {
        var hasChanges = false
        val editor = prefs.edit()

        if (!prefs.contains(KEY_GPX_TREE_URI)) {
            val legacyTreeUri = prefs.getString(LEGACY_KEY_GPX_FOLDER_URI, null)
            if (!legacyTreeUri.isNullOrBlank()) {
                editor.putString(KEY_GPX_TREE_URI, legacyTreeUri)
                hasChanges = true
            }
        }

        if (!prefs.contains(KEY_DOSE_RATE_AVG_TIMESTAMPS_N)) {
            val legacyWindowSize = prefs.getString(LEGACY_KEY_DOSE_RATE_WINDOW_SIZE, null)
            if (!legacyWindowSize.isNullOrBlank()) {
                editor.putString(KEY_DOSE_RATE_AVG_TIMESTAMPS_N, legacyWindowSize)
                hasChanges = true
            }
        }

        if (hasChanges) {
            editor.apply()
        }
    }

    fun setGpxTreeUri(uri: Uri?) {
        prefs.edit().putString(KEY_GPX_TREE_URI, uri?.toString()).apply()
    }

    fun getGpxTreeUriString(): String? {
        val primary = prefs.getString(KEY_GPX_TREE_URI, null)
        if (!primary.isNullOrBlank()) return primary
        return prefs.getString(LEGACY_KEY_GPX_FOLDER_URI, null)
    }

    fun shouldSaveDoseRateInEle(): Boolean = prefs.getBoolean(KEY_SAVE_DOSE_RATE_IN_ELE, false)

    fun shouldUseBluetoothMicIfAvailable(defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, defaultValue)
    }

    fun getAudioThreshold(bluetooth: Boolean, defaultValue: Float): Float {
        val key = if (bluetooth) KEY_BLUETOOTH_AUDIO_THRESHOLD else KEY_AUDIO_THRESHOLD
        val stored = prefs.getFloat(key, Float.NaN)
        return if (!stored.isNaN() && stored > 0f && stored.isFinite()) stored else defaultValue
    }

    fun getCpsToUsvhCoefficient(): Double {
        return prefs.getString(KEY_CPS_TO_USVH, DEFAULT_CPS_TO_USVH.toString())
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 && it.isFinite() }
            ?: DEFAULT_CPS_TO_USVH
    }

    fun getAlertDoseRate(): Double {
        return prefs.getString(KEY_ALERT_DOSE_RATE, DEFAULT_ALERT_DOSE_RATE.toString())
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: DEFAULT_ALERT_DOSE_RATE
    }

    fun getDoseRateAvgWindowSize(): Int {
        val parsed = prefs.getString(KEY_DOSE_RATE_AVG_TIMESTAMPS_N, DEFAULT_DOSE_RATE_WINDOW_SIZE.toString())
            ?.toIntOrNull()
            ?: prefs.getString(LEGACY_KEY_DOSE_RATE_WINDOW_SIZE, DEFAULT_DOSE_RATE_WINDOW_SIZE.toString())
                ?.toIntOrNull()
            ?: DEFAULT_DOSE_RATE_WINDOW_SIZE
        return parsed.takeIf { it in ALLOWED_DOSE_RATE_WINDOW_SIZES } ?: DEFAULT_DOSE_RATE_WINDOW_SIZE
    }

    fun getMaxSpeedKmh(): Double {
        return prefs.getString(KEY_MAX_SPEED_KMH, DEFAULT_MAX_SPEED_KMH.toString())
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it.isFinite() }
            ?: DEFAULT_MAX_SPEED_KMH
    }

    fun getPointSpacingMeters(): Double {
        return prefs.getString(KEY_POINT_SPACING_M, DEFAULT_POINT_SPACING_M.toString())
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it.isFinite() }
            ?: DEFAULT_POINT_SPACING_M
    }

    fun getMinCountsPerPoint(): Int {
        return prefs.getString(KEY_MIN_COUNTS_PER_POINT, DEFAULT_MIN_COUNTS_PER_POINT.toString())
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: DEFAULT_MIN_COUNTS_PER_POINT
    }

    fun getMaxTimeWithoutCountsSeconds(): Double {
        return prefs.getString(KEY_MAX_TIME_WITHOUT_COUNTS_S, DEFAULT_MAX_TIME_WITHOUT_COUNTS_S.toString())
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it.isFinite() }
            ?: DEFAULT_MAX_TIME_WITHOUT_COUNTS_S
    }

    fun getMaxTimeWithoutGpsSeconds(): Double {
        return prefs.getString(KEY_MAX_TIME_WITHOUT_GPS_S, DEFAULT_MAX_TIME_WITHOUT_GPS_S.toString())
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it.isFinite() }
            ?: DEFAULT_MAX_TIME_WITHOUT_GPS_S
    }
}
