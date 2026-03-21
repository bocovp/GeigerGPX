package com.example.geigergpx

import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

data class ConfidenceInterval(
    val mean: Double,
    val delta: Double,
    val lowBound: Double,
    val highBound: Double
)

object DoseStatistics {
    fun confidenceIntervalFromTimestamps(t1: Double, tn: Double, n: Int): ConfidenceInterval {
        if (n <= 1 || tn <= t1) {
            return ConfidenceInterval(mean = 0.0, delta = 0.0, lowBound = 0.0, highBound = 0.0)
        }
        val deltaTime = tn - t1
        val norm = (if (n < 10) n - 2 else n - 1).toDouble() // Using unbiased estimator for low number of points

        if (norm <= 0.0 || deltaTime <= 0.0) {
            return ConfidenceInterval(mean = 0.0, delta = 0.0, lowBound = 0.0, highBound = 0.0)
        }

        val mean = norm / deltaTime
        val z = 1.95996 // Normal distribution quantile for conf. P = 0.95
        val root = sqrt((n - 1).toDouble())
        val delta = mean * z / root // This is simply CI for normal distribution
        val gamma = mean * (z * z - 1.0) / (3 * (n - 1)).toDouble() // This follows from Cornish–Fisher expansion for Chi^2 distribution

        return ConfidenceInterval(
            mean = mean,
            delta = delta,
            lowBound = max(0.0, mean - delta + gamma),
            highBound = mean + delta + gamma
        )
    }

    /**
     * Stored GPX/POI samples carry only the number of counts and the total elapsed seconds.
     * Reconstruct the same interval model used by the live UI by treating those values as the
     * full measurement window, which corresponds to n = counts + 1 interval boundaries.
     */
    fun confidenceIntervalFromCountsAndSeconds(counts: Int, seconds: Double): ConfidenceInterval {
        if (counts <= 0 || seconds <= 0.0) {
            return ConfidenceInterval(mean = 0.0, delta = 0.0, lowBound = 0.0, highBound = 0.0)
        }
        return confidenceIntervalFromTimestamps(0.0, seconds, counts + 1)
    }

    fun doseRateIntervalFromCountsAndSeconds(counts: Int, seconds: Double, coeff: Double): ConfidenceInterval {
        val cpsInterval = confidenceIntervalFromCountsAndSeconds(counts, seconds)
        return ConfidenceInterval(
            mean = cpsInterval.mean * coeff,
            delta = cpsInterval.delta * coeff,
            lowBound = cpsInterval.lowBound * coeff,
            highBound = cpsInterval.highBound * coeff
        )
    }

    fun formatDoseRateText(ci: ConfidenceInterval, sampleCount: Int, decimalDigits: Int): String {
        if (ci.mean == 0.0 && ci.delta == 0.0) {
            return String.format(Locale.US, "%.${decimalDigits}f", 0.0)
        }

        return if (sampleCount <= 9) {
            String.format(Locale.US, "%.${decimalDigits}f … %.${decimalDigits}f", ci.lowBound, ci.highBound)
        } else {
            String.format(Locale.US, "%.${decimalDigits}f ± %.${decimalDigits}f", ci.mean, ci.delta)
        }
    }
}
