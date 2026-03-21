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
