package com.github.bocovp.geigergpx

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlin.math.log10
import kotlin.math.pow

class CalibrationActivity : AppCompatActivity() {
    private var audioInput: AudioInputManager? = null
    private var calibrationSession: CalibrationSession? = null
    private lateinit var plot: CalibrationPlotView
    private lateinit var thresholdInput: EditText
    private lateinit var status: TextView
    private var bluetooth = false
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetooth = intent.getBooleanExtra(EXTRA_BLUETOOTH, false)
        buildUi()
        loadThreshold()
        startPlotting()
    }

    override fun onDestroy() {
        audioInput?.stop()
        calibrationSession?.stop()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = if (bluetooth) "Bluetooth calibration" else "Calibration"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply { setPadding(24, 8, 24, 8); text = "Starting audio..." }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        plot = CalibrationPlotView(this)
        root.addView(plot, LinearLayout.LayoutParams(-1, 0, 1f))
        thresholdInput = EditText(this).apply {
            hint = "Threshold (dB)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> saveThresholdFromInput(); false }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveThresholdFromInput() }
        }
        root.addView(thresholdInput, LinearLayout.LayoutParams(-1, -2))
        val auto = MaterialButton(this).apply {
            text = "Autocalibrate"
            setOnClickListener { runAutocalibration() }
        }
        root.addView(auto, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        plot.onThresholdSelected = { threshold, isFinished -> saveThreshold(threshold, updateInput = true, persist = isFinished) }
    }

    private fun startPlotting() {
        audioInput = AudioInputManager(
            context = applicationContext,
            magThreshold = currentThreshold(),
            bluetoothMagThreshold = currentThreshold(),
            useBluetoothMicIfAvailable = bluetooth,
            onBeep = { _, _, _ -> },
            onAudioStatus = { text, _ -> runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = text
                }
            } },
            onRecordingStarted = { sampleRate ->
                val detector = GoertzelDetector(currentThreshold(), sampleRate).apply {
                    onCalibrationWindowAnalyzed = { main, low, high, timestampNs ->
                        plot.addSample(main, low, high, timestampNs)
                    }
                }
                audioInputDetector = detector
            },
            onRawAudio = { samples, bufferStartNs -> audioInputDetector?.processSamples(samples, bufferStartNs) }
        ).also { it.start() }
    }

    @Volatile private var audioInputDetector: GoertzelDetector? = null

    private fun runAutocalibration() {
        audioInput?.stop()
        audioInput = null
        status.text = "Estimating signal level..."
        calibrationSession = CalibrationSession(
            context = this,
            onProgress = { phase, current, total -> runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = if (phase == 2) "Calibrating... $current/$total" else "Estimating signal level..."
                }
            } },
            onFinished = { threshold ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        calibrationSession = null
                        threshold?.let { saveThreshold(it, updateInput = true) }
                        Toast.makeText(this, "Calibration finished.", Toast.LENGTH_SHORT).show()
                        startPlotting()
                    }
                }
            },
            onAudioStatus = { text, _ -> runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = text
                }
            } },
            useBluetoothMicIfAvailable = bluetooth,
            thresholdPreferenceKey = thresholdKey(),
            fallbackThreshold = defaultThreshold()
        ).also { it.start() }
    }

    private fun loadThreshold() = saveThreshold(currentThreshold(), updateInput = true, persist = false)

    private fun saveThresholdFromInput() {
        val db = thresholdInput.text.toString().trim().toFloatOrNull() ?: return
        if (db.isFinite()) saveThreshold(fromDb(db), updateInput = false)
    }

    private fun saveThreshold(threshold: Float, updateInput: Boolean, persist: Boolean = true) {
        if (threshold <= 0f || !threshold.isFinite()) return
        if (persist) prefs.edit { putFloat(thresholdKey(), threshold) }
        plot.setThresholdMagnitude(threshold)
        if (updateInput) thresholdInput.setText("%.2f".format(java.util.Locale.US, toDb(threshold)))
    }

    private fun currentThreshold(): Float {
        val stored = prefs.getFloat(thresholdKey(), Float.NaN)
        return if (stored.isNaN()) defaultThreshold() else stored
    }

    private fun thresholdKey() = if (bluetooth) SettingsKeys.KEY_BLUETOOTH_AUDIO_THRESHOLD else SettingsKeys.KEY_AUDIO_THRESHOLD
    private fun defaultThreshold() = if (bluetooth) AudioInputManager.DEFAULT_BLUETOOTH_MAG_THRESHOLD else AudioInputManager.DEFAULT_MAG_THRESHOLD
    private fun toDb(intensity: Float) = (10.0 * log10(intensity.toDouble() / 100.0)).toFloat()
    private fun fromDb(value: Float) = (10.0.pow(value / 10.0) * 100.0).toFloat()

    companion object { const val EXTRA_BLUETOOTH = "bluetooth" }
}
