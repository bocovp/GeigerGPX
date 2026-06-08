package com.github.bocovp.geigergpx

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.XmlRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

object DeviceConfigManager {
    const val CUSTOM_DEVICE_NAME = "Custom"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_FREQ_LOW = "device_freqLow"
    const val KEY_FREQ_MAIN = "device_freqMain"
    const val KEY_FREQ_HIGH = "device_freqHigh"
    const val KEY_DURATION = "device_duration"
    const val KEY_DOMINANCE_THRESHOLD = "device_dominanceThreshold"
    const val KEY_DOMINANCE_THRESHOLD_END = "device_dominanceThresholdEnd"
    const val KEY_WINDOW_SIZE = "device_windowSize"
    const val KEY_STEP_SIZE = "device_stepSize"
    const val KEY_ONE_BEEP_TOL = "device_oneBeepTol"
    const val KEY_TWO_BEEP_TOL = "device_twoBeepTol"
    const val KEY_THREE_BEEP_TOL = "device_threeBeepTol"
    const val KEY_FOUR_BEEP_TOL = "device_fourBeepTol"
    const val KEY_COUNTS_PER_BEEP = "device_countsPerBeep"

    data class Device(
        val name: String,
        val sensitivity: Double,
        val fallbackConfig: GoertzelDetector.RateConfig,
        val rateConfigs: Map<Int, GoertzelDetector.RateConfig>
    )

    private val lock = Any()
    private var appContext: Context? = null
    private var parsedDevices: List<Device> = emptyList()

    fun init(context: Context, @XmlRes xmlRes: Int = R.xml.devices) {
        synchronized(lock) {
            appContext = context.applicationContext
            parsedDevices = try {
                parseDevices(context.applicationContext, xmlRes)
            } catch (e: Exception) {
                android.util.Log.e("DeviceConfigManager", "Failed to parse devices XML", e)
                emptyList()
            }
            ensurePreferences(context.applicationContext)
        }
    }

    fun devices(context: Context? = null): List<Device> {
        ensureInitialized(context)
        return synchronized(lock) { parsedDevices }
    }

    fun currentDeviceName(prefs: SharedPreferences): String {
        val stored = prefs.getString(KEY_DEVICE_NAME, null)
        val names =  try {
            devices().map { it.name }
        } catch (_: IllegalStateException) {
            emptyList()
        }
        return when {
            stored == CUSTOM_DEVICE_NAME -> CUSTOM_DEVICE_NAME
            stored != null && names.contains(stored) -> stored
            names.isNotEmpty() -> names.first()
            else -> CUSTOM_DEVICE_NAME
        }
    }

