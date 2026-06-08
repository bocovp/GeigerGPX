package com.github.bocovp.geigergpx

import kotlin.math.PI
import kotlin.math.cos

class GoertzelDetector(
    private val magThreshold: Float,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE
) {


    var onBeep: (Float, Int, Long) -> Unit = { _, _, _ -> }
    var onWindowAnalyzed: ((main: Float, sideEnergy: Float) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Rate-derived configuration — set in init via configFor().
    // windowSize, stepSize, freqLow, freqHigh are declared as lateinit-style
    // vals (Kotlin allows val assignment in init for properties declared without
    // an initializer).
    // -------------------------------------------------------------------------
    private val windowSize: Int
    private val stepSize:   Int

    private val freqMain:   Float
    private val freqLow:    Float
    private val freqHigh:   Float

    private val coeffMain:  Float
    private val coeffLow:   Float
    private val coeffHigh:  Float
    private val hann:       FloatArray
    private var processingBuffer: ShortArray

    private val oneBeepMin: Double
    private val oneBeepMax: Double
    private val twoBeepMin: Double
    private val twoBeepMax: Double
    private val threeBeepMin: Double
    private val threeBeepMax: Double
    private val fourBeepMin: Double
    private val fourBeepMax: Double
    private val dominanceThreshold: Float
    private val dominanceThresholdEnd: Float


    private val magThresholdEnd = magThreshold / 2f

    init {
        // Rate-dependent parameters are resolved from the currently selected device.
        val cfg    = DeviceConfigManager.rateConfigFor(sampleRate)
        windowSize = cfg.windowSize
        stepSize   = cfg.stepSize
        freqMain   = cfg.freqMain
        freqLow    = cfg.freqLow
        freqHigh   = cfg.freqHigh
        dominanceThreshold = cfg.dominanceThreshold
        dominanceThresholdEnd = cfg.dominanceThresholdEnd

        // Goertzel coefficients depend on freqLow/freqHigh, which are now resolved.
        coeffMain = coeff(freqMain)
        coeffLow  = coeff(cfg.freqLow)
        coeffHigh = coeff(cfg.freqHigh)

        oneBeepMin = cfg.duration - cfg.oneBeepTol
        oneBeepMax = cfg.duration + cfg.oneBeepTol

        twoBeepMin = cfg.duration * 2 - cfg.twoBeepTol
        twoBeepMax = cfg.duration * 2 + cfg.twoBeepTol

        threeBeepMin = cfg.duration * 3 - cfg.threeBeepTol
        threeBeepMax = cfg.duration * 3 + cfg.threeBeepTol

        fourBeepMin = cfg.duration * 4 - cfg.fourBeepTol
        fourBeepMax = cfg.duration * 4 + cfg.fourBeepTol

        hann = FloatArray(windowSize) {
            (0.5 - 0.5 * cos(2.0 * PI * it / (windowSize - 1))).toFloat()
        }
        processingBuffer = ShortArray(windowSize * 4)
    }

    private var leftoverSamples = 0
    private var totalSamplesProcessed: Long = 0

    private var state = State.SILENCE
    private var beepStartSample: Long = 0
    private var currentBeepMaxMain = 0f
    private var buffersProcessed = 0

    fun reset() {
        state = State.SILENCE
        beepStartSample = 0L
        currentBeepMaxMain = 0f
        leftoverSamples = 0
        totalSamplesProcessed = 0L
    }

    fun processSamples(samples: ShortArray, bufferStartNs: Long = 0L) {
        if (samples.isEmpty()) return


        val leftoverAtStart = leftoverSamples
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
                detected     = (main > magThreshold)    && (main > dominanceThreshold    * sideEnergy)
                detectedWeak = (main > magThresholdEnd) && (main > dominanceThresholdEnd * sideEnergy)
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
                    val duration = (currentWindowGlobalSample - beepStartSample).toDouble() / sampleRate
                    val beepEndNs: Long = if (bufferStartNs != 0L) {
                        bufferStartNs + (pos.toLong() - leftoverAtStart) * 1_000_000_000L / sampleRate
                    } else {
                        System.nanoTime()
                    }
                    processBeep(duration, currentBeepMaxMain, beepEndNs)
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

        buffersProcessed++
        //if (BuildConfig.DEBUG && buffersProcessed.mod(4) == 0) {
        //    android.util.Log.d(TAG, "processSamples: bufferSize=${samples.size} totalProcessed=$totalSamplesProcessed")
        //}
    }

    private fun computeWindowEnergies(pos: Int): Pair<Float, Float> {
        var q1M = 0f; var q2M = 0f
        var q1L = 0f; var q2L = 0f
        var q1H = 0f; var q2H = 0f

        for (i in 0 until windowSize) {
            val s = processingBuffer[pos + i].toFloat() * hann[i]

            val q0M = coeffMain * q1M - q2M + s
            q2M = q1M; q1M = q0M

            val q0L = coeffLow  * q1L - q2L + s
            q2L = q1L; q1L = q0L

            val q0H = coeffHigh * q1H - q2H + s
            q2H = q1H; q1H = q0H
        }

        val main      = q1M * q1M + q2M * q2M - q1M * q2M * coeffMain
        val low       = q1L * q1L + q2L * q2L - q1L * q2L * coeffLow
        val high      = q1H * q1H + q2H * q2H - q1H * q2H * coeffHigh
        val sideEnergy = (low + high) / 2f
        return Pair(main, sideEnergy)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= processingBuffer.size) return
        var newSize = processingBuffer.size
        while (newSize < required) newSize *= 2
        val resized = ShortArray(newSize)
        if (leftoverSamples > 0) {
            System.arraycopy(processingBuffer, 0, resized, 0, leftoverSamples)
        }
        processingBuffer = resized
    }

    private fun processBeep(duration: Double, peakMain: Float, beepEndNs: Long) {
        val count = when (duration) {
            in oneBeepMin..oneBeepMax     -> 1
            in twoBeepMin..twoBeepMax     -> 2
            in threeBeepMin..threeBeepMax -> 3
            in fourBeepMin..fourBeepMax   -> 4
            else                          -> 0
        }
       // if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "$count Duration ${"%.4f".format(java.util.Locale.US, duration)}\tPeak ${"%.2e".format(java.util.Locale.US, peakMain)}")
       // }
        onBeep(peakMain, count, beepEndNs)
    }

    private fun coeff(freq: Float): Float {
        val omega = 2.0 * PI * freq / sampleRate
        return 2.0f * cos(omega).toFloat()
    }

    private enum class State { SILENCE, DECAY, BEEP }

    companion object {
        private const val TAG = "GoertzelDetector"

        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_WINDOW_SIZE = 205
        const val DEFAULT_STEP_SIZE   = 32

        const val  BEEP_DURATION = 0.025
        const val DEFAULT_DOMINANCE_THRESHOLD     = 2.0f
        const val DEFAULT_DOMINANCE_THRESHOLD_END = 1.1f
    }

    data class RateConfig(
        val windowSize:   Int,
        val stepSize:     Int,
        val freqMain:     Float,
        val freqLow:      Float,
        val freqHigh:     Float,
        val duration:     Double,
        val dominanceThreshold: Float,
        val dominanceThresholdEnd: Float,
        val oneBeepTol:   Double,
        val twoBeepTol:   Double,
        val threeBeepTol: Double,
        val fourBeepTol:  Double,
        val countsPerBeep: Int // <-- Add this
    )
}
