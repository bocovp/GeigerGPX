package com.github.bocovp.geigergpx

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import kotlin.concurrent.thread

/**
 * Orchestrates the two-stage calibration process.
 *
 * Stage 1 — silence listening (default 5 s):
 *   Runs a GoertzelDetector with threshold = 0 to find the loudest dominant
 *   signal present in the environment. This establishes the threshold for stage 2.
 *
 * Stage 2 — beep counting:
 *   Runs a GoertzelDetector calibrated from the stage 1 result and collects
 *   [totalBeepCount] peak magnitudes. The median is used to compute the final
 *   threshold, which is persisted to SharedPreferences.
 *
 * Lifecycle:
 *   val session = CalibrationSession(context, onProgress, onFinished)
 *   session.start()
 *   // session stops itself once calibration is complete;
 *   // call session.stop() to cancel early.
 */
class CalibrationSession(
    private val context: Context,
    private val onProgress: (phase: Int, current: Int, total: Int) -> Unit,
    private val onFinished: (threshold: Float?) -> Unit,
    private val onAudioStatus: (status: String, errorCode: Int) -> Unit = { _, _ -> },
    private val useBluetoothMicIfAvailable: Boolean = PreferenceManager.getDefaultSharedPreferences(context)
        .getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, true),
    private val thresholdPreferenceKey: String = SettingsFragment.KEY_AUDIO_THRESHOLD,
    private val fallbackThreshold: Float = AudioInputManager.DEFAULT_MAG_THRESHOLD,
    private val stageOneDurationSeconds: Int = 5,
    private val totalBeepCount: Int = 10
) {
    // Resolved in onRecordingStarted once AudioBeepDetector reports the actual rate.
    // Both are written on the worker thread (onRecordingStarted) before any
    // processSamples call, then read on the same worker thread — @Volatile ensures
    // visibility in the unlikely event stop() inspects them from another thread.
    @Volatile private var actualSampleRate: Int = 0
    @Volatile private var stageOneSamplesTotal: Int = 0

    private var stageOneSamplesProcessed = 0
    private var stageOneMaxMain = 0f

    private val peaks = mutableListOf<Float>()

    // Created in onRecordingStarted once actualSampleRate is known.
    // Null until then; processSamples guards against the null case.
    @Volatile private var stageOneDetector: GoertzelDetector? = null

    private var stageTwoDetector: GoertzelDetector? = null

    // The AudioBeepDetector is constructed once and held for the full lifetime of
    // this session. onRawAudio intercepts all samples so CalibrationSession can
    // drive its own GoertzelDetector instances at the correct sample rate.
    // onRecordingStarted fires before the first onRawAudio call, giving us the
    // actual negotiated sample rate (which differs from DEFAULT_SAMPLE_RATE on SCO).
    private val detector = AudioInputManager(
        context = context.applicationContext,
        magThreshold = fallbackThreshold / 1000f,  // irrelevant; onRawAudio bypasses internal detector
        useBluetoothMicIfAvailable = useBluetoothMicIfAvailable,
        onBeep = { _, _ -> },
        onAudioStatus = onAudioStatus,
        onRecordingStarted = { sampleRate -> onRecordingStarted(sampleRate) },
        onRawAudio = { samples -> processSamples(samples) }
    )

    fun start() {
        // onProgress(1, ...) is deferred to onRecordingStarted so we can report
        // the correct total (stageOneDurationSeconds) only after the rate is known.
        detector.start()
    }

    fun stop() {
        detector.stop()
    }

    // Called on the worker thread immediately before the recording loop starts.
    // Initialises all rate-dependent state so processSamples() sees consistent values.
    private fun onRecordingStarted(sampleRate: Int) {
        actualSampleRate      = sampleRate
        stageOneSamplesTotal  = stageOneDurationSeconds * sampleRate

        stageOneDetector = GoertzelDetector(
            magThreshold = 0f,
            sampleRate   = sampleRate
        ).apply {
            onWindowAnalyzed = { main, sideEnergy ->
                if (sideEnergy > 0f
                    && main > GoertzelDetector.DEFAULT_DOMINANCE_THRESHOLD * sideEnergy) {
                    if (main > stageOneMaxMain) stageOneMaxMain = main
                }
            }
        }

        onProgress(1, 0, stageOneDurationSeconds)
    }

    private fun processSamples(samples: ShortArray) {
        // Guard against samples arriving before onRecordingStarted (should never
        // happen given AudioBeepDetector's ordering guarantee, but be safe).
        val s1 = stageOneDetector ?: return
        if (samples.isEmpty() || peaks.size >= totalBeepCount) return

        var offset = 0

        if (stageTwoDetector == null) {
            val remaining = stageOneSamplesTotal - stageOneSamplesProcessed
            val toProcess = minOf(samples.size, remaining)

            if (toProcess > 0) {
                s1.processSamples(samples.copyOfRange(0, toProcess))
                stageOneSamplesProcessed += toProcess
                offset = toProcess
            }

            if (stageOneSamplesProcessed >= stageOneSamplesTotal) {
                transitionToStageTwo()
            }
        }

        if (offset < samples.size) {
            stageTwoDetector?.processSamples(samples.copyOfRange(offset, samples.size))
        }
    }

    private fun transitionToStageTwo() {
        stageOneDetector?.onWindowAnalyzed = null

        val baseThreshold = (stageOneMaxMain / 2.5f).takeIf { it > 0f } ?: fallbackThreshold

        stageTwoDetector = GoertzelDetector(
            magThreshold = baseThreshold,
            sampleRate   = actualSampleRate
        ).apply {
            onBeep = { peakMain, _ ->
                if (peakMain.isFinite() && peaks.size < totalBeepCount) {
                    peaks.add(peakMain)
                    onProgress(2, peaks.size, totalBeepCount)
                    if (peaks.size == totalBeepCount) {
                        finishCalibration()
                    }
                }
            }
        }

        onProgress(2, 0, totalBeepCount)
    }

    private fun finishCalibration() {
        val median         = peaks.sorted()[totalBeepCount / 2]
        val raw            = minOf(median / 2.5f, 1e10f)
        val finalThreshold = if (raw > 0f) raw else fallbackThreshold

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putFloat(thresholdPreferenceKey, finalThreshold)
            .apply()

        // Both onFinished and stop() are dispatched to avoid blocking or interrupting
        // the onRawAudio callback chain that led here.
        // onFinished is posted to the main thread for safe UI access.
        // stop() is run on a background thread because it joins the worker thread
        // with up to a 4000 ms timeout — calling it on the main thread risks an ANR.
        Handler(Looper.getMainLooper()).post {
            onFinished(finalThreshold)
            thread(name = "CalibrationStop") { detector.stop() }
        }
    }
}