    fun currentDevice(prefs: SharedPreferences): Device? {
        val name = currentDeviceName(prefs)
        return try {
            devices().firstOrNull { it.name == name }
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun isCustom(prefs: SharedPreferences): Boolean = currentDeviceName(prefs) == CUSTOM_DEVICE_NAME

    fun sensitivityFromPrefs(prefs: SharedPreferences): Double {
        currentDevice(prefs)?.let { return it.sensitivity }
        return prefs.getString(RadiationCalibration.KEY_SENSITIVITY, null)?.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: RadiationCalibration.DEFAULT_SENSITIVITY
    }

    fun countsPerBeepFromPrefs(prefs: SharedPreferences): Int {
        currentDevice(prefs)?.let { return it.fallbackConfig.countsPerBeep }
        return prefs.getString(KEY_COUNTS_PER_BEEP, null)?.toIntOrNull()?.takeIf {  it in 1..1000  } ?: 1
    }

    fun rateConfigFor(sampleRate: Int): GoertzelDetector.RateConfig {
        val context = synchronized(lock) { appContext } ?: return configFromPars(emptyMap())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val device = currentDevice(prefs)
        if (device != null) {
            return device.rateConfigs[sampleRate] ?: device.fallbackConfig
        }
        return customFallbackConfig(prefs)
    }

    fun selectDevice(context: Context, name: String) {
        ensureInitialized(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit { putString(KEY_DEVICE_NAME, name) }
        if (name == CUSTOM_DEVICE_NAME) ensureCustomDefaults(context, force = false)
    }

    fun displayConfig(context: Context): GoertzelDetector.RateConfig {
        ensureInitialized(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return currentDevice(prefs)?.fallbackConfig ?: customFallbackConfig(prefs)
    }

    fun detectorName(context: Context): String = "Goertzel detector"

    fun formatNumber(value: Double): String {
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded % 1.0 == 0.0) {
            "%.1f".format(Locale.US, rounded)
        } else {
            rounded.toString()
        }
    }

    fun formatInt(value: Int): String = value.toString()

    fun defaultDeviceName(context: Context): String {
        ensureInitialized(context)
        return devices(context).firstOrNull()?.name ?: CUSTOM_DEVICE_NAME
    }

    private fun ensureInitialized(context: Context?): Context {
        synchronized(lock) {
            appContext?.let { return it }
            val ctx = context ?: throw IllegalStateException("DeviceConfigManager is not initialized")
            init(ctx)
            return appContext!!
        }
    }

    private fun ensurePreferences(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.contains(KEY_DEVICE_NAME)) {
            prefs.edit { putString(KEY_DEVICE_NAME, parsedDevices.firstOrNull()?.name ?: CUSTOM_DEVICE_NAME) }
        }
        ensureCustomDefaults(context, force = false)
    }

    private fun ensureCustomDefaults(context: Context, force: Boolean) {
        val first = synchronized(lock) { parsedDevices.firstOrNull() } ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val c = first.fallbackConfig
        prefs.edit {
            putDefaultString(this, RadiationCalibration.KEY_SENSITIVITY, formatNumber(first.sensitivity), prefs, force)
            putDefaultString(this, KEY_FREQ_LOW, formatNumber(c.freqLow.toDouble()), prefs, force)
            putDefaultString(this, KEY_FREQ_MAIN, formatNumber(c.freqMain.toDouble()), prefs, force)
            putDefaultString(this, KEY_FREQ_HIGH, formatNumber(c.freqHigh.toDouble()), prefs, force)
            putDefaultString(this, KEY_DURATION, formatNumber(c.duration), prefs, force)
            putDefaultString(this, KEY_DOMINANCE_THRESHOLD, formatNumber(c.dominanceThreshold.toDouble()), prefs, force)
            putDefaultString(this, KEY_DOMINANCE_THRESHOLD_END, formatNumber(c.dominanceThresholdEnd.toDouble()), prefs, force)
            putDefaultString(this, KEY_WINDOW_SIZE, formatInt(c.windowSize), prefs, force)
            putDefaultString(this, KEY_STEP_SIZE, formatInt(c.stepSize), prefs, force)
            putDefaultString(this, KEY_ONE_BEEP_TOL, formatNumber(c.oneBeepTol), prefs, force)
            putDefaultString(this, KEY_TWO_BEEP_TOL, formatNumber(c.twoBeepTol), prefs, force)
            putDefaultString(this, KEY_THREE_BEEP_TOL, formatNumber(c.threeBeepTol), prefs, force)
            putDefaultString(this, KEY_FOUR_BEEP_TOL, formatNumber(c.fourBeepTol), prefs, force)
            putDefaultString(this, KEY_COUNTS_PER_BEEP, formatInt(c.countsPerBeep), prefs, force)
        }
    }

    private fun putDefaultString(
        editor: SharedPreferences.Editor,
        key: String,
        value: String,
        prefs: SharedPreferences,
        force: Boolean
    ) {
        if (force || !prefs.contains(key)) editor.putString(key, value)
    }

    private fun customFallbackConfig(prefs: SharedPreferences): GoertzelDetector.RateConfig {
        return GoertzelDetector.RateConfig(
            windowSize = prefs.getString(KEY_WINDOW_SIZE, null)?.toIntOrNull()?.takeIf { it > 0 } ?: 205,
            stepSize = prefs.getString(KEY_STEP_SIZE, null)?.toIntOrNull()?.takeIf { it > 0 } ?: 32,
            freqMain = prefs.getString(KEY_FREQ_MAIN, null)?.toFloatOrNull()?.takeIf { it > 0f } ?: 3276.8f,
            freqLow = prefs.getString(KEY_FREQ_LOW, null)?.toFloatOrNull()?.takeIf { it > 0f } ?: 3076.8f,
            freqHigh = prefs.getString(KEY_FREQ_HIGH, null)?.toFloatOrNull()?.takeIf { it > 0f } ?: 3476.8f,
            duration = prefs.getString(KEY_DURATION, null)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.025,
            dominanceThreshold = prefs.getString(KEY_DOMINANCE_THRESHOLD, null)?.toFloatOrNull()?.takeIf { it > 0f } ?: 2.0f,
            dominanceThresholdEnd = prefs.getString(KEY_DOMINANCE_THRESHOLD_END, null)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.1f,
            oneBeepTol = prefs.getString(KEY_ONE_BEEP_TOL, null)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.01,
            twoBeepTol = prefs.getString(KEY_TWO_BEEP_TOL, null)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.015,
            threeBeepTol = prefs.getString(KEY_THREE_BEEP_TOL, null)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.015,
            fourBeepTol = prefs.getString(KEY_FOUR_BEEP_TOL, null)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.015,
            countsPerBeep = prefs.getString(KEY_COUNTS_PER_BEEP, null)?.toIntOrNull()?.takeIf { it in 1..1000 } ?: 1
        )
    }

    private fun parseDevices(context: Context, @XmlRes xmlRes: Int): List<Device> {
        val result = mutableListOf<Device>()
        context.resources.getXml(xmlRes).use { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "device") {
                    result.add(parseDevice(parser))
                }
                event = parser.next()
            }
        }
        return result
    }

