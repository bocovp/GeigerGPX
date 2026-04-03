package com.github.bocovp.geigergpx

import kotlin.math.PI
import kotlin.math.cos

class GoertzelDetector(
    private val magThreshold: Float,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    // windowSize, stepSize, freqLow, freqHigh are derived from sampleRate in init
    // via configFor() — they are NOT constructor parameters.
    private val oneBeep: Double = BEEP_DURATION,

    private val dominanceThreshold: Float = DEFAULT_DOMINANCE_THRESHOLD,
    private val dominanceThresholdEnd: Float = DEFAULT_DOMINANCE_THRESHOLD_END
) {

    var onBeep: (Float, Int) -> Unit = { _, _ -> }
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


    private val magThresholdEnd = magThreshold / 2f

    init {
        // The switch block: all rate-dependent parameters are resolved here.
        val cfg    = configFor(sampleRate)
        windowSize = cfg.windowSize
        stepSize   = cfg.stepSize
        freqMain   = cfg.freqMain
        freqLow    = cfg.freqLow
        freqHigh   = cfg.freqHigh

        // Goertzel coefficients depend on freqLow/freqHigh, which are now resolved.
        coeffMain = coeff(freqMain)
        coeffLow  = coeff(cfg.freqLow)
        coeffHigh = coeff(cfg.freqHigh)

        oneBeepMin = oneBeep - cfg.oneBeepTol
        oneBeepMax = oneBeep + cfg.oneBeepTol

        twoBeepMin = oneBeep * 2 - cfg.twoBeepTol
        twoBeepMax = oneBeep * 2 + cfg.twoBeepTol

        threeBeepMin = oneBeep * 3 - cfg.threeBeepTol
        threeBeepMax = oneBeep * 3 + cfg.threeBeepTol

        fourBeepMin = oneBeep * 4 - cfg.fourBeepTol
        fourBeepMax = oneBeep * 4 + cfg.fourBeepTol

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

    private fun processBeep(duration: Double, peakMain: Float) {
        val count = when (duration) {
            in oneBeepMin..oneBeepMax     -> 1
            in twoBeepMin..twoBeepMax     -> 2
            in threeBeepMin..threeBeepMax -> 3
            in fourBeepMin..fourBeepMax   -> 4
            else                          -> 0
        }
       // if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "$count Duration ${"%.4f".format(duration)}\tPeak ${"%.2e".format(peakMain)}")
       // }
        onBeep(peakMain, count)
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

        // -------------------------------------------------------------------------
        // Rate-driven configuration switch.
        // windowSize is chosen so the bin width (sampleRate / windowSize) is ~200 Hz
        // at all three supported rates, keeping the witness bins exactly ±1 bin away
        // from freqMain (bins 15 and 17 at SCO rates; bins 12 and 14 at 44100 Hz).
        // stepSize is ~18 % of windowSize across all rates for consistent temporal
        // resolution of beep edge detection.
        // -------------------------------------------------------------------------
        private data class RateConfig(
            val windowSize:   Int,
            val stepSize:     Int,
            val freqMain:     Float,
            val freqLow:      Float,
            val freqHigh:     Float,
            val oneBeepTol:   Double,
            val twoBeepTol:   Double,
            val threeBeepTol: Double,
            val fourBeepTol:  Double
        )

        private fun configFor(sampleRate: Int): RateConfig =
            when (sampleRate) {
                16000 -> {
                    // Bluetooth  audio
                    val winSize  = 83 // 0.005 s
                    val binWidth = 16000f / winSize // bin width = 205.1 Hz
                    RateConfig(
                        windowSize = winSize,
                        stepSize   = 12, // 0.00075 s
                        freqLow    = 16 * binWidth, //  3084.34
                        freqMain   = 17 * binWidth, //  3277.11
                        freqHigh   = 18 * binWidth, //  3469.88
                        oneBeepTol = 0.005,
                        twoBeepTol = 0.01,
                        threeBeepTol = 0.01,
                        fourBeepTol = 0.01
                    )
                }
                8000 -> {
                    // Bluetooth  audio
                    val winSize  = 61 // 0.0076 s
                    val binWidth = 8000f / winSize
                    RateConfig(
                        windowSize = winSize,
                        stepSize   = 6, // 0.00075 s
                        freqLow    = 23 * binWidth, //  3016.39
                        freqMain   = 25 * binWidth, //  3278.69
                        freqHigh   = 27 * binWidth, //  3540.98
                        oneBeepTol = 0.0075,
                        twoBeepTol = 0.015,
                        threeBeepTol = 0.015,
                        fourBeepTol = 0.015
                    )
                }
                48000 -> {
                    // Bluetooth  audio
                    val winSize  = 205 // 0.0043 s
                    val binWidth = 48000f / winSize
                    RateConfig(
                        windowSize = winSize,
                        stepSize   = 32, // 0.00067s
                        freqLow    = 13 * binWidth, //  3043.90
                        freqMain   = 14 * binWidth, //  3278.05
                        freqHigh   = 15 * binWidth, //  3512.20
                        oneBeepTol = 0.0075,
                        twoBeepTol = 0.015,
                        threeBeepTol = 0.015,
                        fourBeepTol = 0.015
                    )
                }
                else -> {
                    // 44100 Hz (built-in mic) or any unexpected rate.
                    val winSize  = DEFAULT_WINDOW_SIZE
                    val binWidth = sampleRate.toFloat() / winSize  //  in width = 252 Hz
                    RateConfig(
                        windowSize = winSize, // 0.004s
                        stepSize   = DEFAULT_STEP_SIZE, // 0.00073 s
                        freqLow    = 12 * binWidth, //3024
                        freqMain   = 13 * binWidth, //3276
                        freqHigh   = 14 * binWidth, //3528
                        oneBeepTol = 0.01,
                        twoBeepTol = 0.015,
                        threeBeepTol = 0.015,
                        fourBeepTol = 0.015
                    )
                }
            }
    }
}
