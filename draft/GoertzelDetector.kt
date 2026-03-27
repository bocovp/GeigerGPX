package com.example.geigergpx

import kotlin.math.PI
import kotlin.math.cos

class GoertzelDetector(
    private val magThreshold: Float,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val freqMain: Float = DEFAULT_FREQ_MAIN,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val freqLow: Float = DEFAULT_FREQ_LOW,
    private val freqHigh: Float = DEFAULT_FREQ_HIGH,
    private val stepSize: Int = DEFAULT_STEP_SIZE,
    private val oneBeepMin: Double = DEFAULT_ONE_BEEP_MIN,
    private val oneBeepMax: Double = DEFAULT_ONE_BEEP_MAX,
    private val twoBeepMax: Double = DEFAULT_TWO_BEEP_MAX,
    private val dominanceThreshold: Float = DEFAULT_DOMINANCE_THRESHOLD,
    private val dominanceThresholdEnd: Float = DEFAULT_DOMINANCE_THRESHOLD_END
) {

    var onBeep: (Float, Int) -> Unit = { _, _ -> }
    var onWindowAnalyzed: ((main: Float, sideEnergy: Float) -> Unit)? = null

    private val coeffMain = coeff(freqMain)
    private val coeffLow  = coeff(freqLow)
    private val coeffHigh = coeff(freqHigh)

    private val hann = FloatArray(windowSize) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (windowSize - 1))).toFloat()
    }

    private var processingBuffer = ShortArray(windowSize * 4)
    private var leftoverSamples = 0
    private var totalSamplesProcessed: Long = 0

    private val magThresholdEnd = magThreshold / 2f

    private var state = State.SILENCE
    private var beepStartSample: Long = 0
    private var currentBeepMaxMain = 0f

    fun processSamples(samples: ShortArray) {
        if (samples.isEmpty()) return

        ensureCapacity(leftoverSamples + samples.size)
        System.arraycopy(samples, 0, processingBuffer, leftoverSamples, samples.size)

        val totalInBuffer = leftoverSamples + samples.size
        var pos = 0

        while (pos + windowSize <= totalInBuffer) {
            val currentWindowGlobalSample = totalSamplesProcessed + pos
            val (main, sideEnergy) = computeWindowEnergies(pos)
            onWindowAnalyzed?.invoke(main, sideEnergy)

            var detected     = false
            var detectedWeak = false

            if (main > magThresholdEnd) {
                detected     = (main > magThreshold) && (main > dominanceThreshold * sideEnergy)
                detectedWeak = main > dominanceThresholdEnd * sideEnergy
            }

            when {
                detected -> {
                    if (state == State.SILENCE) {
                        state = State.BEEP
                        beepStartSample = currentWindowGlobalSample
                    } else if (state == State.DECAY) {
                        state = State.BEEP
                    }
                    if (main > currentBeepMaxMain) currentBeepMaxMain = main
                }

                detectedWeak -> {
                    if (state == State.BEEP) {
                        state = State.DECAY
                    }
                }

                state == State.BEEP || state == State.DECAY -> {
                    val duration =
                        (currentWindowGlobalSample - beepStartSample).toDouble() / sampleRate
                    processBeep(duration, currentBeepMaxMain)
                    state = State.SILENCE
                    currentBeepMaxMain = 0f
                }
            }

            pos += stepSize
        }

        leftoverSamples = totalInBuffer - pos
        if (leftoverSamples > 0) {
            System.arraycopy(processingBuffer, pos, processingBuffer, 0, leftoverSamples)
        }

        totalSamplesProcessed += pos
    }

    private fun computeWindowEnergies(pos: Int): Pair<Float, Float> {
        var q1M = 0f; var q2M = 0f
        var q1L = 0f; var q2L = 0f
        var q1H = 0f; var q2H = 0f

        for (i in 0 until windowSize) {
            val s = processingBuffer[pos + i].toFloat() * hann[i]

            val q0M = coeffMain * q1M - q2M + s; q2M = q1M; q1M = q0M
            val q0L = coeffLow  * q1L - q2L + s; q2L = q1L; q1L = q0L
            val q0H = coeffHigh * q1H - q2H + s; q2H = q1H; q1H = q0H
        }

        val main = q1M * q1M + q2M * q2M - q1M * q2M * coeffMain
        if (main <= magThresholdEnd) {
            return Pair(main, 1e10f)
        }

        val low        = q1L * q1L + q2L * q2L - q1L * q2L * coeffLow
        val high       = q1H * q1H + q2H * q2H - q1H * q2H * coeffHigh
        val sideEnergy = (low + high) / 2f
        return Pair(main, sideEnergy)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= processingBuffer.size) return
        var newSize = processingBuffer.size
        while (newSize < required) newSize *= 2
        val resized = ShortArray(newSize)
        System.arraycopy(processingBuffer, 0, resized, 0, leftoverSamples)
        processingBuffer = resized
    }

    // FIX: second arm previously fired onBeep(peakMain, 1) — same as the first arm,
    // making twoBeepMax meaningless. Now correctly reports count = 2.
    // If your detector physically cannot distinguish two pulses and always produces
    // a single long burst, remove the second arm and the twoBeepMax parameter entirely.
    private fun processBeep(duration: Double, peakMain: Float) {
        when {
            duration in oneBeepMin..oneBeepMax                  -> onBeep(peakMain, 1)
            duration > oneBeepMax && duration <= twoBeepMax     -> onBeep(peakMain, 2)
            else                                                 -> onBeep(peakMain, 0)
        }
    }

    private fun coeff(freq: Float): Float {
        val omega = 2.0 * PI * freq / sampleRate
        return 2.0f * cos(omega).toFloat()
    }

    private enum class State { SILENCE, DECAY, BEEP }

    companion object {
        const val DEFAULT_SAMPLE_RATE          = 44100
        const val DEFAULT_FREQ_MAIN            = 3276.0f
        const val DEFAULT_WINDOW_SIZE          = 175
        const val DEFAULT_FREQ_LOW             = DEFAULT_FREQ_MAIN - 252f
        const val DEFAULT_FREQ_HIGH            = DEFAULT_FREQ_MAIN + 252f
        const val DEFAULT_STEP_SIZE            = 32
        const val DEFAULT_ONE_BEEP_MIN         = 0.020
        const val DEFAULT_ONE_BEEP_MAX         = 0.035
        const val DEFAULT_TWO_BEEP_MAX         = 0.070
        const val DEFAULT_DOMINANCE_THRESHOLD     = 2.0f
        const val DEFAULT_DOMINANCE_THRESHOLD_END = 1.5f
    }
}
