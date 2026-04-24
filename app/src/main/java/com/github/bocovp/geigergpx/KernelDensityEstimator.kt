package com.github.bocovp.geigergpx

import kotlin.math.floor

/**
 * Estimates the continuous dose rate via Epanechnikov kernel density estimation.
 *
 * Point events are handled by the original O(N + Q) sliding-window accumulator.
 * Interval events are decomposed into pairs of weighted step events whose
 * convolution with the kernel is evaluated analytically — no discretisation.
 * See companion TeX document for the full mathematical derivation.
 *
 * @param coeff  calibration factor [µSv/h per cps]
 */
class KernelDensityEstimator(private val coeff: Double) {

    companion object {
        private const val INITIAL_CAPACITY    = 256
        /** Two step events closer than this [s] are considered co-incident and merged. */
        private const val STEP_MERGE_EPSILON  = 1e-6
        /** A merged step weight below this [cps] is treated as zero and the entry is dropped. */
        private const val STEP_WEIGHT_EPSILON = 1e-12
    }

    // -------------------------------------------------------------------------
    // Point events — timestamps in seconds, sorted ascending (bubble-back).
    // -------------------------------------------------------------------------
    private var ts   = DoubleArray(0)
    private var ns   = IntArray(0)
    private var size = 0

    // -------------------------------------------------------------------------
    // Step events — each addSampleInterval call produces two entries:
    //   (intervalStart, +counts/duration)  and  (intervalEnd, -counts/duration)
    // Sorted ascending by time via the same bubble-back strategy.
    // -------------------------------------------------------------------------
    private var stepTs    = DoubleArray(0)
    private var stepWs    = DoubleArray(0)   // weight [cps], positive or negative
    private var stepCount = 0

    // Overall data extent — covers point events AND interval endpoints.
    private var dataTStart = Double.MAX_VALUE
    private var dataTEnd   = Double.NEGATIVE_INFINITY

