package com.github.bocovp.geigergpx

import kotlin.math.PI
import kotlin.math.cos

class GoertzelDetector(
    private val magThreshold: Float,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val freqMain: Float = DEFAULT_FREQ_MAIN,
    // windowSize, stepSize, freqLow, freqHigh are derived from sampleRate in init
    // via configFor() — they are NOT constructor parameters.
    private val oneBeepMin: Double = DEFAULT_ONE_BEEP_MIN,
    private val oneBeepMax: Double = DEFAULT_ONE_BEEP_MAX,
    private val twoBeepMin: Double = DEFAULT_TWO_BEEP_MIN,
    private val twoBeepMax: Double = DEFAULT_TWO_BEEP_MAX,
    private val threeBeepMin: Double = DEFAULT_THREE_BEEP_MIN,
    private val threeBeepMax: Double = DEFAULT_THREE_BEEP_MAX,
    private val fourBeepMin: Double = DEFAULT_FOUR_BEEP_MIN,
    private val fourBeepMax: Double = DEFAULT_FOUR_BEEP_MAX,
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
    private val freqLow:    Float
    private val freqHigh:   Float

    private val coeffMain:  Float
    private val coeffLow:   Float
    private val coeffHigh:  Float
    private val hann:       FloatArray
    private var processingBuffer: ShortArray

    private val magThresholdEnd = magThreshold / 2f

    init {
        // The switch block: all rate-dependent parameters are resolved here.
        val cfg    = configFor(sampleRate, freqMain)
        windowSize = cfg.windowSize
        stepSize   = cfg.stepSize
        freqLow    = cfg.freqLow
        freqHigh   = cfg.freqHigh

        // Goertzel coefficients depend on freqLow/freqHigh, which are now resolved.
        coeffMain = coeff(freqMain)
        coeffLow  = coeff(cfg.freqLow)
        coeffHigh = coeff(cfg.freqHigh)

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

        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_FREQ_MAIN   = 3276.0f  // bin 13 at 44100/175

        // SCO center frequency: bin 16 at both 16000/78 and 8000/39.
        // 16 * 16000 / 78 = 16 * 8000 / 39 ≈ 3282.05 Hz
        const val SCO_FREQ_MAIN = 16 * 16000f / 78f

        const val DEFAULT_WINDOW_SIZE = 175
        const val DEFAULT_STEP_SIZE   = 32

        const val DEFAULT_ONE_BEEP_MIN   = 0.025 - 0.005
        const val DEFAULT_ONE_BEEP_MAX   = 0.025 + 0.005
        const val DEFAULT_TWO_BEEP_MIN   = 0.025 * 2.0 - 0.01
        const val DEFAULT_TWO_BEEP_MAX   = 0.025 * 2.0 + 0.01
        const val DEFAULT_THREE_BEEP_MIN = 0.025 * 3.0 - 0.01
        const val DEFAULT_THREE_BEEP_MAX = 0.025 * 3.0 + 0.01
        const val DEFAULT_FOUR_BEEP_MIN  = 0.025 * 4.0 - 0.01
        const val DEFAULT_FOUR_BEEP_MAX  = 0.025 * 4.0 + 0.01

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
            val windowSize: Int,
            val stepSize:   Int,
            val freqLow:    Float,
            val freqHigh:   Float
        )

        private fun configFor(sampleRate: Int, freqMain: Float): RateConfig =
            when (sampleRate) {
                16000 -> {
                    // Window: 78 samples → bin width = 205.1 Hz
                    // Witness bins 15 and 17 are exactly ±205.1 Hz from freqMain.
                    // stepSize 14 ≈ 18 % of 78, giving ~0.875 ms temporal resolution.
                    val binWidth = 16000f / 78
                    RateConfig(
                        windowSize = 78,
                        stepSize   = 14,
                        freqLow    = freqMain - binWidth,
                        freqHigh   = freqMain + binWidth
                    )
                }
                8000 -> {
                    // Window: 39 samples → bin width = 205.1 Hz (identical to 16000/78)
                    // stepSize 7 ≈ 18 % of 39, giving ~0.875 ms temporal resolution.
                    val binWidth = 8000f / 39
                    RateConfig(
                        windowSize = 39,
                        stepSize   = 7,
                        freqLow    = freqMain - binWidth,
                        freqHigh   = freqMain + binWidth
                    )
                }
                else -> {
                    // 44100 Hz (built-in mic) or any unexpected rate.
                    // Window: 175 samples → bin width = 252 Hz
                    // stepSize 32 ≈ 18 % of 175, giving ~0.73 ms temporal resolution.
                    val binWidth = sampleRate.toFloat() / DEFAULT_WINDOW_SIZE
                    RateConfig(
                        windowSize = DEFAULT_WINDOW_SIZE,
                        stepSize   = DEFAULT_STEP_SIZE,
                        freqLow    = freqMain - binWidth,
                        freqHigh   = freqMain + binWidth
                    )
                }
            }
    }
}
