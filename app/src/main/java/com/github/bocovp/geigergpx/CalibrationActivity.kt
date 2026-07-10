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

    private lateinit var autoButton: MaterialButton
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
        audioInputDetector = null
        val input = audioInput
        val session = calibrationSession
        audioInput = null
        calibrationSession = null
        kotlin.concurrent.thread(name = "CalibrationCleanup") {
            input?.stop()
            session?.stop()
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = if (bluetooth) "Bluetooth calibration" else "Calibration"
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(androidx.appcompat.R.attr.homeAsUpIndicator, typedValue, true)
            setNavigationIcon(typedValue.resourceId)

            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        val density = resources.displayMetrics.density
        status = TextView(this).apply {
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
            text = "Starting audio..."
        }
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
        val inputParams = LinearLayout.LayoutParams(-1, -2).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setMargins(margin, 0, margin, 0)
        }
        root.addView(thresholdInput, inputParams)
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }

        autoButton = MaterialButton(this).apply {
            text = "Autocalibrate"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8 }
            setOnClickListener {
                if (calibrationSession == null) runAutocalibration() else cancelAutocalibration()
            }
        }
        buttonContainer.addView(autoButton)

        val doneButton = MaterialButton(this).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 }
            setOnClickListener { finish() }
        }
        buttonContainer.addView(doneButton)

        root.addView(buttonContainer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        plot.onThresholdSelected = { threshold, isFinished -> saveThreshold(threshold, updateInput = true, persist = isFinished) }
    }

    private fun startPlotting() {
        plot.clear()
        audioInput = AudioInputManager(
            context = applicationContext,
            magThreshold = currentThreshold(),
            bluetoothMagThreshold = currentThreshold(),
            useBluetoothMicIfAvailable = bluetooth,
            onBeep = { _, count, timeNs -> if (count > 0) plot.addBeep(timeNs) },
            onAudioStatus = { text, _ -> runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = text
                }
            } },
            onRecordingStarted = { sampleRate ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        val detector = GoertzelDetector(currentThreshold(), sampleRate).apply {
                            onCalibrationBatchAnalyzed = { mains, lows, highs, timesNs, count ->
                                plot.addSamples(mains, lows, highs, timesNs, count)
                            }
                            onBeep = { _, count, timeNs -> if (count > 0) plot.addBeep(timeNs) }
                        }
                        audioInputDetector = detector
                    }
                }
            },
            onRawAudio = { samples, bufferStartNs -> audioInputDetector?.processSamples(samples, bufferStartNs) }
        ).also { it.start() }
    }

    @Volatile private var audioInputDetector: GoertzelDetector? = null

    private fun runAutocalibration() {
        autoButton.isEnabled = false
        autoButton.text = "Starting..."
        thresholdInput.isEnabled = false
        plot.isEnabled = false
        plot.clear()

        val input = audioInput
        audioInput = null
        status.text = "Stopping audio..."

        kotlin.concurrent.thread(name = "StopAudioAndCalibrate") {
            input?.stop()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                autoButton.isEnabled = true
                autoButton.text = "Cancel"
                status.text = "Estimating signal level..."
                calibrationSession = CalibrationSession(
                    context = this,
                    onProgress = { phase, current, total ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                status.text = if (phase == 2) "Calibrating... $current/$total" else "Estimating signal level..."
                            }
                        }
                    },
                    onFinished = { threshold ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                calibrationSession = null
                                autoButton.text = "Autocalibrate"
                                thresholdInput.isEnabled = true
                                plot.isEnabled = true
                                threshold?.let { saveThreshold(it, updateInput = true) }
                                Toast.makeText(this, "Calibration finished.", Toast.LENGTH_SHORT).show()
                                startPlotting()
                            }
                        }
                    },
                    onAudioStatus = { text, _ ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                status.text = text
                            }
                        }
                    },
                    onBatchAnalyzed = { mains, lows, highs, timesNs, count ->
                        if (!isDestroyed && !isFinishing) {
                            plot.addSamples(mains, lows, highs, timesNs, count)
                        }
                    },
                    onBeep = { timeNs ->
                        if (!isDestroyed && !isFinishing) {
                            plot.addBeep(timeNs)
                        }
                    },
                    useBluetoothMicIfAvailable = bluetooth,
                    thresholdPreferenceKey = thresholdKey(),
                    fallbackThreshold = defaultThreshold()
                ).also { it.start() }
            }
        }
    }

    private fun cancelAutocalibration() {
        autoButton.isEnabled = false
        autoButton.text = "Stopping..."
        val session = calibrationSession
        calibrationSession = null
        status.text = "Cancelling calibration..."
        kotlin.concurrent.thread(name = "CancelCalibration") {
            session?.stop()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    autoButton.isEnabled = true
                    autoButton.text = "Autocalibrate"
                    thresholdInput.isEnabled = true
                    plot.isEnabled = true
                    status.text = "Calibration cancelled."
                    startPlotting()
                }
            }
        }
    }

    private fun loadThreshold() = saveThreshold(currentThreshold(), updateInput = true, persist = false)

    private fun saveThresholdFromInput() {
        val db = thresholdInput.text.toString().trim().toFloatOrNull() ?: return
        if (db != null && db.isFinite() && db >= 0f && db <= 140f) {
            saveThreshold(fromDb(db), updateInput = false)
        } else {
            loadThreshold()
        }
    }

    private fun saveThreshold(threshold: Float, updateInput: Boolean, persist: Boolean = true) {
        if (threshold <= 0f || !threshold.isFinite()) return
        if (persist) prefs.edit { putFloat(thresholdKey(), threshold) }
        plot.setThresholdMagnitude(threshold)
        audioInputDetector?.setThreshold(threshold)
        if (updateInput) {
            val formatted = "%.2f".format(java.util.Locale.US, toDb(threshold))
            if (thresholdInput.text.toString() != formatted) {
                thresholdInput.setText(formatted)
            }
        }
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
