package com.github.bocovp.geigergpx

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.XmlRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object DeviceConfigManager {
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

    // This class strictly models the XML data (seconds, Hz, etc.)
    data class DeviceConfig(
        val windowSize: Double,
        val stepSize: Double,
        val freqMain: Float,
        val freqLow: Float,
        val freqHigh: Float,
        val duration: Double,
        val dominanceThreshold: Float,
        val dominanceThresholdEnd: Float,
        val oneBeepTol: Double,
        val twoBeepTol: Double,
        val threeBeepTol: Double,
        val fourBeepTol: Double,
        val countsPerBeep: Int
    )

    data class Device(
        val name: String,
        val sensitivity: Double,
        val fallbackConfig: DeviceConfig,
        val rateConfigs: Map<Int, GoertzelDetector.RateConfig>,
        val isCustom: Boolean = false
    )

    private val lock = Any()
    private var appContext: Context? = null
    private var parsedDevices: List<Device> = emptyList()

    fun init(context: Context, @XmlRes xmlRes: Int = R.xml.devices) {
        synchronized(lock) {
            appContext = context.applicationContext
            val builtIn = try {
                parseDevices(context.applicationContext, xmlRes)
            } catch (e: Exception) {
                android.util.Log.e("DeviceConfigManager", "Failed to parse built-in devices XML", e)
                emptyList()
            }

            val customFile = File(context.filesDir, "custom_devices.xml")
            val custom = if (customFile.exists()) {
                try {
                    parseDevicesFromFile(customFile)
                } catch (e: Exception) {
                    android.util.Log.e("DeviceConfigManager", "Failed to parse custom devices XML", e)
                    emptyList()
                }
            } else emptyList()

            parsedDevices = builtIn + custom
            ensurePreferences(context.applicationContext)
        }
    }

    fun devices(context: Context? = null): List<Device> {
        ensureInitialized(context)
        return synchronized(lock) { parsedDevices }
    }

    fun currentDeviceName(prefs: SharedPreferences): String {
        val stored = prefs.getString(KEY_DEVICE_NAME, null)
        val names = try {
            devices().map { it.name }
        } catch (_: IllegalStateException) {
            emptyList()
        }
        return if (stored != null && names.contains(stored)) stored else names.firstOrNull() ?: "Unknown"
    }

    fun currentDevice(context: Context): Device? {
        ensureInitialized(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val name = currentDeviceName(prefs)
        return try {
            devices(context).firstOrNull { it.name == name }
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun sensitivityFromPrefs(prefs: SharedPreferences): Double {
        return currentDeviceName(prefs).let { name ->
            synchronized(lock) {
                parsedDevices.firstOrNull { it.name == name }?.sensitivity
            }} ?: RadiationCalibration.DEFAULT_SENSITIVITY
    }

    fun rateConfigFor(sampleRate: Int): GoertzelDetector.RateConfig {
        val context = synchronized(lock) { appContext } ?: return defaultConfig(sampleRate)
        val device = currentDevice(context) ?: return defaultConfig(sampleRate)

        // Exact parameterSet matches take priority
        device.rateConfigs[sampleRate]?.let { return it }

        // Dynamic fallback conversion from Seconds -> Samples
        val fb = device.fallbackConfig
        return GoertzelDetector.RateConfig(
            windowSamples = (fb.windowSize * sampleRate).roundToInt().coerceAtLeast(3),
            stepSamples = (fb.stepSize * sampleRate).roundToInt().coerceAtLeast(1),
            freqMain = fb.freqMain,
            freqLow = fb.freqLow,
            freqHigh = fb.freqHigh,
            duration = fb.duration,
            dominanceThreshold = fb.dominanceThreshold,
            dominanceThresholdEnd = fb.dominanceThresholdEnd,
            oneBeepTol = fb.oneBeepTol,
            twoBeepTol = fb.twoBeepTol,
            threeBeepTol = fb.threeBeepTol,
            fourBeepTol = fb.fourBeepTol,
            countsPerBeep = fb.countsPerBeep
        )
    }

    private fun defaultConfig(sampleRate: Int): GoertzelDetector.RateConfig {
        return GoertzelDetector.RateConfig(
            windowSamples = (0.00427 * sampleRate).roundToInt().coerceAtLeast(3),
            stepSamples = (0.000667 * sampleRate).roundToInt().coerceAtLeast(1),
            freqMain = 3276.8f,
            freqLow = 3076.8f,
            freqHigh = 3476.8f,
            duration = 0.025,
            dominanceThreshold = 2.0f,
            dominanceThresholdEnd = 1.1f,
            oneBeepTol = 0.01,
            twoBeepTol = 0.0,
            threeBeepTol = 0.0,
            fourBeepTol = 0.0,
            countsPerBeep = 1
        )
    }

    fun selectDevice(context: Context, name: String) {
        ensureInitialized(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun cloneDevice(context: Context, baseDeviceName: String, newName: String): Boolean {
        synchronized(lock) {
            if (newName.isBlank()) return false
            if (parsedDevices.any { it.name.equals(newName, ignoreCase = true) }) return false
            val base = parsedDevices.firstOrNull { it.name == baseDeviceName } ?: return false
            val newDevice = Device(
                name = newName,
                sensitivity = base.sensitivity,
                fallbackConfig = base.fallbackConfig,
                rateConfigs = emptyMap(),
                isCustom = true
            )
            parsedDevices = parsedDevices + newDevice
            saveCustomDevices(context)
            selectDevice(context, newName)
            return true
        }
    }

    fun renameActiveDevice(context: Context, newName: String): Boolean {
        synchronized(lock) {
            if (newName.isBlank()) return false
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val currentName = currentDeviceName(prefs)
            if (currentName == newName) return true
            if (parsedDevices.any { it.name.equals(newName, ignoreCase = true)  && it.name != currentName}) return false

            val current = parsedDevices.firstOrNull { it.name == currentName } ?: return false
            if (!current.isCustom) return false

            val updated = current.copy(name = newName)
            parsedDevices = parsedDevices.map { if (it.name == currentName) updated else it }
            saveCustomDevices(context)
            selectDevice(context, newName)
            return true
        }
    }

    fun updateActiveDeviceProperty(context: Context, key: String, value: String) {
        synchronized(lock) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val currentName = currentDeviceName(prefs)
            val current = parsedDevices.firstOrNull { it.name == currentName } ?: return
            if (!current.isCustom) return

            val c = current.fallbackConfig
            val updatedConfig = when (key) {
                KEY_FREQ_LOW -> c.copy(freqLow = value.toFloatOrNull()?.takeIf { it > 0f }  ?: c.freqLow)
                KEY_FREQ_MAIN -> c.copy(freqMain = value.toFloatOrNull()?.takeIf { it > 0f }  ?: c.freqMain)
                KEY_FREQ_HIGH -> c.copy(freqHigh = value.toFloatOrNull()?.takeIf { it > 0f }  ?: c.freqHigh)
                KEY_DURATION -> c.copy(duration = value.toDoubleOrNull()?.takeIf { it > 0.0 }  ?: c.duration)
                KEY_DOMINANCE_THRESHOLD -> c.copy(dominanceThreshold = value.toFloatOrNull()?.takeIf { it > 0f }  ?: c.dominanceThreshold)
                KEY_DOMINANCE_THRESHOLD_END -> c.copy(dominanceThresholdEnd = value.toFloatOrNull()?.takeIf { it > 0f }  ?: c.dominanceThresholdEnd)
                KEY_WINDOW_SIZE -> c.copy(windowSize = value.toDoubleOrNull()?.takeIf { it > 0.0 }  ?: c.windowSize)
                KEY_STEP_SIZE -> c.copy(stepSize = value.toDoubleOrNull()?.takeIf { it > 0.0 }  ?: c.stepSize)
                KEY_ONE_BEEP_TOL -> c.copy(oneBeepTol = value.toDoubleOrNull()?.takeIf { it >= 0.0 }  ?: c.oneBeepTol)
                KEY_TWO_BEEP_TOL -> c.copy(twoBeepTol = value.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: c.twoBeepTol)
                KEY_THREE_BEEP_TOL -> c.copy(threeBeepTol = value.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: c.threeBeepTol)
                KEY_FOUR_BEEP_TOL -> c.copy(fourBeepTol = value.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: c.fourBeepTol)
                KEY_COUNTS_PER_BEEP -> c.copy(countsPerBeep = value.toIntOrNull()?.takeIf { it in 1..1000 } ?: c.countsPerBeep)
                else -> c
            }

            val updatedSensitivity = if (key == RadiationCalibration.KEY_SENSITIVITY) {
                value.toDoubleOrNull()?.takeIf { it > 0.0 } ?: current.sensitivity
            } else current.sensitivity

            val updatedDevice = current.copy(sensitivity = updatedSensitivity, fallbackConfig = updatedConfig)
            parsedDevices = parsedDevices.map { if (it.name == currentName) updatedDevice else it }
            saveCustomDevices(context)
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(KEY_DEVICE_NAME, currentName)
                .apply()
        }
    }

    private fun saveCustomDevices(context: Context) {
        val customDevices = parsedDevices.filter { it.isCustom }
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<devices>\n")
        customDevices.forEach { dev ->
            val c = dev.fallbackConfig
            xml.append("\t<device name=\"${dev.name.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")}\">\n")
            xml.append("\t\t<sensitivity>${dev.sensitivity}</sensitivity>\n")
            xml.append("\t\t<detector type=\"Goertzel\">\n")
            xml.append("\t\t\t<par name=\"countsPerBeep\">${c.countsPerBeep}</par>\n")
            xml.append("\t\t\t<par name=\"freqLow\">${c.freqLow}</par>\n")
            xml.append("\t\t\t<par name=\"freqMain\">${c.freqMain}</par>\n")
            xml.append("\t\t\t<par name=\"freqHigh\">${c.freqHigh}</par>\n")
            xml.append("\t\t\t<par name=\"duration\">${c.duration}</par>\n")
            xml.append("\t\t\t<par name=\"dominanceThreshold\">${c.dominanceThreshold}</par>\n")
            xml.append("\t\t\t<par name=\"dominanceThresholdEnd\">${c.dominanceThresholdEnd}</par>\n")
            xml.append("\t\t\t<par name=\"windowSize\">${c.windowSize}</par>\n")
            xml.append("\t\t\t<par name=\"stepSize\">${c.stepSize}</par>\n")
            xml.append("\t\t\t<par name=\"oneBeepTol\">${c.oneBeepTol}</par>\n")
            xml.append("\t\t\t<par name=\"twoBeepTol\">${c.twoBeepTol}</par>\n")
            xml.append("\t\t\t<par name=\"threeBeepTol\">${c.threeBeepTol}</par>\n")
            xml.append("\t\t\t<par name=\"fourBeepTol\">${c.fourBeepTol}</par>\n")
            xml.append("\t\t</detector>\n")
            xml.append("\t</device>\n")
        }
        xml.append("</devices>\n")
        val targetFile = File(context.filesDir, "custom_devices.xml")
        val atomicFile = androidx.core.util.AtomicFile(targetFile)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(xml.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            android.util.Log.e("DeviceConfigManager", "Failed to save custom devices", e)
        }
    }

    fun getPropertyValue(device: Device, key: String): String {
        val c = device.fallbackConfig
        return when (key) {
            RadiationCalibration.KEY_SENSITIVITY -> formatNumber(device.sensitivity)
            KEY_FREQ_LOW -> formatNumber(c.freqLow.toDouble())
            KEY_FREQ_MAIN -> formatNumber(c.freqMain.toDouble())
            KEY_FREQ_HIGH -> formatNumber(c.freqHigh.toDouble())
            KEY_DURATION -> formatNumber(c.duration)
            KEY_DOMINANCE_THRESHOLD -> formatNumber(c.dominanceThreshold.toDouble())
            KEY_DOMINANCE_THRESHOLD_END -> formatNumber(c.dominanceThresholdEnd.toDouble())
            KEY_WINDOW_SIZE -> formatNumber(c.windowSize)
            KEY_STEP_SIZE -> formatNumber(c.stepSize)
            KEY_ONE_BEEP_TOL -> formatNumber(c.oneBeepTol)
            KEY_TWO_BEEP_TOL -> formatNumber(c.twoBeepTol)
            KEY_THREE_BEEP_TOL -> formatNumber(c.threeBeepTol)
            KEY_FOUR_BEEP_TOL -> formatNumber(c.fourBeepTol)
            KEY_COUNTS_PER_BEEP -> formatInt(c.countsPerBeep)
            else -> ""
        }
    }

    fun formatNumber(value: Double): String {
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded % 1.0 == 0.0) "%.1f".format(Locale.US, rounded) else rounded.toString()
    }
    fun formatInt(value: Int): String = value.toString()

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
            prefs.edit { putString(KEY_DEVICE_NAME, parsedDevices.firstOrNull()?.name ?: "Unknown") }
        }
    }

    private fun parseDevicesFromFile(file: File): List<Device> {
        val result = mutableListOf<Device>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        file.inputStream().use { stream ->
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "device") {
                    result.add(parseDevice(parser).copy(isCustom = true))
                }
                event = parser.next()
            }
        }
        return result
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

        val fallbackConfig = parseDeviceConfig(fallbackPars)
        val configs = parameterSets.mapNotNull { pars ->
            val sampleRate = pars["sampleRate"]?.toIntOrNull() ?: return@mapNotNull null
            sampleRate to configFromParameterSet(pars, fallbackConfig, sampleRate)
        }.toMap()
        return Device(name, sensitivity, fallbackConfig, configs)
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

    private fun parseDeviceConfig(pars: Map<String, String>): DeviceConfig {
        return DeviceConfig(
            windowSize = pars["windowSize"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.00427,
            stepSize = pars["stepSize"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.000667,
            freqMain = pars["freqMain"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 3276.8f,
            freqLow = pars["freqLow"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 3076.8f,
            freqHigh = pars["freqHigh"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 3476.8f,
            duration = pars["duration"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.025,
            dominanceThreshold = pars["dominanceThreshold"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 2.0f,
            dominanceThresholdEnd = pars["dominanceThresholdEnd"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.1f,
            oneBeepTol = pars["oneBeepTol"]?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.01,
            twoBeepTol = pars["twoBeepTol"]?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0,
            threeBeepTol = pars["threeBeepTol"]?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0,
            fourBeepTol = pars["fourBeepTol"]?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0,
            countsPerBeep = pars["countsPerBeep"]?.toIntOrNull()?.takeIf { it in 1..1000 } ?: 1
        )
    }

    private fun configFromParameterSet(
        pars: Map<String, String>,
        fallback: DeviceConfig,
        sampleRate: Int
    ): GoertzelDetector.RateConfig {
        val windowSamples = pars["windowSamples"]?.toIntOrNull()?.takeIf { it >= 3 } ?: (fallback.windowSize * sampleRate).roundToInt().coerceAtLeast(3)
        val stepSamples = pars["stepSamples"]?.toIntOrNull()?.takeIf { it >= 1 } ?: (fallback.stepSize * sampleRate).roundToInt().coerceAtLeast(1)

        var freqLow = fallback.freqLow
        var freqMain = fallback.freqMain
        var freqHigh = fallback.freqHigh

        if (windowSamples > 0) {
            val binWidth = sampleRate.toFloat() / windowSamples.toFloat()
            freqLow = pars["binLow"]?.toFloatOrNull()?.let { it * binWidth } ?: fallback.freqLow
            freqMain = pars["binMain"]?.toFloatOrNull()?.let { it * binWidth } ?: fallback.freqMain
            freqHigh = pars["binHigh"]?.toFloatOrNull()?.let { it * binWidth } ?: fallback.freqHigh
        }

        return GoertzelDetector.RateConfig(
            windowSamples = windowSamples,
            stepSamples = stepSamples,
            freqMain = freqMain,
            freqLow = freqLow,
            freqHigh = freqHigh,
            duration = fallback.duration,
            dominanceThreshold = fallback.dominanceThreshold,
            dominanceThresholdEnd = fallback.dominanceThresholdEnd,
            oneBeepTol = fallback.oneBeepTol,
            twoBeepTol = fallback.twoBeepTol,
            threeBeepTol = fallback.threeBeepTol,
            fourBeepTol = fallback.fourBeepTol,
            countsPerBeep = fallback.countsPerBeep
        )
    }
}