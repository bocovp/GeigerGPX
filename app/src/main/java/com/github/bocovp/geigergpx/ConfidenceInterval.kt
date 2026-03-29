package com.github.bocovp.geigergpx

import java.util.Locale
import kotlin.div
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.toDouble
import kotlin.times

class ConfidenceInterval {
    companion object {
        @JvmStatic
        fun getFalseAlarmRate(
            alertDoseRate: Double,
            avgTimestamps: Int,
            cpsToUsvhCoefficient: Double
        ): Double {
            return 0.0
        }
    }

    enum class DisplayMode {
        AUTO,
        INTERVAL,
        PLUS_MINUS
    }

    val mean: Double
    val delta: Double
    val lowBound: Double
    val highBound: Double
    val sampleCount: Int

    // Table[Quantile[ChiSquareDistribution[2 n], alpha/2], {n, 0,11}] /. alpha->1-0.95
    private val CHI2_L = floatArrayOf(
        0.0f    , 0.0506356f, 0.484419f, 1.23734f, 2.17973f,  3.24697f,
        4.40379f, 5.62873f  , 6.90766f , 8.23075f, 9.59078f, 10.9823f
    )

    // Table[Quantile[ChiSquareDistribution[2 n], 1-alpha/2], {n, 0,11}] /. alpha->1-0.95
    private val CHI2_R = floatArrayOf(
        0.0f    , 7.37776f, 11.1433f, 14.4494f, 17.5345f, 20.4832f,
        23.3367f, 26.1189f, 28.8454f, 31.5264f, 34.1696f, 36.7807f
    )

    private val Z_95 = 1.95996f // Normal distribution quantile for conf. P = 0.95

    fun chi2(n: Int, z: Float): Float {
        // z is normalQuantile(p)
        // Wilson-Hilferty formula: v * (1 - 2/(9v) + z * sqrt(2/(9v)))^3
        val term1 = 2.0f / (9.0f * n)
        val base = 1.0f - term1 + z * sqrt(term1)
        return n.toFloat() * base * base * base
    }

    fun chi2L(n: Int): Float {
        return if (n <= 11) CHI2_L[n] else chi2(2*n, -Z_95)
    }

    fun chi2R(n: Int): Float {
        return if (n <= 11) CHI2_R[n] else chi2(2*n, Z_95)
    }

    constructor(tStart: Double, tEnd: Double, eventsInside: Int, eventAtEnd:Boolean){
        val duration = (tEnd - tStart).toFloat()
        sampleCount = eventsInside
        val n = eventsInside         // Total count of events excluding boundaries
        val add = if (eventAtEnd) 1 else 0

        val invDuration2 = 0.5f / duration

        lowBound = (chi2L(n + add) * invDuration2).toDouble()
        highBound = (chi2R(n + 1) * invDuration2).toDouble()

        val meanF = n.toFloat() / duration
        mean = meanF.toDouble()
        delta = when (n) {
            0 -> highBound
            1 ->  (meanF * Z_95).toDouble()
            else -> {
                val root = sqrt((n - add).toFloat())
                (meanF * Z_95 / root).toDouble() // This is simply CI for normal distribution
            }
        }
    }

    constructor(mean: Double, delta: Double, lowBound: Double, highBound: Double, sampleCount: Int) {
        this.mean = mean
        this.delta = delta
        this.lowBound = lowBound
        this.highBound = highBound
        this.sampleCount = sampleCount
    }

    // Old implementation for reference
    constructor(t1: Double, tn: Double, n: Int) {
        // n is number of detected events here so n >= 2
        sampleCount = n
        val deltaTime = tn - t1
        val norm = (if (n > 2 && n < 10) n - 2 else n - 1).toDouble() // Using unbiased estimator for low number of points

        if (n < 2 || norm <= 0.0 || deltaTime <= 0.0) {
            mean = 0.0
            delta = 0.0
            lowBound = 0.0
            highBound = 0.0
            return
        }

        mean = norm / deltaTime
        val z = 1.95996 // Normal distribution quantile for conf. P = 0.95
        val root = sqrt((n - 1).toDouble())
        delta = mean * z / root // This is simply CI for normal distribution
        val gamma = mean * (z * z - 1.0) / (3 * (n - 1)).toDouble() // This follows from Cornish–Fisher expansion for Chi^2 distribution
        lowBound = max(0.0, mean - delta + gamma)
        highBound = mean + delta + gamma
    }

    fun toText(decimalDigits: Int, displayMode: DisplayMode = DisplayMode.AUTO): String {
        if (sampleCount < 2) {
            return "0" // revise?
        }

        val useInterval = when (displayMode) {
            DisplayMode.AUTO -> sampleCount <= 9
            DisplayMode.INTERVAL -> true
            DisplayMode.PLUS_MINUS -> false
        }

        return if (useInterval) {
            String.format(Locale.US, "%.${decimalDigits}f … %.${decimalDigits}f", lowBound, highBound)
        } else {
            String.format(Locale.US, "%.${decimalDigits}f ± %.${decimalDigits}f", mean, delta)
        }
    }

    fun scale(coef: Double): ConfidenceInterval = ConfidenceInterval(
        mean = mean * coef,
        delta = delta * coef,
        lowBound = lowBound * coef,
        highBound = highBound * coef,
        sampleCount = sampleCount
    )

}
