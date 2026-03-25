package com.example.geigergpx

import java.util.Locale
import kotlin.div
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.toDouble
import kotlin.times

class ConfidenceInterval {
    val mean: Float
    val delta: Float
    val lowBound: Float
    val highBound: Float
    val sampleCount: Int

    // Table[Quantile[ChiSquareDistribution[2 n], alpha/2], {n, 0,11}] /. alpha->1-0.95
    private val CHI2_L = floatArrayOf(
        0.0f, 0.0506356f, 0.484419f, 1.23734f, 2.17973f, 3.24697f,
        4.40379f, 5.62873f, 6.90766f, 8.23075f, 9.59078f, 10.9823f
    )

    // Table[Quantile[ChiSquareDistribution[2 n], 1-alpha/2], {n, 0,11}] /. alpha->1-0.95
    private val CHI2_R = floatArrayOf(
        0.0f, 7.37776f, 11.1433f, 14.4494f, 17.5345f, 20.4832f,
        23.3367f, 26.1189f, 28.8454f, 31.5264f, 34.1696f, 36.7807f
    )

    fun chi2(n: Int, z: Float): Float {
        // val z = normalQuantile(p)
        // Wilson-Hilferty formula: v * (1 - 2/(9v) + z * sqrt(2/(9v)))^3
        val term1 = 2.0f / (9.0f * n)
        val base = 1.0f - term1 + z * sqrt(term1)
        return n.toFloat() * base.pow(3)
    }

    fun chi2L(n: Int): Float {
        return if (n <= 11) CHI2_L[n] else chi2(2 * n, -1.95996f)
    }

    fun chi2R(n: Int): Float {
        return if (n <= 11) CHI2_R[n] else chi2(2*n, 1.95996f)
    }

    constructor(tStart: Double, tEnd: Double, eventsInside: Int, eventAtEnd:Boolean){
        val duration = (tEnd - tStart).toFloat()
        require(duration > 0) { "Duration must be positive" }
        val n = eventsInside         // Total count of events excluding boundaries
        val add = if (eventAtEnd) 1 else 0

        if (n <= 12) {
            lowBound = chi2L(n + add) / (2.0f * duration)
            highBound = chi2R(n + 1) / (2.0f * duration)
        } else {
            lowBound = 0.0f // never used
            highBound = 0.0f // never used
        }

        mean = n / duration
        val z = 1.95996f // Normal distribution quantile for conf. P = 0.95
        if (n == 0) {
            delta = (highBound - lowBound) / 2
        } else if (n == 1) {
            val root = sqrt(n.toFloat())
            delta = mean * z / root
        } else {
            val root = sqrt((n - add).toFloat())
            delta = mean * z / root // This is simply CI for normal distribution
        }
//        val gamma = mean * (z * z - 1.0) / (3 * (n - 1)).toDouble() // This follows from Cornish–Fisher expansion for Chi^2 distribution
//        lowBound = max(0.0, mean - delta + gamma)
//        highBound = mean + delta + gamma
    }


    constructor(mean: Double, delta: Double, lowBound: Double, highBound: Double, sampleCount: Int) {
        this.mean = mean
        this.delta = delta
        this.lowBound = lowBound
        this.highBound = highBound
        this.sampleCount = sampleCount
    }



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

    fun toText(decimalDigits: Int): String {
        if (sampleCount < 2) {
            return "0"
        }

        return if (sampleCount <= 9) {
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
