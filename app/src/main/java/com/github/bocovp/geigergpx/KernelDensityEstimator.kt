package com.github.bocovp.geigergpx

import kotlin.math.floor

/**
 * Estimates the continuous dose rate via Epanechnikov kernel density estimation.
 *
 * Point events use a sliding-window accumulator with binary seeks to skip
 * data outside the requested kernel windows.
 * Interval events are decomposed into pairs of weighted step events whose
 * convolution with the kernel is evaluated analytically — no discretisation.
 * See companion TeX document for the full mathematical derivation.
 *
 * @param sensitivity  sensitivity [cps per µSv/h]
 */
class KernelDensityEstimator(private var sensitivity: Double) {

    /** Optional retention window in seconds. When positive, newly added data evicts older events. */
    var timeout: Double = 0.0

    companion object {
        private const val INITIAL_CAPACITY    = 256
        /** Two step events closer than this [s] are considered co-incident and merged. */
        private const val STEP_MERGE_EPSILON  = 1e-6
        /** A merged step weight below this [cps] is treated as zero and the entry is dropped. */
        private const val STEP_WEIGHT_EPSILON = 1e-12
        /** Dose rate [µSv/h] at which the adaptive bandwidth equals the slider scale. */
        private const val ADAPTIVE_BACKGROUND_DOSE = 0.1
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

    // Lazy cumulative step weights: prefix[k] = Σ stepWs[0..k]. A query can
    // start in the middle of a long interval without visiting its earlier edge.
    private var stepPrefix = DoubleArray(0)
    private var stepPrefixValid = 0

    // Original intervals — retained separately because merged step events lose
    // the midpoint and pairing needed to assign one adaptive scale per interval.
    private data class SampleInterval(val start: Double, val end: Double, val density: Double)
    private val intervals = ArrayList<SampleInterval>()
    private var intervalIndex: IntervalIndex? = null

    // Overall data extent — covers point events AND interval endpoints.
    private var dataTStart = Double.MAX_VALUE
    private var dataTEnd   = Double.NEGATIVE_INFINITY

    // Reused to access chi2L / chi2R without duplicating that logic.
    private val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)
    private var inverseSensitivity = if (sensitivity > 0.0) 1.0 / sensitivity else 0.0

    fun updateSensitivity(newSensitivity: Double) {
        sensitivity = newSensitivity
        inverseSensitivity = if (newSensitivity > 0.0) 1.0 / newSensitivity else 0.0
    }