    // Reused to access chi2L / chi2R without duplicating that logic.
    private val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)

    // -------------------------------------------------------------------------
    // Reusable output buffers — resized lazily, never shrunk.
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
        size      = 0
        stepCount = 0
        dataTStart = Double.MAX_VALUE
        dataTEnd   = Double.NEGATIVE_INFINITY
    }

    /**
     * Records a new point event.
     * @param time  event timestamp in seconds
     * @param n     number of counts detected at this event
     */
    @Synchronized
    fun addPoint(time: Double, n: Int) {
        if (size == ts.size) growPoints()
        ts[size] = time
        ns[size] = n
        size++

        // Bubble backwards — O(1) amortised for nearly-sorted insertions.
        var i = size - 1
        while (i > 0 && ts[i - 1] > ts[i]) {
            val tmpT = ts[i - 1]; ts[i - 1] = ts[i]; ts[i] = tmpT
            val tmpN = ns[i - 1]; ns[i - 1] = ns[i]; ns[i] = tmpN
            i--
        }

        if (time < dataTStart) dataTStart = time
        if (time > dataTEnd)   dataTEnd   = time
    }

    /**
     * Records a uniform-rate sample spanning a time interval.
     *
     * Decomposes the rectangular count density into a rising step event at
     * [intervalStartSeconds] with weight +counts/duration and a falling step event
     * at the interval end with weight -counts/duration. Their convolution with the
     * Epanechnikov kernel is evaluated analytically in [estimateDoseRateHelper],
     * producing an exact, smooth estimate with no discretisation artefacts.
     *
     * Degenerate intervals (duration ≤ 0) fall back to a single point event.
     *
     * @param intervalStartSeconds  start of the counting window [s]
     * @param durationSeconds       length of the counting window [s]
     * @param counts                total counts recorded in the window
     */
    @Synchronized
    fun addSampleInterval(
        intervalStartSeconds: Double,
        durationSeconds:      Double,
        counts:               Int
    ) {
        if (counts <= 0) return
        val safeDuration = durationSeconds.coerceAtLeast(0.0)

        if (safeDuration == 0.0) {
            // Degenerate: treat as a single point event.
            addPoint(intervalStartSeconds, counts)
            return
        }

        val intervalEnd = intervalStartSeconds + safeDuration
        val density     = counts.toDouble() / safeDuration   // [cps]

        addStepEvent(intervalStartSeconds, +density)
        addStepEvent(intervalEnd,          -density)

        if (intervalStartSeconds < dataTStart) dataTStart = intervalStartSeconds
        if (intervalEnd          > dataTEnd)   dataTEnd   = intervalEnd
    }

    /**
     * Returns the first and last timestamps currently recorded, spanning both
     * point events and interval endpoints. Returns null if no data has been added.
     */
    @Synchronized
    fun timestampBounds(): Pair<Double, Double>? {
        if (dataTStart > dataTEnd) return null
        return Pair(dataTStart, dataTEnd)
    }

    /**
     * Estimates dose rate in µSv/h at each query point in [t2s] (sorted ascending).
     * Delegates to [estimateDoseRateHelper] for the cps estimate, then multiplies
     * by [coeff].
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
     * Estimates dose rate and its Garwood (exact Poisson) confidence interval at
     * each query point in [t2s] (sorted ascending). See companion TeX document for
     * the derivation of T_eff, n_eff, and the chi² interpolation.
     *
     * @return Triple(doseRate [µSv/h], lowerBound [µSv/h], upperBound [µSv/h])
     */
    @Synchronized
    fun getConfidenceIntervals(t2s: DoubleArray, scale: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        if (size == 0 && stepCount == 0)
            return Triple(DoubleArray(t2s.size), DoubleArray(t2s.size), DoubleArray(t2s.size))

        ensureBuffers(t2s.size)
        estimateDoseRateHelper(t2s, scale, bufCps, bufKernelMasses)

        val invScale = 1.0 / scale

        for (i in t2s.indices) {
            val t  = t2s[i]
            val km = bufKernelMasses[i]

            val uLo        = maxOf(-1.0, (t - dataTEnd)   * invScale)
            val uHi        = minOf( 1.0, (t - dataTStart) * invScale)
            val k2         = epanechnikovSquaredIntegral(uLo, uHi)
            val tEff       = if (k2 > 1e-12) scale * km * km / k2 else 0.0
            val scale2usvh = if (tEff > 0.0) 0.5 / tEff * coeff   else 0.0

            val nEff = (bufCps[i] * tEff).coerceAtLeast(0.0)
            val nF   = floor(nEff).toInt()
            val nC   = nF + 1
            val frac = nEff - nF

            val chiL = ci.chi2L(nF) + frac * (ci.chi2L(nC) - ci.chi2L(nF))
            val chiR = ci.chi2R(nF + 1) + frac * (ci.chi2R(nC + 1) - ci.chi2R(nF + 1))

            bufMean[i] = bufCps[i] * coeff
            bufLow[i]  = chiL * scale2usvh
            bufHigh[i] = chiR * scale2usvh
        }

        return Triple(bufMean.copyOf(t2s.size), bufLow.copyOf(t2s.size), bufHigh.copyOf(t2s.size))
    }

    // -------------------------------------------------------------------------
    // Core convolution — O(|t2s| + N + M) sliding window
    // N = point events, M <= step events (= 2 × intervals), Q = query points.
    // -------------------------------------------------------------------------

    /**
     * Fills [outCps] with raw count rate [cps] and [outKernelMasses] with the
     * boundary-correction kernel mass at each query point.
     *
     * Point-event contribution: three moment accumulators S0, S1, S2 (unchanged
     * from original). Interval contribution: four moment accumulators Tk0–Tk3
     * for transitioning step events, plus a scalar [stepDone] for completed ones.
     *
     * Requires [t2s] sorted ascending.
     */
    private fun estimateDoseRateHelper(
        t2s: DoubleArray, scale: Double,
        outCps: DoubleArray, outKernelMasses: DoubleArray
    ) {
        if (size == 0 && stepCount == 0) {
            outCps.fill(0.0, 0, t2s.size)
            outKernelMasses.fill(0.0, 0, t2s.size)
            return
        }

        val tStart    = dataTStart
        val tEnd      = dataTEnd
        val invScale  = 1.0 / scale
        val invScale2 = invScale  * invScale
        val invScale3 = invScale2 * invScale
        val norm      = 0.75 * invScale            // 3/(4h)

        // --- Point-event sliding window accumulators ---
        var lo = 0; var hi = 0
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0

        // --- Step-event sliding window accumulators ---
        // A step event at t_j passes through three phases as query t sweeps right:
        //   not-yet-active  (t < t_j − h) : contributes 0
        //   transitioning   (|t − t_j| ≤ h) : contributes w_j · F_K(t − t_j)
        //   completed       (t > t_j + h) : contributes w_j · 1
        //
        // Admit-before-evict ordering ensures that step events fully past the
        // current query (t_j + h ≤ t) are immediately routed to stepDone without
        // corrupting the Tk accumulators.
        var stepAdmit = 0           // next step event to enter the transitioning set
        var stepEvict = 0           // next step event to leave the transitioning set
        var stepDone  = 0.0         // Σ w_j  for completed step events  [cps]
        var tk0       = 0.0         // Σ w_j           for transitioning events
        var tk1       = 0.0         // Σ w_j · τ_j
        var tk2       = 0.0         // Σ w_j · τ_j²
        var tk3       = 0.0         // Σ w_j · τ_j³    (τ_j = t_j − tStart)

        for (j in t2s.indices) {
            val t     = t2s[j]
            val winLo = t - scale
            val winHi = t + scale

            // --- Point events: evict left edge, admit right edge ---
            while (lo < hi && ts[lo] < winLo) {
                val w = ns[lo].toDouble(); val tx = ts[lo] - tStart
                s0 -= w; s1 -= w * tx; s2 -= w * tx * tx
                lo++
            }
            while (hi < size && ts[hi] <= winHi) {
                val w = ns[hi].toDouble(); val tx = ts[hi] - tStart
                s0 += w; s1 += w * tx; s2 += w * tx * tx
                hi++
            }

            // --- Step events: ADMIT first, then EVICT ---
            // Admit when t ≥ t_j − h  ↔  t_j − h ≤ t  ↔  t_j ≤ t + h
            while (stepAdmit < stepCount && stepTs[stepAdmit] - scale <= t) {
                val w = stepWs[stepAdmit]; val tj = stepTs[stepAdmit] - tStart
                tk0 += w; tk1 += w * tj; tk2 += w * tj * tj; tk3 += w * tj * tj * tj
                stepAdmit++
            }
            // Evict when t ≥ t_j + h  ↔  t_j + h ≤ t
            while (stepEvict < stepAdmit && stepTs[stepEvict] + scale <= t) {
                val w = stepWs[stepEvict]; val tj = stepTs[stepEvict] - tStart
                stepDone += w
                tk0 -= w; tk1 -= w * tj; tk2 -= w * tj * tj; tk3 -= w * tj * tj * tj
                stepEvict++
            }

            val tRel = t - tStart   // τ = t − tStart  (numerical stability offset)

            // Point-event KDE numerator: (3/4h) · Σ n_i(1 − u_i²)
            // Expanded via S0, S1, S2 without an inner loop.
            val pointSum = norm * (s0 - (tRel * tRel * s0 - 2.0 * tRel * s1 + s2) * invScale2)

            // Interval contribution:
            //   completed part   : stepDone             [cps]
            //   transitioning part: (3/4) · Σ w_j · F_K(t − t_j) expanded in Tk accumulators
            val stepTransition = 0.75 * (
                tk0 * (tRel * invScale - tRel * tRel * tRel * invScale3 / 3.0 + 2.0 / 3.0) +
                tk1 * (tRel * tRel * invScale3 - invScale) -
                tk2 * (tRel * invScale3) +
                tk3 * (invScale3 / 3.0)
            )
            val intervalContrib = stepDone + stepTransition   // [cps]

            // Boundary correction: fraction of kernel support inside data extent.
            val uLo        = maxOf(-1.0, (t - tEnd)   * invScale)
            val uHi        = minOf( 1.0, (t - tStart) * invScale)
            val kernelMass = epanechnikovIntegral(uLo, uHi)

            outKernelMasses[j] = kernelMass
            outCps[j] = if (kernelMass > 1e-9) (pointSum + intervalContrib) / kernelMass else 0.0
        }
    }

    // -------------------------------------------------------------------------
    // Kernel integral helpers
    // -------------------------------------------------------------------------

    /**
     * Integral of the Epanechnikov kernel (3/4)(1 − u²) from [uLo] to [uHi].
     * Equals 1.0 on full support [−1, 1]. Used as boundary-correction mass.
     */
    private fun epanechnikovIntegral(uLo: Double, uHi: Double): Double {
        fun F(u: Double) = u - u * u * u / 3.0
        return 0.75 * (F(uHi) - F(uLo))
    }

    /**
     * Integral of K²(u) = (9/16)(1 − u²)² from [uLo] to [uHi].
     * Equals 3/5 on full support [−1, 1]. Used for T_eff in CI computation.
     */
    private fun epanechnikovSquaredIntegral(uLo: Double, uHi: Double): Double {
        fun G(u: Double) = u - 2.0 * u * u * u / 3.0 + u * u * u * u * u / 5.0
        return 0.5625 * (G(uHi) - G(uLo))
    }

    // -------------------------------------------------------------------------
    // Internal helpers — sorted insertion and dynamic growth
    // -------------------------------------------------------------------------

    /**
     * Inserts a step event maintaining sorted order via bubble-back, then attempts
     * to merge it with an existing step event at the same timestamp.
     *
     * Merging is triggered when the new event's timestamp is within [STEP_MERGE_EPSILON]
     * of its left neighbour after sorting.  The weights are summed; if the result is
     * below [STEP_WEIGHT_EPSILON] in magnitude the entry is removed entirely.
     *
     * This collapses the shared endpoint of two touching intervals into a single step
     * (or eliminates it when densities match), halving the step-event count in the
     * common fully-tiled case.
     */
    private fun addStepEvent(time: Double, weight: Double) {
        if (stepCount == stepTs.size) growSteps()
        stepTs[stepCount] = time
        stepWs[stepCount] = weight
        stepCount++

        // Bubble backwards to restore sort order.
        var i = stepCount - 1
        while (i > 0 && stepTs[i - 1] > stepTs[i]) {
            val tmpT = stepTs[i - 1]; stepTs[i - 1] = stepTs[i]; stepTs[i] = tmpT
            val tmpW = stepWs[i - 1]; stepWs[i - 1] = stepWs[i]; stepWs[i] = tmpW
            i--
        }

        // Merge with left neighbour if timestamps coincide.
        // After bubble-back the array had no duplicates, so only i−1 can match.
        if (i > 0 && stepTs[i] - stepTs[i - 1] <= STEP_MERGE_EPSILON) {
            stepWs[i - 1] += stepWs[i]
            removeStepAt(i)          // close the gap; O(1) for end-of-array insertions
            i--
            // Drop the merged entry if its weight is negligible.
            if (kotlin.math.abs(stepWs[i]) < STEP_WEIGHT_EPSILON) {
                removeStepAt(i)
            }
        }
    }

    /**
     * Removes the step event at [index] by shifting subsequent entries one place left.
     * O(stepCount − index); O(1) for the common case where [index] is near the end.
     */
    private fun removeStepAt(index: Int) {
        System.arraycopy(stepTs, index + 1, stepTs, index, stepCount - index - 1)
        System.arraycopy(stepWs, index + 1, stepWs, index, stepCount - index - 1)
        stepCount--
    }

    private fun growPoints() {
        val cap = if (ts.isEmpty()) INITIAL_CAPACITY else ts.size * 2
        ts = ts.copyOf(cap)
        ns = ns.copyOf(cap)
    }

    private fun growSteps() {
        val cap = if (stepTs.isEmpty()) INITIAL_CAPACITY else stepTs.size * 2
        stepTs = stepTs.copyOf(cap)
        stepWs = stepWs.copyOf(cap)
    }
}
