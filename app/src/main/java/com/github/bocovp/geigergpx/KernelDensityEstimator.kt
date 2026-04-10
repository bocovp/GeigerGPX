package com.github.bocovp.geigergpx

import kotlin.math.floor

/**
 * Records Geiger counter event timestamps during a track and estimates
 * the continuous dose rate via Epanechnikov kernel density estimation.
 *
 * @param coeff  calibration factor [µSv/h per cps] — converts count rate to dose rate
 */
class KernelDensityEstimator(private val coeff: Double) {

    // Timestamps in seconds; appended in monotonically increasing order
    // (guaranteed by call-site: always System.currentTimeMillis()/1000.0)
    private var ts = DoubleArray(0)
    private var ns = IntArray(0)
    private var size = 0

    // Reuse a single instance just to access chi2L / chi2R without duplicating that logic
    private val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Clears all recorded events. Call at the start of every new track. */
    fun clear() {
        ts = DoubleArray(0)
        ns = IntArray(0)
        size = 0
    }

    /**
     * Records a new event.
     * @param time  event timestamp in seconds
     * @param n     number of counts detected at this event
     */
    fun addPoint(time: Double, n: Int) {
        if (size == ts.size) grow()
        ts[size] = time
        ns[size] = n
        size++
    }

    /**
     * Returns the first and last timestamps currently recorded.
     * If no points are present, returns null.
     */
    fun timestampBounds(): Pair<Double, Double>? {
        if (size <= 0) return null
        return Pair(ts[0], ts[size - 1])
    }

    /**
     * Core convolution — returns raw count rate in **cps** (not yet multiplied by [coeff]).
     *
     *   result(t) = (0.75 / scale) * Σ_i  ns[i] * (1 − u²),   u = (t − ts[i]) / scale
     *
     * Only events within [t − scale, t + scale] are visited (finite Epanechnikov support).
     */
    private fun estimateDoseRateHelper(t2s: DoubleArray, scale: Double): DoubleArray {
        val result = DoubleArray(t2s.size)
        if (size == 0) return result

        val invScale = 1.0 / scale
        val norm = 0.75 * invScale   // Epanechnikov constant; coeff NOT applied here

        for (j in t2s.indices) {
            val t = t2s[j]
            val lo = lowerBound(t - scale)
            val hi = upperBound(t + scale)
            var sum = 0.0
            for (i in lo until hi) {
                val u = (t - ts[i]) * invScale   // |u| < 1 guaranteed by the window
                sum += ns[i] * (1.0 - u * u)
            }
            result[j] = norm * sum
        }
        return result
    }

    /**
     * Estimates dose rate in **µSv/h** at each query point in [t2s].
     *
     * Delegates to [estimateDoseRateHelper] (cps) and multiplies by [coeff] (µSv/h per cps).
     */
    fun estimateDoseRate(t2s: DoubleArray, scale: Double): DoubleArray {
        val cps = estimateDoseRateHelper(t2s, scale)
        for (j in cps.indices) cps[j] *= coeff
        return cps
    }

    /**
     * Estimates dose rate and its Garwood (exact Poisson) confidence interval at each
     * query point in [t2s].
     *
     * Internally calls [estimateDoseRateHelper] to obtain cps values, then:
     *
     *   T_eff  = 5·scale / 3                   (effective observation window, seconds)
     *   n_eff  = cps[i] · T_eff                (effective count — continuous)
     *   nF     = floor(n_eff),  nC = nF + 1    (bracket integers)
     *   frac   = n_eff − nF                    (interpolation weight ∈ [0, 1))
     *
     *   lower[i] = lerp( chi2L(nF),     chi2L(nC)     , frac ) / (2·T_eff) · coeff
     *   upper[i] = lerp( chi2R(nF + 1), chi2R(nC + 1) , frac ) / (2·T_eff) · coeff
     *   mean[i]  = cps[i] · coeff
     *
     * @return Triple(doseRate [µSv/h], lowerBound [µSv/h], upperBound [µSv/h])
     */
    fun getConfidenceIntervals(t2s: DoubleArray, scale: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val cps   = estimateDoseRateHelper(t2s, scale)
        val tEff  = 5.0 * scale / 3.0
        val scale2usvh = 0.5 / tEff * coeff   // inv2T · coeff: maps chi2 quantile → µSv/h
        val mean  = DoubleArray(cps.size)
        val low   = DoubleArray(cps.size)
        val high  = DoubleArray(cps.size)

        for (i in cps.indices) {
            val nEff = (cps[i] * tEff).coerceAtLeast(0.0)
            val nF   = floor(nEff).toInt()
            val nC   = nF + 1
            val frac = nEff - nF   // ∈ [0, 1)

            // Linear interpolation between bracketing integer quantiles
            val chiL = ci.chi2L(nF) + frac * (ci.chi2L(nC) - ci.chi2L(nF))
            val chiR = ci.chi2R(nF + 1) + frac * (ci.chi2R(nC + 1) - ci.chi2R(nF + 1))

            mean[i] = cps[i] * coeff
            low[i]  = chiL * scale2usvh
            high[i] = chiR * scale2usvh
        }
        return Triple(mean, low, high)
    }

    // -------------------------------------------------------------------------
    // Internal helpers — dynamic array growth
    // -------------------------------------------------------------------------

    private fun grow() {
        val cap = if (ts.isEmpty()) 256 else ts.size * 2
        ts = ts.copyOf(cap)
        ns = ns.copyOf(cap)
    }

    // -------------------------------------------------------------------------
    // Binary search on the prefix ts[0..size) (already sorted)
    // -------------------------------------------------------------------------

    /** First index i in [0, size) such that ts[i] >= value, or size if none. */
    private fun lowerBound(value: Double): Int {
        var lo = 0; var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index i in [0, size) such that ts[i] > value, or size if none. */
    private fun upperBound(value: Double): Int {
        var lo = 0; var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
