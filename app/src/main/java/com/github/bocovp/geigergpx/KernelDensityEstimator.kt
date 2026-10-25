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

    // Observed intervals include zero-count windows and survive source pruning.
    private var observedTStart = Double.MAX_VALUE
    private var observedTEnd   = Double.NEGATIVE_INFINITY

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
        other.observedTStart = observedTStart
        other.observedTEnd = observedTEnd
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
        observedTStart = Double.MAX_VALUE
        observedTEnd   = Double.NEGATIVE_INFINITY
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
     * Zero-count intervals still extend the observed time range.
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
        if (counts < 0) return
        val safeDuration = durationSeconds.coerceAtLeast(0.0)

        if (safeDuration == 0.0) {
            // Degenerate: treat as a single point event.
            addPoint(intervalStartSeconds, counts)
            return
        }

        val intervalEnd = intervalStartSeconds + safeDuration
        observedTStart = minOf(observedTStart, intervalStartSeconds)
        observedTEnd = maxOf(observedTEnd, intervalEnd)
        dataTStart = minOf(dataTStart, intervalStartSeconds)
        dataTEnd = maxOf(dataTEnd, intervalEnd)
        if (counts == 0) {
            pruneTimedOut(intervalEnd)
            return
        }

        val density     = counts.toDouble() / safeDuration   // [cps]

        intervals.add(SampleInterval(intervalStartSeconds, intervalEnd, density))
        intervalIndex = null
        addStepEvent(intervalStartSeconds, +density)
        addStepEvent(intervalEnd,          -density)

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
     * Estimates dose rate and approximate 95% effective-count confidence bounds at
     * each query point in [t2s] (sorted ascending). See companion TeX document for
     * the derivation of T_eff, n_eff, and the chi² interpolation.
     *
     * @return Triple(doseRate [µSv/h], lowerBound [µSv/h], upperBound [µSv/h])
     */
    fun getConfidenceIntervals(t2s: DoubleArray,
                               scale: Double,
                               tEndOverride: Double? = null): Triple<DoubleArray, DoubleArray, DoubleArray> {
        if (dataTStart > dataTEnd)
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
            val tClamped   = t.coerceIn(effectiveTStart, effectiveTEnd)
            //Outside the measurement range, don't allow the effective exposure to shrink further; use the boundary value.

            val uLo        = maxOf(-1.0, (tClamped - effectiveTEnd   ) * invScale)
            val uHi        = minOf( 1.0, (tClamped - effectiveTStart ) * invScale)
            val km         = if (size == 0 && stepCount == 0) epanechnikovIntegral(uLo, uHi) else bufKernelMasses[i]
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
    // Adaptive KDE — reflected pilot, then one reflected bandwidth per source.
    // The original fixed-bandwidth convolution below is kept separate.
    // -------------------------------------------------------------------------

    /**
     * Estimates adaptive dose rate at sorted query times in the observation range.
     * The reflected pilot selects h_i = scale · 0.1 / pilotDose, clamped to
     * [scale/10, scale]. Intervals use their midpoint only to select bandwidth;
     * their counts are still integrated over the whole original interval.
     */
    fun estimateAdaptiveDoseRate(t2s: DoubleArray,
                                 scale: Double,
                                 tEndOverride: Double? = null): DoubleArray {
        val cps = DoubleArray(t2s.size)
        estimateAdaptiveDoseRateHelper(t2s, scale, cps, null, tEndOverride)
        for (i in cps.indices) cps[i] *= inverseSensitivity
        return cps
    }

    /**
     * Approximate 95% confidence bounds from effective Poisson counts.
     * Originals and their mirrors share the same count: add their kernels
     * before squaring. The pilot uncertainty and smoothing bias are not included.
     * An exposure cap at the background scale keeps sparse-tail bounds finite.
     */
    fun getAdaptiveConfidenceIntervals(t2s: DoubleArray,
                                       scale: Double,
                                       tEndOverride: Double? = null): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val cps = DoubleArray(t2s.size)
        val variance = DoubleArray(t2s.size)
        val mean = DoubleArray(t2s.size)
        val low = DoubleArray(t2s.size)
        val high = DoubleArray(t2s.size)
        estimateAdaptiveDoseRateHelper(t2s, scale, cps, variance, tEndOverride)
        val end = maxOf(dataTEnd, tEndOverride ?: dataTEnd)
        if (dataTStart >= end) return Triple(mean, low, high)

        val from = lowerBound(t2s, t2s.size, dataTStart)
        val until = upperBound(t2s, t2s.size, end)
        if (from == until) return Triple(mean, low, high)
        val times = DoubleArray(until - from) { t2s[from + it] - dataTStart }
        val length = end - dataTStart
        val backgroundVariance = DoubleArray(times.size)
        reflectedEstimate(times, listOf(ReflectedSource(0.0, length, 1.0,
            ReflectedKernel(length, scale))), DoubleArray(times.size), backgroundVariance, scale)

        for (j in times.indices) {
            val i = from + j
            val backgroundExposure = 1.0 / backgroundVariance[j]
            val exposure = if (cps[i] > 0.0 && variance[i] > 0.0) {
                minOf(cps[i] / variance[i], backgroundExposure)
            } else backgroundExposure
            val effectiveCount = (cps[i] * exposure).coerceAtLeast(0.0)
            val n = floor(effectiveCount).toInt()
            val fraction = effectiveCount - n
            val chiL = ci.chi2L(n) + fraction * (ci.chi2L(n + 1) - ci.chi2L(n))
            val chiR = ci.chi2R(n + 1) + fraction * (ci.chi2R(n + 2) - ci.chi2R(n + 1))
            val conversion = 0.5 * inverseSensitivity / exposure
            mean[i] = cps[i] * inverseSensitivity
            low[i] = chiL * conversion
            high[i] = chiR * conversion
        }
        return Triple(mean, low, high)
    }

    /** Original point counts or interval density; mirrors never become new records. */
    private data class AdaptiveSample(
        val start: Double, val end: Double, val weight: Double
    ) {
        val midpoint = start + (end - start) / 2.0
    }

    private fun adaptiveSources(left: Double, right: Double, scale: Double): List<AdaptiveSample> {
        val from = lowerBound(ts, size, left - scale)
        val endIndex = upperBound(ts, size, right + scale)
        val sources = ArrayList<AdaptiveSample>(endIndex - from)
        for (i in from until endIndex) sources.add(AdaptiveSample(ts[i], ts[i], ns[i].toDouble()))
        if (intervals.isNotEmpty()) {
            val index = intervalIndex ?: IntervalIndex(intervals).also { intervalIndex = it }
            index.addOverlapping(left - scale, right + scale, sources)
        }
        return sources
    }

    private fun estimateAdaptiveDoseRateHelper(
        t2s: DoubleArray, scale: Double,
        outCps: DoubleArray, outVariance: DoubleArray?, tEndOverride: Double? = null
    ) {
        require(scale.isFinite() && scale > 0.0) { "Scale must be finite and positive" }
        require(tEndOverride == null || tEndOverride.isFinite()) { "End time must be finite" }
        for (i in t2s.indices) {
            require(t2s[i].isFinite() && (i == 0 || t2s[i] >= t2s[i - 1])) {
                "Query times must be finite and sorted ascending"
            }
        }
        val end = maxOf(dataTEnd, tEndOverride ?: dataTEnd)
        if (t2s.isEmpty() || dataTStart >= end) return
        val from = lowerBound(t2s, t2s.size, dataTStart)
        val until = upperBound(t2s, t2s.size, end)
        if (from == until) return
        val sources = adaptiveSources(t2s[from], t2s[until - 1], scale).sortedBy { it.midpoint }
        if (sources.isEmpty()) return
        val length = end - dataTStart
        val background = ReflectedKernel(length, scale)
        val pilotTimes = DoubleArray(sources.size) { sources[it].midpoint - dataTStart }
        val pilot = DoubleArray(sources.size)
        // Long intervals retain their original midpoint, even far outside the
        // final query window. Select the pilot's own contributors around it.
        val pilotSources = adaptiveSources(sources.first().midpoint, sources.last().midpoint, scale)
            .map { ReflectedSource(it.start - dataTStart, it.end - dataTStart, it.weight, background) }
        reflectedEstimate(pilotTimes, pilotSources, pilot, null, scale)
        val minimum by lazy { ReflectedKernel(length, scale / 10.0) }
        val selected = sources.mapIndexed { i, source ->
            val dose = pilot[i] * inverseSensitivity
            val h = if (dose > 0.0) (scale * ADAPTIVE_BACKGROUND_DOSE / dose)
                .coerceIn(scale / 10.0, scale) else scale
            val kernel = when (h) {
                scale -> background
                scale / 10.0 -> minimum
                else -> ReflectedKernel(length, h)
            }
            ReflectedSource(source.start - dataTStart, source.end - dataTStart, source.weight, kernel)
        }
        val times = DoubleArray(until - from) { t2s[from + it] - dataTStart }
        val cps = DoubleArray(times.size)
        val variance = outVariance?.let { DoubleArray(times.size) }
        reflectedEstimate(times, selected, cps, variance, scale)
        cps.copyInto(outCps, from)
        if (variance != null) variance.copyInto(outVariance!!, from)
    }

    private data class ReflectedSource(
        val start: Double, val end: Double, val weight: Double, val kernel: ReflectedKernel
    )

    /** Cubic mean and quintic variance, in powers of time from a nearby origin. */
    private class ReflectedPolynomial {
        var m0 = 0.0; var m1 = 0.0; var m2 = 0.0; var m3 = 0.0
        var v0 = 0.0; var v1 = 0.0; var v2 = 0.0; var v3 = 0.0; var v4 = 0.0; var v5 = 0.0
        val constant get() = m1 == 0.0 && m2 == 0.0 && m3 == 0.0 &&
            v1 == 0.0 && v2 == 0.0 && v3 == 0.0 && v4 == 0.0 && v5 == 0.0

        fun mean(x: Double) = ((m3 * x + m2) * x + m1) * x + m0
        fun variance(x: Double) = ((((v5 * x + v4) * x + v3) * x + v2) * x + v1) * x + v0

        fun clear() {
            m0 = 0.0; m1 = 0.0; m2 = 0.0; m3 = 0.0
            v0 = 0.0; v1 = 0.0; v2 = 0.0; v3 = 0.0; v4 = 0.0; v5 = 0.0
        }

        /** Add/remove a piece after translating its coefficients to this origin. */
        fun add(p: ReflectedPolynomial, sign: Double, x: Double) {
            m0 += sign * p.mean(x)
            m1 += sign * ((3.0 * p.m3 * x + 2.0 * p.m2) * x + p.m1)
            m2 += sign * (p.m2 + 3.0 * p.m3 * x)
            m3 += sign * p.m3
            v0 += sign * p.variance(x)
            v1 += sign * ((((5.0 * p.v5 * x + 4.0 * p.v4) * x + 3.0 * p.v3) * x + 2.0 * p.v2) * x + p.v1)
            v2 += sign * (((10.0 * p.v5 * x + 6.0 * p.v4) * x + 3.0 * p.v3) * x + p.v2)
            v3 += sign * ((10.0 * p.v5 * x + 4.0 * p.v4) * x + p.v3)
            v4 += sign * (p.v4 + 5.0 * p.v5 * x)
            v5 += sign * p.v5
        }

        /** Integral of one quadratic node and its square, times an affine width. */
        fun node(p0: Double, p1: Double, p2: Double, w0: Double, w1: Double, withVariance: Boolean) {
            m0 += w0 * p0
            m1 += w0 * p1 + w1 * p0
            m2 += w0 * p2 + w1 * p1
            m3 += w1 * p2
            if (!withVariance) return
            val a0 = p0 * p0; val a1 = 2.0 * p0 * p1
            val a2 = p1 * p1 + 2.0 * p0 * p2; val a3 = 2.0 * p1 * p2; val a4 = p2 * p2
            v0 += w0 * a0
            v1 += w0 * a1 + w1 * a0
            v2 += w0 * a2 + w1 * a1
            v3 += w0 * a3 + w1 * a2
            v4 += w0 * a4 + w1 * a3
            v5 += w1 * a4
        }
    }

    /** An integration endpoint s(t) = offset + slope*t; slopes are -1, 0 or 1. */
    private data class ReflectedLimit(val offset: Double, val slope: Double = 0.0) {
        fun at(t: Double) = offset + slope * t
    }

    /**
     * Three-point Gauss integration is exact for the quadratic mean and quartic
     * squared kernel on each cell. Its affine limits produce cubic/quintic
     * coefficients. Sum original and reflected kernels BEFORE squaring so all
     * mirror covariance terms are included automatically.
     */
    private fun reflectedPolynomial(source: ReflectedSource, t: Double, withVariance: Boolean): ReflectedPolynomial {
        val result = ReflectedPolynomial()
        fun node(s: Double, slope: Double, w0: Double, w1: Double) {
            val left = source.kernel.segmentAt(t - s)
            val right = source.kernel.segmentAt(t + s)
            val dl = 1.0 - slope; val dr = 1.0 + slope
            result.node(left.value(t - s) + right.value(t + s),
                left.derivative(t - s) * dl + right.derivative(t + s) * dr,
                left.c2 * dl * dl + right.c2 * dr * dr, w0, w1, withVariance)
        }
        if (source.start == source.end) {
            node(source.start, 0.0, source.weight, 0.0)
            return result
        }
        val limits = arrayListOf(ReflectedLimit(source.start), ReflectedLimit(source.end))
        for (c in source.kernel.breakpoints) {
            if (t - c > source.start && t - c < source.end) limits.add(ReflectedLimit(-c, 1.0))
            if (c - t > source.start && c - t < source.end) limits.add(ReflectedLimit(c, -1.0))
        }
        limits.sortBy { it.at(t) }
        for (i in 1 until limits.size) {
            val lo = limits[i - 1]; val hi = limits[i]
            val half = (hi.at(t) - lo.at(t)) / 2.0
            if (half <= 0.0) continue
            val middle = lo.at(t) + half
            val halfSlope = (hi.slope - lo.slope) / 2.0
            val middleSlope = (hi.slope + lo.slope) / 2.0
            val offset = kotlin.math.sqrt(3.0 / 5.0)
            node(middle, middleSlope, source.weight * half * 8.0 / 9.0, source.weight * halfSlope * 8.0 / 9.0)
            for (sign in -1..1 step 2) {
                node(middle + sign * offset * half, middleSlope + sign * offset * halfSlope,
                    source.weight * half * 5.0 / 9.0, source.weight * halfSlope * 5.0 / 9.0)
            }
        }
        return result
    }

    private data class ReflectedPiece(
        val source: ReflectedSource, val beginning: Double, val ending: Double,
        val origin: Double, val polynomial: ReflectedPolynomial,
        val meanBound: Double, val varianceBound: Double
    )

    private fun reflectedPieces(source: ReflectedSource, left: Double, right: Double,
                                withVariance: Boolean): List<ReflectedPiece> {
        val length = source.kernel.length
        val cuts = arrayListOf(0.0, length)
        fun cut(t: Double) { if (t > 0.0 && t < length) cuts.add(t) }
        for (c in source.kernel.breakpoints) {
            cut(source.start + c); cut(source.end + c)
            cut(c - source.start); cut(c - source.end)
            if (source.start != source.end) {
                for (d in source.kernel.breakpoints) {
                    val s = (d - c) / 2.0
                    if (s > source.start && s < source.end) cut((c + d) / 2.0)
                }
            }
        }
        val sorted = cuts.distinct().sorted()
        val pieces = ArrayList<ReflectedPiece>()
        val peak = source.kernel.peak
        val bound = if (source.start == source.end) source.weight * peak else
            source.weight * minOf(1.0, (source.end - source.start) * peak)
        for (i in 1 until sorted.size) {
            val start = sorted[i - 1]
            val end = if (sorted[i] == length) Double.POSITIVE_INFINITY else sorted[i]
            if (end <= left || start > right) continue
            val origin = start + (sorted[i] - start) / 2.0
            val polynomial = reflectedPolynomial(source, origin, withVariance)
            if (polynomial.m0 <= 0.0) continue
            pieces.add(ReflectedPiece(source, start, end, origin, polynomial, bound, bound * peak))
        }
        return pieces
    }

    /** One event sweep for both pilot and final pass, including observation edges. */
    private fun reflectedEstimate(times: DoubleArray, sources: List<ReflectedSource>,
                                  mean: DoubleArray, variance: DoubleArray?, scale: Double) {
        if (times.isEmpty()) return
        val pieces = sources.flatMap { reflectedPieces(it, times.first(), times.last(), variance != null) }
        val beginnings = pieces.sortedBy { it.beginning }
        val endings = pieces.sortedBy { it.ending }
        val active = LinkedHashSet<ReflectedPiece>()
        val dynamic = LinkedHashSet<ReflectedPiece>()
        val sum = ReflectedPolynomial()
        var constantMean = 0.0; var constantVariance = 0.0
        var constantCount = 0
        var constantMeanError = 0.0; var constantVarianceError = 0.0
        var dynamicMeanError = 0.0; var dynamicVarianceError = 0.0
        var meanBound = 0.0; var varianceBound = 0.0
        var admit = 0; var evict = 0
        var origin = times.first()
        fun update(piece: ReflectedPiece, sign: Double) {
            if (piece.polynomial.constant) {
                constantMean += sign * piece.polynomial.m0
                constantVariance += sign * piece.polynomial.v0
                constantCount += if (sign > 0.0) 1 else -1
                constantMeanError += 8e-15 * piece.meanBound
                constantVarianceError += 8e-15 * piece.varianceBound
                if (constantCount == 0) {
                    constantMean = 0.0; constantVariance = 0.0
                    constantMeanError = 0.0; constantVarianceError = 0.0
                }
            } else {
                sum.add(piece.polynomial, sign, origin - piece.origin)
                // Retain removal history until rebasing: a large departed
                // source can leave roundoff larger than the remaining signal.
                dynamicMeanError += 8e-15 * piece.meanBound
                dynamicVarianceError += 8e-15 * piece.varianceBound
            }
            meanBound += sign * piece.meanBound
            varianceBound += sign * piece.varianceBound
        }
        for (j in times.indices) {
            val t = times[j]
            while (evict < endings.size && endings[evict].ending <= t) {
                val piece = endings[evict++]
                if (active.remove(piece)) { update(piece, -1.0); dynamic.remove(piece) }
            }
            if (dynamic.isEmpty() || t - origin >= scale / 10.0) {
                origin = t
                sum.clear()
                dynamicMeanError = 0.0; dynamicVarianceError = 0.0
                // Long constant plateaus never need rebuilding.
                for (piece in dynamic) {
                    sum.add(piece.polynomial, 1.0, origin - piece.origin)
                    dynamicMeanError += 8e-15 * piece.meanBound
                    dynamicVarianceError += 8e-15 * piece.varianceBound
                }
            }
            if (active.isEmpty()) {
                constantMean = 0.0; constantVariance = 0.0; meanBound = 0.0; varianceBound = 0.0
            }
            while (admit < beginnings.size && beginnings[admit].beginning <= t) {
                val piece = beginnings[admit++]
                if (piece.ending <= t) continue
                active.add(piece)
                if (!piece.polynomial.constant) dynamic.add(piece)
                update(piece, 1.0)
            }
            if (active.isEmpty()) continue
            var m = sum.mean(t - origin) + constantMean
            var v = sum.variance(t - origin) + constantVariance
            val meanTolerance = maxOf(1e-6 * meanBound, 1e7 * (constantMeanError + dynamicMeanError))
            val varianceTolerance = maxOf(1e-6 * varianceBound, 1e7 * (constantVarianceError + dynamicVarianceError))
            // In vanishing tails, use the same exact integrals at this query to
            // avoid subtracting nearly equal polynomial terms. Ordinary queries
            // require only the accumulated scalar coefficients.
            if (!m.isFinite() || m <= meanTolerance ||
                (variance != null && (!v.isFinite() || v <= varianceTolerance))) {
                m = 0.0; v = 0.0
                for (piece in active) {
                    val direct = reflectedPolynomial(piece.source, t, variance != null)
                    m += direct.m0; v += direct.v0
                }
            }
            mean[j] = m.coerceAtLeast(0.0)
            if (variance != null) variance[j] = v.coerceAtLeast(0.0)
        }
    }

    /**
     * Periodic kernel P(z) = sum_k K_h(z + 2kL). Reflection on [0,L]
     * is P(t-s) + P(t+s). Only the offsets [-L,2L] are needed.
     *
     * There are at most two support boundaries per period. Summing an
     * arithmetic progression of offsets avoids creating h/L mirror copies
     * when the bandwidth exceeds the duration of a short recording.
     */
    private class ReflectedKernel(val length: Double, scale: Double) {
        data class Segment(
            val start: Double, val end: Double,
            val shift: Double, val inverseScale: Double, val amplitude: Double, val spread: Double
        ) {
            val c2 = -amplitude * inverseScale * inverseScale
            fun value(offset: Double): Double {
                val u = (offset + shift) * inverseScale
                return (amplitude * ((1.0 - u) * (1.0 + u) - spread)).coerceAtLeast(0.0)
            }
            fun derivative(offset: Double) = -2.0 * amplitude * (offset + shift) * inverseScale * inverseScale
        }

        val peak: Double
        val breakpoints: List<Double>
        val segments: List<Segment>

        init {
            val period = 2.0 * length
            val remainder = scale % period
            val edges = arrayListOf(-length, period)
            for (edge in doubleArrayOf(remainder, -remainder)) {
                for (shift in -1..1) {
                    val z = edge + shift * period
                    if (z > -length && z < period) edges.add(z)
                }
            }
            breakpoints = edges.distinct().sorted()
            val inverseScale = 1.0 / scale
            val step = period * inverseScale
            segments = breakpoints.zipWithNext { start, end ->
                val origin = start + (end - start) / 2.0
                val first = kotlin.math.ceil((-scale - origin) / period)
                val last = kotlin.math.floor((scale - origin) / period)
                val count = (last - first + 1.0).coerceAtLeast(0.0)

                // For equally spaced offsets, mean square equals centroid²
                // plus period²(count²-1)/12. Compute this in units of h.
                val span = count * step
                val spread = (span * span - step * step) / 12.0
                val amplitude = 0.75 * count * inverseScale
                Segment(start, end, (first + last) * period / 2.0, inverseScale, amplitude,
                    if (count == 0.0) 0.0 else spread)
            }
            peak = 2.0 * segments.maxOf { it.value((-it.shift).coerceIn(it.start, it.end)) }
        }

        fun segmentAt(offset: Double): Segment {
            var index = 0
            while (index < segments.lastIndex && offset >= segments[index].end) index++
            return segments[index]
        }
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

        fun addOverlapping(left: Double, right: Double, out: ArrayList<AdaptiveSample>) {
            collect(1, 0, leafCount, left, right, out)
        }

        private fun collect(
            node: Int, from: Int, until: Int, left: Double, right: Double,
            out: ArrayList<AdaptiveSample>
        ) {
            if (from >= samples.size || samples[from].start > right || maxEnds[node] < left) return
            if (until - from == 1) {
                val sample = samples[from]
                out.add(AdaptiveSample(sample.start, sample.end, sample.density))
                return
            }
            val mid = (from + until) ushr 1
            collect(2 * node, from, mid, left, right, out)
            collect(2 * node + 1, mid, until, left, right, out)
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
        observedTStart = maxOf(observedTStart, minTime)
        if (observedTStart > observedTEnd) {
            observedTStart = Double.MAX_VALUE
            observedTEnd = Double.NEGATIVE_INFINITY
        }
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
        dataTStart = observedTStart
        dataTEnd = observedTEnd
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
