package com.github.bocovp.geigergpx

import java.util.Locale
import kotlin.div
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.toDouble
import kotlin.times

class ConfidenceInterval {
    companion object {

        private val g_5 = doubleArrayOf(
            0.082994, 0.085757, 0.084824, 0.081208, 0.076622, 0.071227, 0.065179, 0.059155, 0.053707, 0.048457,
            0.043629, 0.039236, 0.035053, 0.031524, 0.028300, 0.025409, 0.022878, 0.020471, 0.018504, 0.016591,
            0.014994, 0.013521, 0.012285, 0.011061, 0.010099, 0.009126, 0.008315, 0.007563, 0.006930, 0.006288,
            0.005737, 0.005307, 0.004857, 0.004429, 0.004100, 0.003801, 0.003507, 0.003198, 0.002952, 0.002760,
            0.002529, 0.002362, 0.002171, 0.002037, 0.001873, 0.001751, 0.001647, 0.001523, 0.001434, 0.001335,
            0.001236, 0.001158, 0.001084, 0.001011, 0.000949, 0.000903, 0.000849, 0.000790, 0.000738, 0.000706,
            0.000662, 0.000620, 0.000595, 0.000557, 0.000522, 0.000493, 0.000470, 0.000437, 0.000414, 0.000400,
            0.000381, 0.000357, 0.000337, 0.000324, 0.000301, 0.000294, 0.000276, 0.000266, 0.000248, 0.000243,
            0.000226, 0.000222, 0.000204, 0.000201, 0.000194, 0.000179, 0.000170, 0.000162, 0.000160, 0.000150,
            0.000141, 0.000135, 0.000127, 0.000126, 0.000121, 0.000111, 0.000110, 0.000109, 0.000103, 0.000097, 0.000093);

        private val g_10 = doubleArrayOf(
            0.060881, 0.061005, 0.056953, 0.050471, 0.043253, 0.035841, 0.029420, 0.023559, 0.019052, 0.015108,
            0.011937, 0.009433, 0.007484, 0.005965, 0.004719, 0.003767, 0.003000, 0.002370, 0.001918, 0.001538,
            0.001236, 0.000994, 0.000824, 0.000666, 0.000551, 0.000452, 0.000364, 0.000303, 0.000247, 0.000210,
            0.000173, 0.000146, 0.000116, 0.000100, 0.000084, 0.000071, 0.000060, 0.000050, 0.000043, 0.000038,
            0.000032, 0.000027, 0.000023, 0.000019, 0.000018, 0.000013, 0.000012, 0.000012, 0.000011, 0.000009,
            0.000008, 0.000007, 0.000005, 0.000005, 0.000004, 0.000004, 0.000003, 0.000003, 0.000003, 0.000002,
            0.000002, 0.000002, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001, 0.000001,      0.0);

        private val g_20 = doubleArrayOf(
            0.043929, 0.042027, 0.035140, 0.026362, 0.018526, 0.012570, 0.008084, 0.005246, 0.003239, 0.002026,
            0.001241, 0.000751, 0.000466, 0.000290, 0.000188, 0.000108, 0.000072, 0.000045, 0.000030, 0.000018,
            0.000011, 0.000008, 0.000005, 0.000002, 0.000002, 0.000002, 0.000001, 0.000001, 0.0,      0.0,      0.0);

        private val g_50 = doubleArrayOf(0.027911, 0.023777, 0.013918, 0.006484, 0.002607, 0.000928, 0.000290, 0.000107, 0.000024, 0.00001, 0.000002);

        private val g_100 = doubleArrayOf(0.019884, 0.013535, 0.004549, 0.000987, 0.000148, 0.000017, 0.000001, 0.0,      0.0,      0.0,     0.0);

        fun getFalseAlarmRate(
            alertDoseRate: Double,
            avgTimestamps: Int,
            cps2uSvhCoefficient: Double
        ): Double {
            // Have to work in cps here!
            val lambda = 1.0 // background cps
            val A = alertDoseRate / cps2uSvhCoefficient // Alert cps
            val K = avgTimestamps
            val T = 3600.0 // 1 hour
            return g(A/lambda, K) * A * T
        }

        /**
         * lambda: event rate
         * A: ratio K/t (where A >= 1.5 * lambda)
         * K: number of consecutive events
         * T: total time range
         */
        fun g(aOverLambda: Double, K: Int): Double {
            val ind = ((aOverLambda-1)*10).roundToInt().coerceAtLeast(0)
            return when (K) {
                5   -> if (ind <= 100) g_5  [ind] else 0.0
                10  -> if (ind <= 70 ) g_10 [ind] else 0.0
                20  -> if (ind <= 30 ) g_20 [ind] else 0.0
                50  -> if (ind <= 10 ) g_50 [ind] else 0.0
                100 -> if (ind <= 10 ) g_100[ind] else 0.0
                else -> 0.0
            }
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