    private fun parseDevice(parser: XmlPullParser): Device {
        val name = parser.getAttributeValue(null, "name") ?: "Unknown"
        var sensitivity = RadiationCalibration.DEFAULT_SENSITIVITY
        val fallbackPars = mutableMapOf<String, String>()
        val parameterSets = mutableListOf<Map<String, String>>()
        val startDepth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT && !(event == XmlPullParser.END_TAG && parser.depth == startDepth && parser.name == "device")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "sensitivity" -> sensitivity = parser.nextText().trim().toDoubleOrNull() ?: sensitivity
                    "detector" -> parseDetector(parser, fallbackPars, parameterSets)
                }
            }
            event = parser.next()
        }
        val fallback = configFromPars(fallbackPars)
        val configs = parameterSets.mapNotNull { pars ->
            val sampleRate = pars["sampleRate"]?.toIntOrNull() ?: return@mapNotNull null
            sampleRate to configFromPars(pars, fallback, sampleRate)
        }.toMap()
        return Device(name, sensitivity, fallback, configs)
    }

    private fun parseDetector(
        parser: XmlPullParser,
        fallbackPars: MutableMap<String, String>,
        parameterSets: MutableList<Map<String, String>>
    ) {
        val startDepth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT && !(event == XmlPullParser.END_TAG && parser.depth == startDepth && parser.name == "detector")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "par" -> {
                        val key = parser.getAttributeValue(null, "name")
                        val value = parser.nextText().trim()
                        if (key != null) fallbackPars[key] = value
                    }
                    "parameterSet" -> parameterSets.add(parseParameterSet(parser))
                }
            }
            event = parser.next()
        }
    }

    private fun parseParameterSet(parser: XmlPullParser): Map<String, String> {
        val pars = mutableMapOf<String, String>()
        val startDepth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT && !(event == XmlPullParser.END_TAG && parser.depth == startDepth && parser.name == "parameterSet")) {
            if (event == XmlPullParser.START_TAG && parser.name == "par") {
                val key = parser.getAttributeValue(null, "name")
                val value = parser.nextText().trim()
                if (key != null) pars[key] = value
            }
            event = parser.next()
        }
        return pars
    }

    private fun configFromPars(
        pars: Map<String, String>,
        fallback: GoertzelDetector.RateConfig? = null,
        sampleRate: Int? = null
    ): GoertzelDetector.RateConfig {
        val base = fallback ?: GoertzelDetector.RateConfig(
            windowSize = 205,
            stepSize = 32,
            freqMain = 3276.8f,
            freqLow = 3076.8f,
            freqHigh = 3476.8f,
            duration = 0.025,
            dominanceThreshold = 2.0f,
            dominanceThresholdEnd = 1.1f,
            oneBeepTol = 0.01,
            twoBeepTol = 0.015,
            threeBeepTol = 0.015,
            fourBeepTol = 0.015,
            countsPerBeep = 1
        )
        var config = base.copy(
            windowSize = pars["windowSize"]?.toIntOrNull()?.takeIf { it > 0 } ?: base.windowSize,
            stepSize = pars["stepSize"]?.toIntOrNull()?.takeIf { it > 0 } ?: base.stepSize,
            freqMain = pars["freqMain"]?.toFloatOrNull()?.takeIf { it > 0 } ?: base.freqMain,
            freqLow = pars["freqLow"]?.toFloatOrNull()?.takeIf { it > 0 } ?: base.freqLow,
            freqHigh = pars["freqHigh"]?.toFloatOrNull()?.takeIf { it > 0 } ?: base.freqHigh,
            duration = pars["duration"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: base.duration,
            dominanceThreshold = pars["dominanceThreshold"]?.toFloatOrNull()?.takeIf { it > 0 } ?: base.dominanceThreshold,
            dominanceThresholdEnd = pars["dominanceThresholdEnd"]?.toFloatOrNull()?.takeIf { it > 0 } ?: base.dominanceThresholdEnd,
            oneBeepTol = pars["oneBeepTol"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: base.oneBeepTol,
            twoBeepTol = pars["twoBeepTol"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: base.twoBeepTol,
            threeBeepTol = pars["threeBeepTol"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: base.threeBeepTol,
            fourBeepTol = pars["fourBeepTol"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: base.fourBeepTol,
            countsPerBeep = pars["countsPerBeep"]?.toIntOrNull()?.takeIf { it in 1..1000 } ?: base.countsPerBeep
        )
        if (sampleRate != null && config.windowSize > 0) {
            val binWidth = sampleRate.toFloat() / config.windowSize.toFloat()
            config = config.copy(
                freqLow = pars["binLow"]?.toFloatOrNull()?.let { it * binWidth } ?: config.freqLow,
                freqMain = pars["binMain"]?.toFloatOrNull()?.let { it * binWidth } ?: config.freqMain,
                freqHigh = pars["binHigh"]?.toFloatOrNull()?.let { it * binWidth } ?: config.freqHigh
            )
        }
        return config
    }
}
