package com.github.bocovp.geigergpx

import kotlin.math.PI
import kotlin.math.cos

class GoertzelDetector(
    private val magThreshold: Float,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE
) {


    var onBeep: (Float, Int, Long) -> Unit = { _, _, _ -> }
    var onWindowAnalyzed: ((main: Float, sideEnergy: Float) -> Unit)? = null
    var onCalibrationWindowAnalyzed: ((main: Float, low: Float, high: Float, timestampNs: Long) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Rate-derived configuration — set in init via configFor().
    // windowSize, stepSize, freqLow, freqHigh are declared as lateinit-style
    // vals (Kotlin allows val assignment in init for properties declared without
    // an initializer).
    // -------------------------------------------------------------------------
    private val windowSamples: Int
    private val stepSamples:   Int

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
        windowSamples = cfg.windowSamples
        stepSamples = cfg.stepSamples
        freqMain   = cfg.freqMain
        freqLow    = cfg.freqLow
        freqHigh   = cfg.freqHigh
        dominanceThreshold = cfg.dominanceThreshold
        dominanceThresholdEnd = cfg.dominanceThresholdEnd

        // Goertzel coefficients depend on freqLow/freqHigh, which are now resolved.
        coeffMain = coeff(freqMain)
        coeffLow  = coeff(cfg.freqLow)
        coeffHigh = coeff(cfg.freqHigh)

        oneBeepMin = if (cfg.oneBeepTol > 0.0) cfg.duration - cfg.oneBeepTol else Double.MAX_VALUE
        oneBeepMax = if (cfg.oneBeepTol > 0.0) cfg.duration + cfg.oneBeepTol else Double.NEGATIVE_INFINITY

        twoBeepMin = if (cfg.twoBeepTol > 0.0) cfg.duration * 2 - cfg.twoBeepTol else Double.MAX_VALUE
        twoBeepMax = if (cfg.twoBeepTol > 0.0) cfg.duration * 2 + cfg.twoBeepTol else Double.NEGATIVE_INFINITY

        threeBeepMin = if (cfg.threeBeepTol > 0.0) cfg.duration * 3 - cfg.threeBeepTol else Double.MAX_VALUE
        threeBeepMax = if (cfg.threeBeepTol > 0.0) cfg.duration * 3 + cfg.threeBeepTol else Double.NEGATIVE_INFINITY

        fourBeepMin = if (cfg.fourBeepTol > 0.0) cfg.duration * 4 - cfg.fourBeepTol else Double.MAX_VALUE
        fourBeepMax = if (cfg.fourBeepTol > 0.0) cfg.duration * 4 + cfg.fourBeepTol else Double.NEGATIVE_INFINITY

        hann = FloatArray(windowSamples) {
            (0.5 - 0.5 * cos(2.0 * PI * it / (windowSamples - 1))).toFloat()
        }
        processingBuffer = ShortArray(windowSamples * 4)
    }

    private var leftoverSamples = 0
    private var totalSamplesProcessed: Long = 0

    private var state = State.SILENCE
    private var beepStartSample: Long = 0
    private var currentBeepMaxMain = 0f
    private var buffersProcessed = 0

    private var dropoutWindows = 0
    private val maxDropoutWindows = 2

    fun reset() {
        state = State.SILENCE
        beepStartSample = 0L
        currentBeepMaxMain = 0f
        leftoverSamples = 0
        totalSamplesProcessed = 0L
        dropoutWindows = 0
    }

    fun processSamples(samples: ShortArray, bufferStartNs: Long = 0L) {
        if (samples.isEmpty()) return


        val leftoverAtStart = leftoverSamples
        ensureCapacity(leftoverSamples + samples.size)
        System.arraycopy(samples, 0, processingBuffer, leftoverSamples, samples.size)

        val totalInBuffer = leftoverSamples + samples.size
        var pos = 0

        while (pos + windowSamples <= totalInBuffer) {
            val currentWindowGlobalSample = totalSamplesProcessed + pos
            val energies = computeWindowEnergies(pos)
            val main = energies.main
            val sideEnergy = energies.sideEnergy
            onWindowAnalyzed?.invoke(main, sideEnergy)
            onCalibrationWindowAnalyzed?.invoke(main, energies.low, energies.high, windowTimestampNs(bufferStartNs, pos, leftoverAtStart))

            var detected     = false
            var detectedWeak = false

            if (main > magThresholdEnd) {
                detected     = (main > magThreshold)    && (main > dominanceThreshold    * sideEnergy)
                detectedWeak = (main > magThresholdEnd) && (main > dominanceThresholdEnd * sideEnergy)
            }

            when {
                detected -> {
                    dropoutWindows = 0
                    if (state == State.SILENCE) {
                        state = State.BEEP
                        beepStartSample = currentWindowGlobalSample
                    } else if (state == State.DECAY) {
                        state = State.BEEP
                    }
                    if (main > currentBeepMaxMain) currentBeepMaxMain = main
                }

                detectedWeak -> {
                    dropoutWindows = 0
                    if (state == State.BEEP) {
                        state = State.DECAY
                    }
                }

                state == State.BEEP || state == State.DECAY -> {
                    dropoutWindows++

                    if (dropoutWindows > maxDropoutWindows) {
                        // Signal has been gone too long. Kill the beep.
                        // Subtract the dropout windows from the duration so we don't artificially lengthen it
                        val actualEndSample = currentWindowGlobalSample - (dropoutWindows * stepSamples)
                        val duration = (actualEndSample - beepStartSample).toDouble() / sampleRate
                        val beepEndNs: Long = if (bufferStartNs != 0L) {
                            // Project back to the actual end of the sound
                            val backstep = (dropoutWindows * stepSamples).toLong()
                            bufferStartNs + (pos.toLong() - leftoverAtStart - backstep) * 1_000_000_000L / sampleRate
                        } else {
                            System.nanoTime()
                        }
                        processBeep(duration, currentBeepMaxMain, beepEndNs)
                        state = State.SILENCE
                        currentBeepMaxMain = 0f
                        dropoutWindows = 0
                    }
                }
            }

            pos += stepSamples
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

    private fun computeWindowEnergies(pos: Int): WindowEnergies {
        var q1M = 0f; var q2M = 0f
        var q1L = 0f; var q2L = 0f
        var q1H = 0f; var q2H = 0f

        for (i in 0 until windowSamples) {
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
        val sideEnergy = maxOf(low, high) // (low + high) / 2f
        return WindowEnergies(main, low, high, sideEnergy)
    }

    private fun windowTimestampNs(bufferStartNs: Long, pos: Int, leftoverAtStart: Int): Long {
        return if (bufferStartNs != 0L) {
            bufferStartNs + (pos.toLong() - leftoverAtStart) * 1_000_000_000L / sampleRate
        } else {
            System.nanoTime()
        }
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

    private data class WindowEnergies(val main: Float, val low: Float, val high: Float, val sideEnergy: Float)

    private enum class State { SILENCE, DECAY, BEEP }

    companion object {
        private const val TAG = "GoertzelDetector"

        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_WINDOW_SAMPLES = 205
        const val DEFAULT_STEP_SAMPLES   = 32

        const val  BEEP_DURATION = 0.025
        const val DEFAULT_DOMINANCE_THRESHOLD     = 2.0f
        const val DEFAULT_DOMINANCE_THRESHOLD_END = 1.1f
    }

    data class RateConfig(
        val windowSamples:   Int,
        val stepSamples:     Int,
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
        val countsPerBeep: Int
    )
}
