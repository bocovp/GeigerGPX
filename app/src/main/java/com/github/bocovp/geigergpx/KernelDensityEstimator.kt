package com.github.bocovp.geigergpx

import kotlin.math.floor

/**
 * Estimates the continuous dose rate via Epanechnikov kernel density estimation.
 * @param coeff  calibration factor [µSv/h per cps] — converts count rate to dose rate
 */
class KernelDensityEstimator(private val coeff: Double) {
    companion object {
        private const val MAX_COUNTS_PER_POINT = 10
    }

    // Timestamps in seconds, maintained in sorted order.
    // addPoint appends and bubbles back; rare out-of-order events from thread races
    // are tolerated and corrected automatically.
    private var ts   = DoubleArray(0)
    private var ns   = IntArray(0)
    private var size = 0

    // Reuse a single instance just to access chi2L / chi2R without duplicating that logic
    private val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)

    // -------------------------------------------------------------------------
    // Reusable output buffers — resized lazily, never shrunk
    // Avoids per-call heap allocation and GC pressure on cheap devices
    // -------------------------------------------------------------------------
    private var bufCps          = DoubleArray(0)
    private var bufKernelMasses = DoubleArray(0)
    private var bufMean         = DoubleArray(0)
    private var bufLow          = DoubleArray(0)
    private var bufHigh         = DoubleArray(0)

    private fun ensureBuffers(n: Int) {
        if (bufCps.size < n) {
            bufCps          = DoubleArray(n)
            bufKernelMasses = DoubleArray(n)
            bufMean         = DoubleArray(n)
            bufLow          = DoubleArray(n)
            bufHigh         = DoubleArray(n)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Clears all recorded events. Call at the start of every new track. */
    @Synchronized
    fun clear() {
        ts   = DoubleArray(0)
        ns   = IntArray(0)
        size = 0
    }

    /**
     * Records a new event.
     * @param time  event timestamp in seconds
     * @param n     number of counts detected at this event
     */

    @Synchronized
    fun addPoint(time: Double, n: Int) {
        if (size == ts.size) grow()
        ts[size] = time
        ns[size] = n
        size++

        // Bubble the new event backwards until ts is sorted.
        // In the common case (in-order insertion) this loop body never executes.
        var i = size - 1
        while (i > 0 && ts[i - 1] > ts[i]) {
            // Swap timestamps
            val tmpT = ts[i - 1];
            ts[i - 1] = ts[i];
            ts[i] = tmpT
            // Swap counts
            val tmpN = ns[i - 1];
            ns[i - 1] = ns[i];
            ns[i] = tmpN
            i--
        }
    }

    /**
     * Adds a count sample that spans a time interval.
     *
     * For numerical stability and better KDE conditioning, large counts are split into
     * multiple pseudo-events distributed uniformly across the interval midpoint grid.
     */
    @Synchronized
    fun addSampleInterval(
        intervalStartSeconds: Double,
        durationSeconds: Double,
        counts: Int
    ) {
        if (counts <= 0) return

        val safeDuration = durationSeconds.coerceAtLeast(0.0)
        val intervalEndSeconds = intervalStartSeconds + safeDuration
        val groupCount = kotlin.math.ceil(counts / MAX_COUNTS_PER_POINT.toDouble()).toInt().coerceAtLeast(1)
        val baseGroupSize = counts / groupCount
        val groupsWithExtraCount = counts % groupCount

        for (groupIndex in 0 until groupCount) {
            val groupSize = baseGroupSize + if (groupIndex < groupsWithExtraCount) 1 else 0
            val timestamp = if (safeDuration <= 0.0) {
                intervalStartSeconds
            } else {
                intervalStartSeconds + ((groupIndex + 0.5) / groupCount) * (intervalEndSeconds - intervalStartSeconds)
            }
            addPoint(timestamp, groupSize)
        }
    }

    /**
     * Returns the first and last timestamps currently recorded.
     * If no points are present, returns null.
     */
    @Synchronized
    fun timestampBounds(): Pair<Double, Double>? {
        if (size <= 0) return null
        return Pair(ts[0], ts[size - 1])
    }

    /**
     * Estimates dose rate in **µSv/h** at each query point in [t2s].
     * Assumes [t2s] is sorted in ascending order.
     *
     * Delegates to [estimateDoseRateHelper] (cps) and multiplies by [coeff] (µSv/h per cps).
     */
    @Synchronized
    fun estimateDoseRate(t2s: DoubleArray, scale: Double): DoubleArray {
        ensureBuffers(t2s.size)
        estimateDoseRateHelper(t2s, scale, bufCps, bufKernelMasses)
        val result = DoubleArray(t2s.size)
        for (j in t2s.indices) result[j] = bufCps[j] * coeff
        return result
    }

    /**
     * Estimates dose rate and its Garwood (exact Poisson) confidence interval at each
     * query point in [t2s]. Assumes [t2s] is sorted in ascending order.
     *
     * Internally calls [estimateDoseRateHelper] to obtain cps values, then:
     *
     *   T_eff  = scale · kernelMass² / ∫K²   (boundary-corrected effective window)
     *   n_eff  = cps[i] · T_eff               (effective count — continuous)
     *   nF     = floor(n_eff),  nC = nF + 1   (bracket integers)
     *   frac   = n_eff − nF                   (interpolation weight ∈ [0, 1))
     *
     *   lower[i] = lerp( chi2L(nF),     chi2L(nC)     , frac ) / (2·T_eff) · coeff
     *   upper[i] = lerp( chi2R(nF + 1), chi2R(nC + 1) , frac ) / (2·T_eff) · coeff
     *   mean[i]  = cps[i] · coeff
     *
     * @return Triple(doseRate [µSv/h], lowerBound [µSv/h], upperBound [µSv/h])
     */
    @Synchronized
    fun getConfidenceIntervals(t2s: DoubleArray, scale: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        if (size == 0) return Triple(DoubleArray(t2s.size), DoubleArray(t2s.size), DoubleArray(t2s.size))

        ensureBuffers(t2s.size)
        estimateDoseRateHelper(t2s, scale, bufCps, bufKernelMasses)

        val tStart   = ts[0]
        val tEnd     = ts[size - 1]
        val invScale = 1.0 / scale

        for (i in t2s.indices) {
            val t          = t2s[i]
            val km         = bufKernelMasses[i]   // already computed, no recalculation needed

            // Exact boundary-corrected effective window:
            //   tEff = scale · km² / ∫[uLo,uHi] K²(u) du
            // For an interior point this reduces to scale · 1² / 0.6 = 5·scale/3 ✓
            val uLo        = maxOf(-1.0, (t - tEnd)   * invScale)
            val uHi        = minOf( 1.0, (t - tStart) * invScale)
            val k2         = epanechnikovSquaredIntegral(uLo, uHi)
            val tEff       = if (k2 > 1e-12) scale * km * km / k2 else 0.0
            val scale2usvh = if (tEff > 0.0)  0.5 / tEff * coeff  else 0.0

            val nEff = (bufCps[i] * tEff).coerceAtLeast(0.0)
            val nF   = floor(nEff).toInt()
            val nC   = nF + 1
            val frac = nEff - nF   // ∈ [0, 1)

            // Linear interpolation between bracketing integer quantiles
            val chiL = ci.chi2L(nF) + frac * (ci.chi2L(nC) - ci.chi2L(nF))
            val chiR = ci.chi2R(nF + 1) + frac * (ci.chi2R(nC + 1) - ci.chi2R(nF + 1))

            bufMean[i] = bufCps[i] * coeff
            bufLow[i]  = chiL * scale2usvh
            bufHigh[i] = chiR * scale2usvh
        }

        // Copy out of reusable buffers into fresh arrays for the caller
        return Triple(bufMean.copyOf(t2s.size), bufLow.copyOf(t2s.size), bufHigh.copyOf(t2s.size))
    }

    // -------------------------------------------------------------------------
    // Core convolution — O(|t2s| + N) sliding window with moment sums
    // -------------------------------------------------------------------------

    /**
     * Fills [outCps] with raw count rate in **cps** and [outKernelMasses] with the
     * boundary-correction factor at each query point.
     *
     * Uses a sliding window over the sorted event array [ts] and rewrites the
     * Epanechnikov sum in terms of three scalar accumulators:
     *
     *   Σ ns[i]·(1−u²) = S0 − (t²·S0 − 2t·S1 + S2) / scale²
     *
     * where  S0 = Σns[i],  S1 = Σns[i]·ts[i],  S2 = Σns[i]·ts[i]²
     *
     * Evicting/admitting one event costs O(1), so the inner loop is eliminated.
     * Complexity: O(|t2s| + N)  vs the previous O(|t2s|·W).
     *
     * **Requires [t2s] to be sorted in ascending order.**
     */

    private fun estimateDoseRateHelper(
        t2s: DoubleArray, scale: Double,
        outCps: DoubleArray, outKernelMasses: DoubleArray
    ) {
        if (size == 0) {
            outCps.fill(0.0, 0, t2s.size)
            outKernelMasses.fill(0.0, 0, t2s.size)
            return
        }

        val invScale  = 1.0 / scale
        val invScale2 = invScale * invScale
        val norm      = 0.75 * invScale
        val tStart    = ts[0]
        val tEnd      = ts[size - 1]

        var lo = 0; var hi = 0          // sliding window indices into ts[]
        var s0 = 0.0                    // Σ ns[i]
        var s1 = 0.0                    // Σ ns[i]·ts[i]
        var s2 = 0.0                    // Σ ns[i]·ts[i]²

        for (j in t2s.indices) {
            val t     = t2s[j]
            val winLo = t - scale
            val winHi = t + scale

            // Evict events that have left the left edge of the window
            while (lo < hi && ts[lo] < winLo) {
                val w = ns[lo].toDouble();
                val tx = ts[lo] - tStart       // OFFSET APPLIED
                s0 -= w;
                s1 -= w * tx;
                s2 -= w * tx * tx
                lo++
            }
            // Admit events newly inside the right edge of the window
            while (hi < size && ts[hi] <= winHi) {
                val w = ns[hi].toDouble();
                val tx = ts[hi] - tStart       // OFFSET APPLIED
                s0 += w;
                s1 += w * tx;
                s2 += w * tx * tx
                hi++
            }

            val tRel = t - tStart
            // Σ ns[i]·(1−u²)  expanded without per-event inner loop
            val sum = s0 - (tRel * tRel * s0 - 2.0 * tRel * s1 + s2) * invScale2

            // Boundary correction
            val uLo        = maxOf(-1.0, (t - tEnd)   * invScale)
            val uHi        = minOf( 1.0, (t - tStart) * invScale)
            val kernelMass = epanechnikovIntegral(uLo, uHi)

            outKernelMasses[j] = kernelMass
            outCps[j]          = if (kernelMass > 1e-9) norm * sum / kernelMass else 0.0
        }
    }

    // -------------------------------------------------------------------------
    // Kernel integral helpers
    // -------------------------------------------------------------------------

    /**
     * Integral of the Epanechnikov kernel 0.75·(1−u²) from [uLo] to [uHi].
     * Equals 1.0 on full support [−1, 1].
     */
    private fun epanechnikovIntegral(uLo: Double, uHi: Double): Double {
        // Antiderivative of (1 − u²)
        fun F(u: Double) = u - u * u * u / 3.0
        return 0.75 * (F(uHi) - F(uLo))
    }

    /**
     * Integral of K²(u) = 0.75²·(1−u²)² from [uLo] to [uHi].
     * Equals 0.6 on full support [−1, 1], giving tEff = 5·scale/3 in the interior.
     */
    private fun epanechnikovSquaredIntegral(uLo: Double, uHi: Double): Double {
        // Antiderivative of (1 − u²)² = 1 − 2u² + u⁴
        fun G(u: Double) = u - 2.0 * u * u * u / 3.0 + u * u * u * u * u / 5.0
        return 0.5625 * (G(uHi) - G(uLo))   // 0.5625 = 0.75²
    }

    // -------------------------------------------------------------------------
    // Internal helpers — dynamic array growth
    // -------------------------------------------------------------------------

    private fun grow() {
        val cap = if (ts.isEmpty()) 256 else ts.size * 2
        ts = ts.copyOf(cap)
        ns = ns.copyOf(cap)
    }
}