    /**
     * Creates a deep copy of the estimator's current state.
     * This is used to take a fast snapshot of the data so that long-running
     * calculations (like getConfidenceIntervals) can be performed on a background
     * thread without blocking the ingestion of new points on the audio thread.
     */
    @Synchronized
    fun copy(): KernelDensityEstimator {
        val other = KernelDensityEstimator(sensitivity)
        other.ts = ts.copyOf(size)
        other.ns = ns.copyOf(size)
        other.size = size
        other.stepTs = stepTs.copyOf(stepCount)
        other.stepWs = stepWs.copyOf(stepCount)
        other.stepCount = stepCount
        other.stepPrefix = stepPrefix.copyOf(stepPrefixValid)
        other.stepPrefixValid = stepPrefixValid
        other.intervals.addAll(intervals)
        other.intervalIndex = intervalIndex   // immutable; safe to share between snapshots
        other.dataTStart = dataTStart
        other.dataTEnd = dataTEnd
        other.timeout = timeout
        return other
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Clears all recorded events. Call at the start of every new track. */
    @Synchronized
    fun clear() {
        size      = 0
        stepCount = 0
        stepPrefixValid = 0
        intervals.clear()
        intervalIndex = null
        dataTStart = Double.MAX_VALUE
        dataTEnd   = Double.NEGATIVE_INFINITY
    }

    /**
     * Records a new point event.
     * @param time  event timestamp in seconds
     * @param n     number of counts detected at this event
     * @param spreadCounts  if true and n > 1, evenly spaces the counts between the last recorded time and this event
     * */
    @Synchronized
    fun addPoint(time: Double, n: Int, spreadCounts: Boolean = false) {
        if (n <= 0) return

        if (spreadCounts && n > 1 && dataTEnd != Double.NEGATIVE_INFINITY && time > dataTEnd) {
            val startT = dataTEnd
            val step = (time - startT) / n
            for (i in 1..n) {
                insertPoint(startT + i * step, 1)
            }
        } else {
            // Fallback for n=1, or the very first point of the track
            insertPoint(time, n)
        }
    }

    private fun insertPoint(time: Double, n: Int) {
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
        pruneTimedOut(time)
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

        intervals.add(SampleInterval(intervalStartSeconds, intervalEnd, density))
        intervalIndex = null
        addStepEvent(intervalStartSeconds, +density)
        addStepEvent(intervalEnd,          -density)

        if (intervalStartSeconds < dataTStart) dataTStart = intervalStartSeconds
        if (intervalEnd          > dataTEnd)   dataTEnd   = intervalEnd
        pruneTimedOut(intervalEnd)
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
     * by the precalculated inverse sensitivity.
     */
    fun estimateDoseRate(t2s: DoubleArray,
                         scale: Double,
                         tEndOverride: Double? = null): DoubleArray {
        val bufCps = DoubleArray(t2s.size)
        val bufKernelMasses = DoubleArray(t2s.size)
        estimateDoseRateHelper(t2s, scale, bufCps, bufKernelMasses, tEndOverride)
        val result = DoubleArray(t2s.size)
        for (j in t2s.indices) result[j] = bufCps[j] * inverseSensitivity
        return result
    }

    /**
     * Estimates dose rate and its Garwood (exact Poisson) confidence interval at
     * each query point in [t2s] (sorted ascending). See companion TeX document for
     * the derivation of T_eff, n_eff, and the chi² interpolation.
     *
     * @return Triple(doseRate [µSv/h], lowerBound [µSv/h], upperBound [µSv/h])
     */
    fun getConfidenceIntervals(t2s: DoubleArray,
                               scale: Double,
                               tEndOverride: Double? = null): Triple<DoubleArray, DoubleArray, DoubleArray> {
        if (size == 0 && stepCount == 0)
            return Triple(DoubleArray(t2s.size), DoubleArray(t2s.size), DoubleArray(t2s.size))

        val bufCps = DoubleArray(t2s.size)
        val bufKernelMasses = DoubleArray(t2s.size)
        val bufMean = DoubleArray(t2s.size)
        val bufLow = DoubleArray(t2s.size)
        val bufHigh = DoubleArray(t2s.size)

        estimateDoseRateHelper(t2s, scale, bufCps, bufKernelMasses, tEndOverride)

        val invScale = 1.0 / scale

        val effectiveTStart = dataTStart
        val effectiveTEnd =  if (tEndOverride != null) maxOf(dataTEnd, tEndOverride) else dataTEnd

        for (i in t2s.indices) {
            val t  = t2s[i]
            val km = bufKernelMasses[i]

            val tClamped   = t.coerceIn(effectiveTStart, effectiveTEnd)
            //Outside the measurement range, don't allow the effective exposure to shrink further; use the boundary value.

            val uLo        = maxOf(-1.0, (tClamped - effectiveTEnd   ) * invScale)
            val uHi        = minOf( 1.0, (tClamped - effectiveTStart ) * invScale)
            val k2         = epanechnikovSquaredIntegral(uLo, uHi)
            val tEff       = if (k2 > 1e-12) scale * km * km / k2 else 0.0
            val scale2usvh = if (tEff > 0.0) 0.5 / tEff * inverseSensitivity   else 0.0

            val nEff = (bufCps[i] * tEff).coerceAtLeast(0.0)
            val nF   = floor(nEff).toInt()
            val nC   = nF + 1
            val frac = nEff - nF

            val chiL = ci.chi2L(nF) + frac * (ci.chi2L(nC) - ci.chi2L(nF))
            val chiR = ci.chi2R(nF + 1) + frac * (ci.chi2R(nC + 1) - ci.chi2R(nF + 1))

            bufMean[i] = bufCps[i] * inverseSensitivity
            bufLow[i]  = chiL * scale2usvh
            bufHigh[i] = chiR * scale2usvh
        }

        return Triple(bufMean, bufLow, bufHigh)
    }

    // -------------------------------------------------------------------------
    // Adaptive KDE — fixed-bandwidth pilot, then one bandwidth per source.
    // Kept separate from the original sliding-window convolution below.
    // -------------------------------------------------------------------------

    /**
     * Estimates adaptive dose rate in µSv/h at sorted query points [t2s].
     *
     * The fixed KDE at [scale] supplies the pilot dose at each point event or
     * interval midpoint. Each source then uses h_i = scale · 0.1 / pilotDose,
     * clamped to [scale / 10, scale]. Interval counts remain uniformly spread
     * over their original window; only bandwidth selection uses the midpoint.
     */
    fun estimateAdaptiveDoseRate(t2s: DoubleArray,
                                 scale: Double,
                                 tEndOverride: Double? = null): DoubleArray {
        val bufCps = DoubleArray(t2s.size)
        estimateAdaptiveDoseRateHelper(t2s, scale, bufCps, null, tEndOverride)
        for (i in bufCps.indices) bufCps[i] *= inverseSensitivity
        return bufCps
    }

    /**
     * Estimates adaptive dose rate and approximate 95% Poisson confidence bounds.
     *
     * With the pilot bandwidths held fixed, A = Σ n_i q_i and V = Σ n_i q_i²
     * give the empirical exposure A/V. For uniform intervals the sums become
     * integrals of q and q², respectively — not the square of the interval mean.
     * T_eff is capped at the background-scale exposure to avoid false precision
     * in sparse tails; n_eff = A · T_eff then supplies the effective count.
     * The effective count uses the same chi² interpolation as the fixed KDE.
     * These plug-in bounds do not include uncertainty in the pilot bandwidths.
     *
     * @return Triple(doseRate [µSv/h], lowerBound [µSv/h], upperBound [µSv/h])
     */
    fun getAdaptiveConfidenceIntervals(t2s: DoubleArray,
                                       scale: Double,
                                       tEndOverride: Double? = null): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val bufCps = DoubleArray(t2s.size)
        val bufVariance = DoubleArray(t2s.size)
        val bufMean = DoubleArray(t2s.size)
        val bufLow = DoubleArray(t2s.size)
        val bufHigh = DoubleArray(t2s.size)
        estimateAdaptiveDoseRateHelper(t2s, scale, bufCps, bufVariance, tEndOverride)

        if (size == 0 && intervals.isEmpty()) return Triple(bufMean, bufLow, bufHigh)
        val tEnd = if (tEndOverride != null) maxOf(dataTEnd, tEndOverride) else dataTEnd
        if (tEnd <= dataTStart) return Triple(bufMean, bufLow, bufHigh)

        for (i in t2s.indices) {
            val cps = bufCps[i]
            val variance = bufVariance[i]
            // Maximum exposure: the background scale also supplies a continuous,
            // positive zero-count upper bound when the last contribution fades.
            val tClamped = t2s[i].coerceIn(dataTStart, tEnd)
            val uLo = maxOf(-1.0, (tClamped - tEnd) / scale)
            val uHi = minOf( 1.0, (tClamped - dataTStart) / scale)
            val km = epanechnikovIntegral(uLo, uHi)
            val k2 = epanechnikovSquaredIntegral(uLo, uHi)
            val backgroundExposure = if (km > 1e-9 && k2 > 1e-12) scale * km * km / k2 else 0.0
            val tEff = if (cps > 0.0 && variance > 0.0) {
                minOf(cps / variance, backgroundExposure)
            } else backgroundExposure
            val nEff = (cps * tEff).coerceAtLeast(0.0)
            val nF = floor(nEff).toInt()
            val nC = nF + 1
            val frac = nEff - nF
            val chiL = ci.chi2L(nF) + frac * (ci.chi2L(nC) - ci.chi2L(nF))
            val chiR = ci.chi2R(nF + 1) + frac * (ci.chi2R(nC + 1) - ci.chi2R(nF + 1))
            val scale2usvh = if (tEff > 0.0) 0.5 / tEff * inverseSensitivity else 0.0

            bufMean[i] = cps * inverseSensitivity
            bufLow[i]  = chiL * scale2usvh
            bufHigh[i] = chiR * scale2usvh
        }
        return Triple(bufMean, bufLow, bufHigh)
    }

    /** A point (start == end, weight = counts) or interval (weight = density [cps]). */
    private data class AdaptiveSample(
        val start: Double, val end: Double, val weight: Double, val scale: Double
    ) {
        val midpoint = start + (end - start) / 2.0
        val beginning = start - scale
        val ending = end + scale
    }

    /**
     * Fills the adaptive rate [outCps] and, optionally, its Poisson [outVariance].
     *
     * Beginning and ending times are sorted independently: variable bandwidths
     * can reverse either order relative to the original timestamps. Only active
     * sources are evaluated at a query, using local differences for stability.
     * Only sources intersecting the query range expanded by [scale] need pilot
     * estimates. Long intervals keep their original midpoints, even when those
     * lie far outside the query range; the pilot seeks to each required window.
     */
    private fun estimateAdaptiveDoseRateHelper(
        t2s: DoubleArray, scale: Double,
        outCps: DoubleArray, outVariance: DoubleArray?,
        tEndOverride: Double? = null
    ) {
        require(scale.isFinite() && scale > 0.0) { "Scale must be finite and positive" }
        require(tEndOverride == null || tEndOverride.isFinite()) { "End time must be finite" }
        for (i in t2s.indices) {
            require(t2s[i].isFinite() && (i == 0 || t2s[i] >= t2s[i - 1])) {
                "Query times must be finite and sorted ascending"
            }
        }
        if (t2s.isEmpty() || (size == 0 && intervals.isEmpty())) return
        val tEnd = if (tEndOverride != null) maxOf(dataTEnd, tEndOverride) else dataTEnd
        if (tEnd <= dataTStart) return

        // --- Candidate selection: no final bandwidth can exceed the slider scale ---
        // Preserve the full data extent for both pilot and final boundary masses.
        val rangeLo = t2s.first() - scale
        val rangeHi = t2s.last() + scale
        val pointFrom = lowerBound(ts, size, rangeLo)
        val pointUntil = upperBound(ts, size, rangeHi)
        val sources = ArrayList<AdaptiveSample>(pointUntil - pointFrom)
        for (i in pointFrom until pointUntil) {
            sources.add(AdaptiveSample(ts[i], ts[i], ns[i].toDouble(), scale))
        }
        if (intervals.isNotEmpty()) {
            val index = intervalIndex ?: IntervalIndex(intervals).also { intervalIndex = it }
            index.addOverlapping(rangeLo, rangeHi, scale, sources)
        }
        if (sources.isEmpty()) return

        // --- First pass: evaluate the fixed KDE only at candidate centres ---
        // Point-only data needs at most a 2h_0 halo. Interval midpoints can be
        // arbitrarily far away, so do not crop pilot inputs to that fixed halo.
        sources.sortBy { it.midpoint }
        val pilotTimes = DoubleArray(sources.size) { sources[it].midpoint }
        val pilotDoses = estimateDoseRate(pilotTimes, scale, tEndOverride)
        for (i in sources.indices) {
            // A zero pilot dose keeps the maximum (background) bandwidth.
            val dose = pilotDoses[i]
            val h = if (dose > 0.0) {
                (scale * ADAPTIVE_BACKGROUND_DOSE / dose).coerceIn(scale / 10.0, scale)
            } else scale
            sources[i] = sources[i].copy(scale = h)
        }
        sources.removeAll { it.ending < t2s.first() || it.beginning > t2s.last() }
        if (sources.isEmpty()) return

        // --- Second pass: independently ordered admission and eviction events ---
        val beginnings = sources.indices.sortedBy { sources[it].beginning }
        val endings = sources.indices.sortedBy { sources[it].ending }
        val active = LinkedHashSet<Int>()
        var admit = 0
        var evict = 0
        for (j in t2s.indices) {
            val t = t2s[j]
            // Admit before evicting so a sparse query can skip an entire support.
            while (admit < sources.size && sources[beginnings[admit]].beginning <= t) {
                active.add(beginnings[admit++])
            }
            while (evict < sources.size && sources[endings[evict]].ending <= t) {
                active.remove(endings[evict++])
            }

            val tClamped = t.coerceIn(dataTStart, tEnd)
            var cps = 0.0
            var variance = 0.0
            for (index in active) {
                val source = sources[index]
                val invScale = 1.0 / source.scale
                // Each source uses its own boundary mass. Outside the measured
                // extent, keep the mass at the boundary as in the fixed KDE.
                val massLo = maxOf(-1.0, (tClamped - tEnd) * invScale)
                val massHi = minOf( 1.0, (tClamped - dataTStart) * invScale)
                val km = epanechnikovIntegral(massLo, massHi)
                if (km <= 1e-9) continue

                if (source.start == source.end) {
                    val u = (t - source.start) * invScale
                    val q = 0.75 * invScale * (1.0 - u * u).coerceAtLeast(0.0) / km
                    cps += source.weight * q
                    if (outVariance != null) variance += source.weight * q * q
                } else {
                    val uLo = maxOf(-1.0, (t - source.end) * invScale)
                    val uHi = minOf( 1.0, (t - source.start) * invScale)
                    if (uHi <= uLo) continue
                    val mass = adaptiveKernelIntegral(uLo, uHi)
                    cps += source.weight * mass / km
                    if (outVariance != null) {
                        val k2 = adaptiveSquaredKernelIntegral(uLo, uHi)
                        variance += source.weight * invScale * k2 / (km * km)
                    }
                }
            }
            outCps[j] = cps
            if (outVariance != null) outVariance[j] = variance
        }
    }

    /**
     * Definite kernel integrals for short overlaps near the support edges.
     * Expanding around the overlap midpoint avoids subtracting almost equal
     * primitives; in particular, the squared-kernel mass can be very small.
     * The original fixed-bandwidth integral helpers remain unchanged.
     */
    private fun adaptiveKernelIntegral(uLo: Double, uHi: Double): Double {
        val width = uHi - uLo
        val mid = (uLo + uHi) / 2.0
        val shape = (1.0 - mid) * (1.0 + mid)
        return (0.75 * width * (shape - width * width / 12.0)).coerceAtLeast(0.0)
    }

    private fun adaptiveSquaredKernelIntegral(uLo: Double, uHi: Double): Double {
        val width = uHi - uLo
        val mid = (uLo + uHi) / 2.0
        val width2 = width * width
        val shape = (1.0 - mid) * (1.0 + mid)
        return (0.5625 * width * (shape * shape +
            (3.0 * mid * mid - 1.0) * width2 / 6.0 + width2 * width2 / 80.0)).coerceAtLeast(0.0)
    }

    // -------------------------------------------------------------------------
    // Core convolution — seek to the requested windows, then slide locally.
    // Earlier completed steps are represented by their cumulative density.
    // -------------------------------------------------------------------------

    /**
     * Fills [outCps] with raw count rate [cps] and [outKernelMasses] with the
     * boundary-correction kernel mass at each query point.
     *
     * Point-event contribution: three moment accumulators S0, S1, S2.
     * Interval contribution: four moment accumulators Tk0–Tk3
     * for transitioning step events, plus a scalar [stepDone] for completed ones.
     * Binary searches skip points and step transitions outside the query windows;
     * the original data extent still supplies the boundary correction.
     *
     * Requires [t2s] sorted ascending.
     */
    private fun estimateDoseRateHelper(
        t2s: DoubleArray, scale: Double,
        outCps: DoubleArray, outKernelMasses: DoubleArray,
        tEndOverride: Double? = null
    ) {
        if (t2s.isEmpty()) return
        if (size == 0 && stepCount == 0) {
            outCps.fill(0.0, 0, t2s.size)
            outKernelMasses.fill(0.0, 0, t2s.size)
            return
        }

        val tStart    = dataTStart
        val tEnd      =   if (tEndOverride != null) maxOf(dataTEnd, tEndOverride) else dataTEnd
        val invScale  = 1.0 / scale
        val invScale2 = invScale  * invScale
        val invScale3 = invScale2 * invScale
        val norm      = 0.75 * invScale            // 3/(4h)

        // --- Point-event sliding window accumulators ---
        var lo = 0; var hi = 0
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        var origin = t2s.first()    // local moment origin, independent of data bounds

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
        var tk3       = 0.0         // Σ w_j · τ_j³    (τ_j = t_j − origin)

        for (j in t2s.indices) {
            val t     = t2s[j]
            val winLo = t - scale
            val winHi = t + scale

            // Seek directly to the first window, or across a gap between windows.
            // Periodic rebasing also keeps polynomial moments small on long plots.
            // With 32h between rebases, each transition can straddle at most one.
            if (j == 0 || t - t2s[j - 1] >= 2.0 * scale || t - origin > 32.0 * scale) {
                lo = lowerBound(ts, size, winLo)
                hi = lo
                s0 = 0.0; s1 = 0.0; s2 = 0.0
                origin = t
                stepEvict = upperBound(stepTs, stepCount, winLo)
                stepAdmit = stepEvict
                stepDone = completedStepWeight(stepEvict)
                tk0 = 0.0; tk1 = 0.0; tk2 = 0.0; tk3 = 0.0
            }

            // --- Point events: ADMIT first, then EVICT ---
            // This also excludes newly admitted old points after sparse queries.
            while (hi < size && ts[hi] <= winHi) {
                val w = ns[hi].toDouble(); val tx = ts[hi] - origin
                s0 += w; s1 += w * tx; s2 += w * tx * tx
                hi++
            }
            while (lo < hi && ts[lo] < winLo) {
                val w = ns[lo].toDouble(); val tx = ts[lo] - origin
                s0 -= w; s1 -= w * tx; s2 -= w * tx * tx
                lo++
            }
            if (lo == hi) { s0 = 0.0; s1 = 0.0; s2 = 0.0 }

            // --- Step events: ADMIT first, then EVICT ---
            // Admit when t ≥ t_j − h  ↔  t_j − h ≤ t  ↔  t_j ≤ t + h
            while (stepAdmit < stepCount && stepTs[stepAdmit] - scale <= t) {
                val w = stepWs[stepAdmit]; val tj = stepTs[stepAdmit] - origin
                tk0 += w; tk1 += w * tj; tk2 += w * tj * tj; tk3 += w * tj * tj * tj
                stepAdmit++
            }
            // Evict when t ≥ t_j + h  ↔  t_j + h ≤ t
            while (stepEvict < stepAdmit && stepTs[stepEvict] + scale <= t) {
                val w = stepWs[stepEvict]; val tj = stepTs[stepEvict] - origin
                stepDone += w
                tk0 -= w; tk1 -= w * tj; tk2 -= w * tj * tj; tk3 -= w * tj * tj * tj
                stepEvict++
            }
            if (stepEvict == stepAdmit) { tk0 = 0.0; tk1 = 0.0; tk2 = 0.0; tk3 = 0.0 }

            val tRel = t - origin   // τ = t − origin  (numerical stability offset)

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
            // Clamping t prevents the denominator from collapsing to 0 when querying outside bounds.
            // Outside the measurement range, don't allow the effective exposure to shrink further; use the boundary value.
            val tClamped   = t.coerceIn(tStart, tEnd)
            val uLo        = maxOf(-1.0, (tClamped - tEnd)   * invScale)
            val uHi        = minOf( 1.0, (tClamped - tStart) * invScale)
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

    /** First index with value ≥ [time], within the occupied prefix of [times]. */
    private fun lowerBound(times: DoubleArray, count: Int, time: Double): Int {
        var lo = 0; var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index with value > [time], within the occupied prefix of [times]. */
    private fun upperBound(times: DoubleArray, count: Int, time: Double): Int {
        var lo = 0; var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Density contributed by the first [count] completed steps. Only the invalid
     * suffix is rebuilt, and only as far as this query needs. Insertion, merging,
     * and pruning invalidate the prefix from the earliest changed index onward.
     */
    private fun completedStepWeight(count: Int): Double {
        if (count == 0) return 0.0
        if (stepPrefix.size < stepCount) stepPrefix = stepPrefix.copyOf(stepTs.size)
        while (stepPrefixValid < count) {
            val i = stepPrefixValid
            stepPrefix[i] = stepWs[i] + if (i > 0) stepPrefix[i - 1] else 0.0
            stepPrefixValid++
        }
        return stepPrefix[count - 1]
    }

    /**
     * Immutable interval-overlap index, built only when adaptive KDE needs it.
     * Intervals are sorted by start; a segment tree stores the maximum end in
     * each subtree. This finds spanning intervals even when both endpoints and
     * their midpoint lie outside the requested range. Reused across plot pans.
     */
    private class IntervalIndex(intervals: List<SampleInterval>) {
        private val samples = intervals.sortedBy { it.start }
        private val leafCount: Int
        private val maxEnds: DoubleArray

        init {
            var capacity = 1
            while (capacity < samples.size) capacity *= 2
            leafCount = capacity
            maxEnds = DoubleArray(2 * capacity) { Double.NEGATIVE_INFINITY }
            for (i in samples.indices) maxEnds[capacity + i] = samples[i].end
            for (i in capacity - 1 downTo 1) maxEnds[i] = maxOf(maxEnds[2 * i], maxEnds[2 * i + 1])
        }

        fun addOverlapping(left: Double, right: Double, scale: Double, out: ArrayList<AdaptiveSample>) {
            collect(1, 0, leafCount, left, right, scale, out)
        }

        private fun collect(
            node: Int, from: Int, until: Int, left: Double, right: Double,
            scale: Double, out: ArrayList<AdaptiveSample>
        ) {
            if (from >= samples.size || samples[from].start > right || maxEnds[node] < left) return
            if (until - from == 1) {
                val sample = samples[from]
                out.add(AdaptiveSample(sample.start, sample.end, sample.density, scale))
                return
            }
            val mid = (from + until) ushr 1
            collect(2 * node, from, mid, left, right, scale, out)
            collect(2 * node + 1, mid, until, left, right, scale, out)
        }
    }

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
        stepPrefixValid = minOf(stepPrefixValid, i)

// Merge with a coincident neighbour if timestamps are within STEP_MERGE_EPSILON.
//
// Exit A (i > 0, stopped on left-neighbour condition):
//   stepTs[i+1] was the last element displaced by the bubble, so
//   stepTs[i+1] > stepTs[i] strictly. Only the LEFT neighbour can be within epsilon.
//
// Exit B (i == 0, loop ran off the left end):
//   No left neighbour exists. The element now at index 1 is the former index-0
//   element and may be within epsilon — check the RIGHT neighbour instead.
        if (i > 0 && stepTs[i] - stepTs[i - 1] <= STEP_MERGE_EPSILON) {
            stepPrefixValid = minOf(stepPrefixValid, i - 1)
            stepWs[i - 1] += stepWs[i]
            removeStepAt(i)
            i--
            if (kotlin.math.abs(stepWs[i]) < STEP_WEIGHT_EPSILON) {
                removeStepAt(i)
            }
        } else if (i == 0 && stepCount > 1 && stepTs[1] - stepTs[0] <= STEP_MERGE_EPSILON) {
            stepWs[0] += stepWs[1]
            removeStepAt(1)
            if (kotlin.math.abs(stepWs[0]) < STEP_WEIGHT_EPSILON) {
                removeStepAt(0)
            }
        }
    }

    /**
     * Removes the step event at [index] by shifting subsequent entries one place left.
     * O(stepCount − index); O(1) for the common case where [index] is near the end.
     */
    private fun removeStepAt(index: Int) {
        stepPrefixValid = minOf(stepPrefixValid, index)
        System.arraycopy(stepTs, index + 1, stepTs, index, stepCount - index - 1)
        System.arraycopy(stepWs, index + 1, stepWs, index, stepCount - index - 1)
        stepCount--
    }


    private fun pruneTimedOut(referenceTime: Double) {
        if (timeout <= 0.0 || referenceTime.isNaN()) return
        val minTime = referenceTime - timeout
        var keepFrom = 0
        while (keepFrom < size && ts[keepFrom] < minTime) keepFrom++
        if (keepFrom > 0) {
            System.arraycopy(ts, keepFrom, ts, 0, size - keepFrom)
            System.arraycopy(ns, keepFrom, ns, 0, size - keepFrom)
            size -= keepFrom
        }
        val stepKeepFrom = removableTimedOutStepPrefix(minTime)
        if (stepKeepFrom > 0) {
            // Keep adaptive intervals consistent with the retained step signal.
            // A removable zero-sum prefix ends only after all its intervals close.
            val removedThrough = stepTs[stepKeepFrom - 1] + STEP_MERGE_EPSILON
            intervals.removeAll { it.end <= removedThrough }
            intervalIndex = null
            stepPrefixValid = 0
            System.arraycopy(stepTs, stepKeepFrom, stepTs, 0, stepCount - stepKeepFrom)
            System.arraycopy(stepWs, stepKeepFrom, stepWs, 0, stepCount - stepKeepFrom)
            stepCount -= stepKeepFrom
        }
        recomputeDataBounds()
    }


    /**
     * Returns the length of the oldest timed-out step-event prefix that can be
     * safely dropped without changing the remaining rectangular interval signal.
     *
     * Step events are +/- changes to a piecewise-constant density. Dropping only
     * complete zero-sum batches preserves the density just after the retained
     * prefix; deleting an unmatched rising/falling edge would shift every later
     * interval contribution.
     */
    private fun removableTimedOutStepPrefix(minTime: Double): Int {
        var runningWeight = 0.0
        var removableCount = 0
        var index = 0
        while (index < stepCount && stepTs[index] < minTime) {
            runningWeight += stepWs[index]
            index++
            if (kotlin.math.abs(runningWeight) < STEP_WEIGHT_EPSILON) {
                removableCount = index
            }
        }
        return removableCount
    }

    private fun recomputeDataBounds() {
        dataTStart = Double.MAX_VALUE
        dataTEnd = Double.NEGATIVE_INFINITY
        for (i in 0 until size) {
            if (ts[i] < dataTStart) dataTStart = ts[i]
            if (ts[i] > dataTEnd) dataTEnd = ts[i]
        }
        for (i in 0 until stepCount) {
            if (stepTs[i] < dataTStart) dataTStart = stepTs[i]
            if (stepTs[i] > dataTEnd) dataTEnd = stepTs[i]
        }
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
