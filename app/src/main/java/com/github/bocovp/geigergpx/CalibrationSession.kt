package com.github.bocovp.geigergpx

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager

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
    private val fallbackThreshold: Float = AudioBeepDetector.DEFAULT_MAG_THRESHOLD,
    private val stageOneDurationSeconds: Int = 5,
    private val totalBeepCount: Int = 10
) {
    private val stageOneSamplesTotal = stageOneDurationSeconds * GoertzelDetector.DEFAULT_SAMPLE_RATE
    private var stageOneSamplesProcessed = 0
    private var stageOneMaxMain = 0f

    private val peaks = mutableListOf<Float>()

    private val stageOneDetector = GoertzelDetector(magThreshold = 0f).apply {
        onWindowAnalyzed = { main, sideEnergy ->
            if (sideEnergy > 0f
                && main > GoertzelDetector.DEFAULT_DOMINANCE_THRESHOLD * sideEnergy) {
                if (main > stageOneMaxMain) stageOneMaxMain = main
            }
        }
    }

    private var stageTwoDetector: GoertzelDetector? = null

    // The detector is constructed once and held for the full lifetime of this
    // session. There is no nullable var — stop() always has a valid reference.
    private val detector = AudioBeepDetector(
        magThreshold = fallbackThreshold / 1000f,
        onBeep = { _, _ -> },
        onRawAudio = { samples -> processSamples(samples) }
    )

    fun start() {
        onProgress(1, 0, stageOneSamplesTotal / GoertzelDetector.DEFAULT_SAMPLE_RATE)
        detector.start()
    }

    fun stop() {
        detector.stop()
    }

    private fun processSamples(samples: ShortArray) {
        if (samples.isEmpty() || peaks.size >= totalBeepCount) return

        var offset = 0

        if (stageTwoDetector == null) {
            val remaining  = stageOneSamplesTotal - stageOneSamplesProcessed
            val toProcess  = minOf(samples.size, remaining)

            if (toProcess > 0) {
                stageOneDetector.processSamples(samples.copyOfRange(0, toProcess))
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
        stageOneDetector.onWindowAnalyzed = null

        val baseThreshold = (stageOneMaxMain / 2.5f).takeIf { it > 0f } ?: fallbackThreshold

        stageTwoDetector = GoertzelDetector(magThreshold = baseThreshold).apply {
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
            .putFloat(SettingsFragment.KEY_AUDIO_THRESHOLD, finalThreshold)
            .apply()

        onFinished(finalThreshold)

        // Defer stop() so it doesn't interrupt the onRawAudio callback chain
        // that led here — and so callers receive onFinished before the detector
        // tears down.
        Handler(Looper.getMainLooper()).post { stop() }
    }
}
