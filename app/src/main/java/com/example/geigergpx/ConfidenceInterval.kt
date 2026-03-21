package com.example.geigergpx

import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

class ConfidenceInterval {
    val mean: Double
    val delta: Double
    val lowBound: Double
    val highBound: Double
    val sampleCount: Int

    constructor(mean: Double, delta: Double, lowBound: Double, highBound: Double, sampleCount: Int) {
        this.mean = mean
        this.delta = delta
        this.lowBound = lowBound
        this.highBound = highBound
        this.sampleCount = sampleCount
    }

    constructor(t1: Double, tn: Double, n: Int) {
        sampleCount = n

        if (n <= 1 || tn <= t1) {
            mean = 0.0
            delta = 0.0
            lowBound = 0.0
            highBound = 0.0
            return
        }

        val deltaTime = tn - t1
        val norm = (if (n < 10) n - 2 else n - 1).toDouble() // Using unbiased estimator for low number of points

        if (norm <= 0.0 || deltaTime <= 0.0) {
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
        if (mean == 0.0 && delta == 0.0) {
            return String.format(Locale.US, "%.${decimalDigits}f", 0.0)
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

    companion object {
        /**
         * Stored GPX/POI samples carry only the number of counts and the total elapsed seconds.
         * Reconstruct the same interval model used by the live UI by treating those values as the
         * full measurement window, which corresponds to n = counts + 1 interval boundaries.
         */
        fun fromCountsAndSeconds(counts: Int, seconds: Double): ConfidenceInterval {
            if (counts <= 0 || seconds <= 0.0) {
                return ConfidenceInterval(
                    mean = 0.0,
                    delta = 0.0,
                    lowBound = 0.0,
                    highBound = 0.0,
                    sampleCount = counts + 1
                )
            }
            return ConfidenceInterval(0.0, seconds, counts + 1)
        }

        fun doseRateFromCountsAndSeconds(counts: Int, seconds: Double, coeff: Double): ConfidenceInterval {
            return fromCountsAndSeconds(counts, seconds).scale(coeff)
        }
    }
}
