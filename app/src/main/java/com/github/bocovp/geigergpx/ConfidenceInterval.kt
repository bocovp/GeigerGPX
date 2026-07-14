package com.github.bocovp.geigergpx

import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ConfidenceInterval {
    companion object {

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

        private const val Z_95 = 1.95996f // Normal distribution quantile for conf. P = 0.95

        private val g_5 = doubleArrayOf(
            0.083165, 0.085675, 0.084754, 0.081360, 0.076599, 0.070973, 0.065149, 0.059315, 0.053647, 0.048439,
            0.043533, 0.039085, 0.035124, 0.031521, 0.028268, 0.025386, 0.022807, 0.020485, 0.018452, 0.016619,
            0.015022, 0.013576, 0.012265, 0.011112, 0.010072, 0.009163, 0.008326, 0.007592, 0.006930, 0.006329,
            0.005786, 0.005282, 0.004860, 0.004469, 0.004108, 0.003776, 0.003478, 0.003213, 0.002968, 0.002742,
            0.002546, 0.002356, 0.002190, 0.002030, 0.001891, 0.001756, 0.001637, 0.001529, 0.001424, 0.001333,
            0.001243, 0.001167, 0.001086, 0.001020, 0.000961, 0.000901, 0.000846, 0.000794, 0.000746, 0.000706,
            0.000663, 0.000626, 0.000588, 0.000555, 0.000526, 0.000500, 0.000469, 0.000443, 0.000419, 0.000399,
            0.000377, 0.000358, 0.000339, 0.000323, 0.000307, 0.000289, 0.000277, 0.000264, 0.000251, 0.000239,
            0.000228, 0.000217, 0.000206, 0.000197, 0.000187, 0.000180, 0.000172, 0.000163, 0.000157, 0.000150,
            0.000144, 0.000136, 0.000132, 0.000126, 0.000120, 0.000116, 0.000110, 0.000107, 0.000101, 0.000098, 0.000094);

        private val g_10 = doubleArrayOf(
            0.060944, 0.061283, 0.057112, 0.050562, 0.043212, 0.035906, 0.029326, 0.023632, 0.018944, 0.015066,
            0.011960, 0.009467, 0.007520, 0.005939, 0.004727, 0.003755, 0.003002, 0.002394, 0.001917, 0.001542,
            0.001239, 0.001007, 0.000809, 0.000666, 0.000542, 0.000443, 0.000365, 0.000301, 0.000252, 0.000206,
            0.000171, 0.000143, 0.000121, 0.000101, 0.000085, 0.000071, 0.000060, 0.000051, 0.000044, 0.000037,
            0.000032, 0.000027, 0.000024, 0.000020, 0.000018, 0.000015, 0.000013, 0.000011, 0.000010, 0.000008,
            0.000008, 0.000007, 0.000005, 0.000005, 0.000004, 0.000004, 0.000003, 0.000003, 0.000003, 0.000002,
            0.000002, 0.000002, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001);

        private val g_15 = doubleArrayOf(
            0.050324, 0.049479, 0.043566, 0.035585, 0.027599, 0.020620, 0.014991, 0.010740, 0.007597, 0.005358,
            0.003736, 0.002605, 0.001826, 0.001280, 0.000899, 0.000635, 0.000448, 0.000321, 0.000231, 0.000160,
            0.000118, 0.000087, 0.000062, 0.000045, 0.000033, 0.000025, 0.000018, 0.000014, 0.000010, 0.000008, 0.000006);

        private val g_20 = doubleArrayOf(
            0.043875, 0.042126, 0.035019, 0.026409, 0.018605, 0.012507, 0.008123, 0.005169, 0.003231, 0.002008,
            0.001230, 0.000765, 0.000474, 0.000292, 0.000181, 0.000111, 0.000070, 0.000044, 0.000029, 0.000019,
            0.000011, 0.000008, 0.000005, 0.000003, 0.000002, 0.000001, 0.000001, 0.000001, 0.0,      0.0,      0.0);

        private val g_30  = doubleArrayOf(
            0.035956, 0.033115, 0.024600, 0.015821, 0.009203, 0.004978, 0.002587, 0.001294, 0.000625, 0.000303,
            0.000145, 0.000072, 0.000034, 0.000017, 0.000007, 0.000004, 0.000002, 0.000001, 0.000001, 0.0,      0.0);

        private val g_50  = doubleArrayOf(
            0.028025, 0.023613, 0.014031, 0.006541, 0.002588, 0.000914, 0.000298, 0.000097, 0.000028, 0.000009, 0.000003);

        private val g_100 = doubleArrayOf(
            0.019956, 0.013419, 0.004547, 0.000946, 0.000150, 0.000018, 0.000002, 0.0,      0.0,      0.0,      0.0);

        fun getFalseAlarmRate(
            alertDoseRate: Double,
            avgCounts: Int,
            sensitivity: Double,
            bgDoseRate: Double = 0.10
        ): Double {
            if (alertDoseRate.isNaN() || alertDoseRate <= 0.0) return 0.0
            // Have to work in cps here!
            val coercedSensitivity  = sensitivity.coerceAtLeast(0.01);
            if (coercedSensitivity.isNaN()) return 0.0
            val lambda = bgDoseRate * coercedSensitivity  // background cps
            if (lambda.isNaN() || lambda <= 0.0) return 0.0
            val A = alertDoseRate * coercedSensitivity  // Alert cps
            val K = avgCounts
            val T = 3600.0 // 1 hour
            return g(A/lambda, K) * A * T
        }

        /**
         * lambda: event rate
         * A: ratio K/t (where A >= 1.5 * lambda)
         * K: number of consecutive events
         * T: total time range
         */
        private fun g(aOverLambda: Double, K: Int): Double {
            val ind = ((aOverLambda-1)*10).roundToInt().coerceAtLeast(0)
            return when (K) {
                5   -> if (ind <= 100) g_5  [ind] else 0.0
                10  -> if (ind <= 70 ) g_10 [ind] else 0.0
                15  -> if (ind <= 30 ) g_15 [ind] else 0.0
                20  -> if (ind <= 30 ) g_20 [ind] else 0.0
                30  -> if (ind <= 20 ) g_30 [ind] else 0.0
                50  -> if (ind <= 10 ) g_50 [ind] else 0.0
                100 -> if (ind <= 10 ) g_100[ind] else 0.0
                else -> 0.0
            }
        }

        fun relativeErrPercent(counts: Int): Double? {
            return if (counts <= 1) null else 100.0 * Z_95 / sqrt((counts - 1).toDouble())
        }
    }

    val mean: Double
    val delta: Double
    val lowBound: Double
    val highBound: Double
    val sampleCount: Int


    fun chi2(n: Int, z: Float): Float {
        // z is normalQuantile(p)
        // Wilson-Hilferty formula: v * (1 - 2/(9v) + z * sqrt(2/(9v)))^3
        val term1 = 2.0f / (9.0f * n)
        val base = 1.0f - term1 + z * sqrt(term1)
        return n.toFloat() * base * base * base
    }

    fun chi2L(n: Int): Float {
        val index = n.coerceAtLeast(0)
        return if (index <= 11) CHI2_L[index] else chi2(2 * index, -Z_95)
    }

    fun chi2R(n: Int): Float {
        val index = n.coerceAtLeast(0)
        return if (index <= 11) CHI2_R[index] else chi2(2 * index, Z_95)
    }

    constructor(tStart: Double, tEnd: Double, eventsInside: Int, eventAtEnd: Boolean) {
        val duration = (tEnd - tStart).toFloat()
        sampleCount = eventsInside.coerceAtLeast(0)
        if (duration <= 0.0f || eventsInside < 0) {
            mean = 0.0
            delta = 0.0
            lowBound = 0.0
            highBound = 0.0
            return
        }

        val n = eventsInside // Total count of events excluding boundaries
        val add = if (eventAtEnd) 1 else 0

        val invDuration2 = 0.5f / duration

        lowBound = (chi2L((n + add).coerceAtLeast(0)) * invDuration2).toDouble()
        highBound = (chi2R((n + 1).coerceAtLeast(0)) * invDuration2).toDouble()

        val meanF = n.toFloat() / duration
        mean = meanF.toDouble()
        delta = when (n) {
            0 -> highBound
            1 -> (meanF * Z_95).toDouble()
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

    // Old implementation for reference; DO NOT DELETE
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
        return if (sampleCount <= 9) {
            toIntervalText(decimalDigits)
        } else {
            toPlusMinusText(decimalDigits)
        }
    }

    fun toIntervalText(decimalDigits: Int): String {
        if (sampleCount < 2) {
            return "0" // revise?
        }
        return String.format(Locale.US, "%.${decimalDigits}f … %.${decimalDigits}f", lowBound, highBound)
    }

    fun toPlusMinusText(decimalDigits: Int): String {
        if (sampleCount < 2) {
            return "0" // revise?
        }
        return String.format(Locale.US, "%.${decimalDigits}f ± %.${decimalDigits}f", mean, delta)
    }

    fun scale(factor: Double): ConfidenceInterval = ConfidenceInterval(
        mean = mean * factor,
        delta = delta * factor,
        lowBound = lowBound * factor,
        highBound = highBound * factor,
        sampleCount = sampleCount
    )

}
