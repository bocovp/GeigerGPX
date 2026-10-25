package com.github.bocovp.geigergpx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class KernelDensityEstimatorTest {
    private data class Sample(val start: Double, val duration: Double, val counts: Int) {
        val end: Double get() = start + duration
        val center: Double get() = start + duration / 2.0
    }

    private fun estimator(samples: List<Sample>, sensitivity: Double): KernelDensityEstimator =
        KernelDensityEstimator(sensitivity).apply {
            for (sample in samples) {
                if (sample.duration == 0.0) addPoint(sample.start, sample.counts)
                else addSampleInterval(sample.start, sample.duration, sample.counts)
            }
        }

    private fun kernel(offset: Double, scale: Double): Double {
        val u = offset / scale
        return if (abs(u) >= 1.0) 0.0 else 0.75 / scale * (1.0 - u * u)
    }

    // Gaussian quadrature integrates these low-degree polynomials exactly, with
    // an independent numerical formulation that also resolves very short tails.
    private fun mass(lo: Double, hi: Double): Double {
        val left = lo.coerceIn(-1.0, 1.0)
        val right = hi.coerceIn(-1.0, 1.0)
        if (right <= left) return 0.0
        val halfWidth = (right - left) / 2.0
        val midpoint = left + halfWidth
        val offset = halfWidth / sqrt(3.0)
        fun shape(u: Double) = (1.0 - u) * (1.0 + u)
        return 0.75 * halfWidth * (shape(midpoint - offset) + shape(midpoint + offset))
    }

    private fun squaredMass(lo: Double, hi: Double): Double {
        val left = lo.coerceIn(-1.0, 1.0)
        val right = hi.coerceIn(-1.0, 1.0)
        if (right <= left) return 0.0
        val halfWidth = (right - left) / 2.0
        val midpoint = left + halfWidth
        val offset = halfWidth * sqrt(3.0 / 5.0)
        fun square(u: Double): Double {
            val shape = (1.0 - u) * (1.0 + u)
            return shape * shape
        }
        return 0.5625 * halfWidth * (5.0 / 9.0 * (square(midpoint - offset) + square(midpoint + offset)) +
            8.0 / 9.0 * square(midpoint))
    }

    /** Direct convolution over the original records, including the global extent. */
    private fun fixedReference(
        samples: List<Sample>, sensitivity: Double, queries: DoubleArray,
        scale: Double, endOverride: Double? = null
    ): DoubleArray {
        val first = samples.minOf { it.start }
        val last = max(samples.maxOf { it.end }, endOverride ?: Double.NEGATIVE_INFINITY)
        return DoubleArray(queries.size) { index ->
            val t = queries[index]
            val boundaryT = t.coerceIn(first, last)
            val boundaryMass = mass((boundaryT - last) / scale, (boundaryT - first) / scale)
            val sum = samples.sumOf { sample ->
                if (sample.duration == 0.0) sample.counts * kernel(t - sample.start, scale)
                else sample.counts / sample.duration * mass((t - sample.end) / scale, (t - sample.start) / scale)
            }
            if (boundaryMass > 1e-9) sum / boundaryMass / sensitivity else 0.0
        }
    }

    private data class Image(val direction: Double, val offset: Double)

    /** Explicit reflected images, independent of the production lattice sums. */
    private fun reflectedMoments(
        t: Double, start: Double, end: Double, scale: Double, length: Double
    ): Pair<Double, Double> {
        val period = 2.0 * length
        val images = ArrayList<Image>()
        for (direction in doubleArrayOf(1.0, -1.0)) {
            val imageLo = min(direction * start, direction * end)
            val imageHi = max(direction * start, direction * end)
            val first = ceil((t - scale - imageHi) / period).toInt()
            val last = floor((t + scale - imageLo) / period).toInt()
            for (k in first..last) images.add(Image(direction, k * period))
        }
        fun weight(s: Double): Double = images.sumOf { kernel(t - it.direction * s - it.offset, scale) }
        if (start == end) {
            val q = weight(start)
            return q to q * q
        }
        val cuts = mutableListOf(start, end)
        for (image in images) {
            for (edge in doubleArrayOf(-scale, scale)) {
                val s = (t - image.offset + edge) / image.direction
                if (s > start && s < end) cuts.add(s)
            }
        }
        cuts.sort()
        var mean = 0.0
        var variance = 0.0
        for (i in 1 until cuts.size) {
            val half = (cuts[i] - cuts[i - 1]) / 2.0
            if (half == 0.0) continue
            val midpoint = cuts[i - 1] + half
            val offset = half * sqrt(3.0 / 5.0)
            val q1 = weight(midpoint - offset)
            val q2 = weight(midpoint)
            val q3 = weight(midpoint + offset)
            // On each support segment q is quadratic and q² is quartic.
            mean += half * (5.0 * (q1 + q3) + 8.0 * q2) / 9.0
            variance += half * (5.0 * (q1 * q1 + q3 * q3) + 8.0 * q2 * q2) / 9.0
        }
        return mean to variance
    }

    private fun scales(
        samples: List<Sample>, sensitivity: Double, scale: Double, endOverride: Double? = null
    ): List<Double> {
        val first = samples.minOf { it.start }
        val last = max(samples.maxOf { it.end }, endOverride ?: Double.NEGATIVE_INFINITY)
        val length = last - first
        return samples.map { target ->
            val pilot = samples.sumOf { sample ->
                if (sample.counts == 0) 0.0 else {
                    val contribution = reflectedMoments(target.center - first, sample.start - first,
                        sample.end - first, scale, length).first
                    contribution * sample.counts / if (sample.duration > 0.0) sample.duration else 1.0
                }
            } / sensitivity
            if (pilot > 0.0) (scale * 0.1 / pilot).coerceIn(scale / 10.0, scale) else scale
        }
    }

    /** Direct reflected source sums; mirrors share their original event count. */
    private fun rawReference(
        samples: List<Sample>, sensitivity: Double, queries: DoubleArray,
        scale: Double, endOverride: Double? = null
    ): Pair<DoubleArray, DoubleArray> {
        val widths = scales(samples, sensitivity, scale, endOverride)
        val first = samples.minOf { it.start }
        val last = max(samples.maxOf { it.end }, endOverride ?: Double.NEGATIVE_INFINITY)
        val rates = DoubleArray(queries.size)
        val variances = DoubleArray(queries.size)
        for ((index, t) in queries.withIndex()) {
            if (t < first || t > last) continue
            for ((sampleIndex, sample) in samples.withIndex()) {
                if (sample.counts == 0) continue
                val (mean, variance) = reflectedMoments(t - first, sample.start - first,
                    sample.end - first, widths[sampleIndex], last - first)
                val weight = sample.counts / if (sample.duration > 0.0) sample.duration else 1.0
                rates[index] += weight * mean
                variances[index] += weight * variance
            }
        }
        return rates to variances
    }

    /** Inspect variance before the confidence-exposure cap can conceal errors. */
    private fun adaptiveRaw(
        kde: KernelDensityEstimator, queries: DoubleArray, scale: Double,
        endOverride: Double? = null
    ): Pair<DoubleArray, DoubleArray> {
        val helper = KernelDensityEstimator::class.java.getDeclaredMethod(
            "estimateAdaptiveDoseRateHelper", DoubleArray::class.java, Double::class.javaPrimitiveType,
            DoubleArray::class.java, DoubleArray::class.java, java.lang.Double::class.java
        ).apply { isAccessible = true }
        val rates = DoubleArray(queries.size)
        val variances = DoubleArray(queries.size)
        helper.invoke(kde, queries, scale, rates, variances, endOverride)
        return rates to variances
    }

    private fun reference(
        samples: List<Sample>, sensitivity: Double, queries: DoubleArray,
        scale: Double, endOverride: Double? = null
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val (rates, variances) = rawReference(samples, sensitivity, queries, scale, endOverride)
        val first = samples.minOf { it.start }
        val last = max(samples.maxOf { it.end }, endOverride ?: Double.NEGATIVE_INFINITY)
        val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)
        val means = DoubleArray(queries.size)
        val lows = DoubleArray(queries.size)
        val highs = DoubleArray(queries.size)
        for ((index, t) in queries.withIndex()) {
            if (t < first || t > last) continue
            val rate = rates[index]
            val variance = variances[index]
            val backgroundVariance = reflectedMoments(t - first, 0.0, last - first, scale, last - first).second
            val backgroundExposure = if (backgroundVariance > 0.0) 1.0 / backgroundVariance else 0.0
            val exposure = if (variance > 0.0 && rate > 0.0) {
                min(rate / variance, backgroundExposure)
            } else backgroundExposure
            val effectiveCount = rate * exposure
            val lowerCount = floor(effectiveCount).toInt()
            val fraction = effectiveCount - lowerCount
            val lowerChi = ci.chi2L(lowerCount) + fraction * (ci.chi2L(lowerCount + 1) - ci.chi2L(lowerCount))
            val upperChi = ci.chi2R(lowerCount + 1) + fraction * (ci.chi2R(lowerCount + 2) - ci.chi2R(lowerCount + 1))
            means[index] = rate / sensitivity
            if (exposure > 0.0) {
                lows[index] = lowerChi * 0.5 / exposure / sensitivity
                highs[index] = upperChi * 0.5 / exposure / sensitivity
            }
        }
        return Triple(means, lows, highs)
    }

    private fun assertCurvesEqual(
        expected: Triple<DoubleArray, DoubleArray, DoubleArray>,
        actual: Triple<DoubleArray, DoubleArray, DoubleArray>, tolerance: Double = 1e-8
    ) {
        assertArrayEquals("means", expected.first, actual.first, tolerance)
        assertArrayEquals("lower bounds", expected.second, actual.second, tolerance)
        assertArrayEquals("upper bounds", expected.third, actual.third, tolerance)
        for (i in actual.first.indices) {
            assertTrue(actual.first[i].isFinite())
            assertTrue(actual.second[i] >= 0.0)
            assertTrue(actual.second[i] <= actual.first[i])
            assertTrue(actual.third[i] >= actual.first[i])
        }
    }

    @Test
    fun backgroundDoseKeepsMaximumScaleAndConstantRate() {
        val samples = listOf(Sample(0.0, 100.0, 10))
        val kde = estimator(samples, 2.0)
        val queries = DoubleArray(101) { it.toDouble() }
        assertEquals(listOf(10.0), scales(samples, 2.0, 10.0))
        assertCurvesEqual(reference(samples, 2.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertArrayEquals(DoubleArray(queries.size) { 0.05 }, kde.estimateAdaptiveDoseRate(queries, 10.0), 1e-10)
    }

    @Test
    fun highDoseClampsAtOneTenthOfSliderScale() {
        val samples = listOf(Sample(0.0, 100.0, 10000))
        val kde = estimator(samples, 1.0)
        val queries = DoubleArray(201) { it * 0.5 }
        assertEquals(listOf(1.0), scales(samples, 1.0, 10.0))
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-6)
        assertArrayEquals(DoubleArray(queries.size) { 100.0 }, kde.estimateAdaptiveDoseRate(queries, 10.0), 1e-8)
    }

    @Test
    fun intermediateDoseUsesInverseProportionalScale() {
        val samples = listOf(Sample(0.0, 100.0, 40))
        val kde = estimator(samples, 1.0)
        val queries = DoubleArray(221) { it * 0.5 - 5.0 }
        assertEquals(2.5, scales(samples, 1.0, 10.0).single(), 1e-10)
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun pointSupportsCanBeginAndEndInDifferentOrders() {
        val samples = listOf(
            Sample(-1.0, 0.0, 1), Sample(0.0, 0.0, 1), Sample(8.0, 0.0, 1), Sample(9.0, 0.0, 300),
            Sample(10.0, 0.0, 1), Sample(17.0, 0.0, 1), Sample(19.0, 0.0, 1), Sample(40.0, 0.0, 1)
        )
        val widths = scales(samples, 30.0, 10.0)
        val starts = samples.indices.map { samples[it].start - widths[it] }
        val ends = samples.indices.map { samples[it].end + widths[it] }
        assertTrue("fixture must reorder support starts", starts.zipWithNext().any { it.first > it.second })
        assertTrue("fixture must reorder support ends", ends.zipWithNext().any { it.first > it.second })
        val queries = DoubleArray(241) { it * 0.25 - 10.0 }
        val kde = estimator(samples.reversed(), 30.0)
        assertCurvesEqual(reference(samples, 30.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertArrayEquals(kde.getAdaptiveConfidenceIntervals(queries, 10.0).first, kde.estimateAdaptiveDoseRate(queries, 10.0), 1e-12)
    }

    @Test
    fun sparseQueriesSkipExpiredSupportsWithoutAddingTheirTails() {
        val samples = listOf(Sample(0.0, 0.0, 10), Sample(1.0, 0.0, 1), Sample(100.0, 0.0, 20), Sample(101.0, 0.0, 1))
        val queries = doubleArrayOf(-20.0, 0.0, 50.0, 100.0, 150.0)
        assertCurvesEqual(reference(samples, 5.0, queries, 10.0), estimator(samples, 5.0).getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun intervalsUseTheirMidpointsAndRetainSharedEndpointsAndOverlaps() {
        val samples = listOf(
            Sample(0.0, 10.0, 5), Sample(10.0, 10.0, 5), Sample(15.0, 18.0, 40),
            Sample(28.0, 2.0, 300), Sample(31.0, 0.0, 4), Sample(40.0, 30.0, 12)
        )
        val queries = DoubleArray(361) { it * 0.25 - 10.0 }
        assertCurvesEqual(reference(samples, 5.0, queries, 10.0), estimator(samples, 5.0).getAdaptiveConfidenceIntervals(queries, 10.0), 1e-7)
    }

    @Test
    fun intervalVarianceIntegratesSquaredKernelInsteadOfSquaringItsMean() {
        val samples = listOf(Sample(0.0, 100.0, 40))
        val queries = doubleArrayOf(50.0)
        val actual = estimator(samples, 1.0).getAdaptiveConfidenceIntervals(queries, 10.0)
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), actual)
        // Only the local kernel exposure contributes to precision, not all 100 seconds.
        val fullInterval = ConfidenceInterval(0.0, 100.0, 40, false)
        assertTrue(actual.third[0] - actual.second[0] > fullInterval.highBound - fullInterval.lowBound)
    }

    @Test
    fun endOverrideChangesPilotAndBoundaryCorrectionAndKeepsZeroCountUpperLimit() {
        val samples = listOf(Sample(0.0, 4.0, 10), Sample(8.0, 0.0, 5))
        val queries = doubleArrayOf(0.0, 4.0, 8.0, 15.0, 25.0, 35.0)
        val actual = estimator(samples, 2.0).getAdaptiveConfidenceIntervals(queries, 10.0, 30.0)
        assertCurvesEqual(reference(samples, 2.0, queries, 10.0, 30.0), actual)
        assertEquals(0.0, actual.first[4], 0.0)
        assertEquals(0.0, actual.second[4], 0.0)
        assertTrue(actual.third[4] > 0.0)
    }

    @Test
    fun epochTimestampsPreserveAdaptiveEstimate() {
        val samples = listOf(Sample(0.0, 4.0, 8), Sample(6.0, 0.0, 50), Sample(8.0, 5.0, 3), Sample(30.0, 8.0, 4))
        val queries = DoubleArray(101) { it * 0.5 - 5.0 }
        val offset = 1_800_000_000.0
        val shifted = samples.map { it.copy(start = it.start + offset) }
        assertCurvesEqual(
            estimator(samples, 3.0).getAdaptiveConfidenceIntervals(queries, 10.0),
            estimator(shifted, 3.0).getAdaptiveConfidenceIntervals(queries.map { it + offset }.toDoubleArray(), 10.0),
            1e-7
        )
    }

    @Test
    fun copyIsIndependentAndClearDiscardsAdaptiveIntervalData() {
        val samples = listOf(Sample(0.0, 10.0, 8), Sample(10.0, 10.0, 8), Sample(30.0, 0.0, 12))
        val original = estimator(samples, 3.0)
        val copy = original.copy()
        val queries = DoubleArray(81) { it * 0.5 }
        val expected = reference(samples, 3.0, queries, 10.0)
        original.addSampleInterval(40.0, 20.0, 100)
        original.clear()
        assertCurvesEqual(expected, copy.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertArrayEquals(DoubleArray(queries.size), original.estimateAdaptiveDoseRate(queries, 10.0), 0.0)
        original.addSampleInterval(0.0, 10.0, 3)
        assertCurvesEqual(
            estimator(listOf(Sample(0.0, 10.0, 3)), 3.0).getAdaptiveConfidenceIntervals(queries, 10.0),
            original.getAdaptiveConfidenceIntervals(queries, 10.0)
        )
    }

    @Test
    fun timeoutDropsCompleteIntervalsAndPointsFromBothEstimators() {
        val kde = KernelDensityEstimator(3.0).apply { timeout = 15.0 }
        kde.addSampleInterval(0.0, 4.0, 20)
        kde.addPoint(5.0, 10)
        kde.addSampleInterval(30.0, 5.0, 8)
        kde.addPoint(40.0, 2)
        val remaining = listOf(Sample(25.0, 5.0, 0), Sample(30.0, 5.0, 8), Sample(40.0, 0.0, 2))
        val queries = DoubleArray(81) { it * 0.5 + 20.0 }
        assertEquals(25.0 to 40.0, kde.timestampBounds())
        assertCurvesEqual(reference(remaining, 3.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertCurvesEqual(kde.getAdaptiveConfidenceIntervals(queries, 10.0), kde.copy().getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun emptyInputsAndZeroExtentReturnFiniteZeroArrays() {
        val empty = KernelDensityEstimator(2.0)
        val queries = doubleArrayOf(0.0, 1.0)
        val zeros = Triple(DoubleArray(2), DoubleArray(2), DoubleArray(2))
        assertCurvesEqual(zeros, empty.getAdaptiveConfidenceIntervals(queries, 10.0))
        assertEquals(0, empty.estimateAdaptiveDoseRate(doubleArrayOf(), 10.0).size)
        empty.addSampleInterval(1.0, 0.0, 2)
        assertCurvesEqual(zeros, empty.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun sensitivityUpdateAffectsPilotAndDoseConversion() {
        val samples = listOf(Sample(0.0, 20.0, 40), Sample(20.0, 20.0, 2))
        val kde = estimator(samples, 1.0)
        kde.updateSensitivity(20.0)
        val queries = DoubleArray(81) { it * 0.5 }
        assertCurvesEqual(reference(samples, 20.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun fixedSparseQueriesExcludeSkippedPointSupports() {
        val samples = listOf(
            Sample(0.0, 0.0, 1), Sample(1.0, 0.0, 4), Sample(1000.0, 0.0, 8),
            Sample(1001.0, 0.0, 2), Sample(5000.0, 0.0, 5), Sample(5001.0, 0.0, 1)
        )
        val queries = doubleArrayOf(-100.0, 0.0, 1.0, 500.0, 1000.0, 1001.0, 4500.0, 5000.0, 5010.0, 6000.0)
        val kde = estimator(samples, 3.0)
        val expected = fixedReference(samples, 3.0, queries, 10.0)
        assertArrayEquals(expected, kde.estimateDoseRate(queries, 10.0), 1e-8)
        for (i in queries.indices) {
            assertEquals("independent query at ${queries[i]}", expected[i],
                kde.estimateDoseRate(doubleArrayOf(queries[i]), 10.0).single(), 1e-8)
        }
    }

    @Test
    fun fixedRangeKeepsDensityOfStepsFarBeforeTheQuery() {
        val samples = listOf(
            Sample(0.0, 20000.0, 10000), Sample(1000.0, 5000.0, 1000),
            Sample(4000.0, 8000.0, 1600), Sample(4995.0, 20.0, 12)
        )
        val queries = DoubleArray(101) { 4980.0 + it * 0.5 }
        val expected = fixedReference(samples, 4.0, queries, 10.0)
        val kde = estimator(samples, 4.0)
        assertArrayEquals(expected, kde.estimateDoseRate(queries, 10.0), 1e-8)
        assertArrayEquals(expected, kde.getConfidenceIntervals(queries, 10.0).first, 1e-8)
        assertCurvesEqual(reference(samples, 4.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-7)
    }

    @Test
    fun longIntervalUsesDistantMidpointAndAllPilotContributors() {
        val samples = listOf(
            Sample(-1000.0, 0.0, 1), Sample(0.0, 10000.0, 1000),
            Sample(5000.0, 0.0, 1000), Sample(15000.0, 0.0, 1)
        )
        val queries = doubleArrayOf(-3.0, -1.0, 0.0, 1.0, 3.0, 6.0)
        val expected = reference(samples, 1.0, queries, 20.0)
        val withoutRemotePoint = reference(samples.filter { it.start != 5000.0 }, 1.0, queries, 20.0)
        assertTrue("remote midpoint event must affect the visible interval edge",
            abs(expected.first[3] - withoutRemotePoint.first[3]) > 0.01)
        assertCurvesEqual(expected, estimator(samples, 1.0).getAdaptiveConfidenceIntervals(queries, 20.0), 1e-7)
    }

    @Test
    fun adaptivePointPilotIncludesSourcesOutsideFinalCandidateWindow() {
        val samples = listOf(
            Sample(-100.0, 0.0, 1), Sample(8.0, 0.0, 1),
            Sample(17.0, 0.0, 100), Sample(100.0, 0.0, 1)
        )
        val queries = doubleArrayOf(-1.0, 0.0, 1.0)
        val expected = reference(samples, 5.0, queries, 10.0)
        val missingPilotNeighbor = reference(samples.filter { it.start != 17.0 }, 5.0, queries, 10.0)
        assertEquals(0.0, expected.first[1], 0.0)
        assertTrue(missingPilotNeighbor.first[1] > 0.0)
        assertCurvesEqual(expected, estimator(samples, 5.0).getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun croppedQueryRetainsGlobalMeasurementBoundsAndEndOverride() {
        val samples = listOf(
            Sample(-1000.0, 0.0, 1), Sample(0.0, 0.0, 3), Sample(3.0, 0.0, 2), Sample(1000.0, 0.0, 1)
        )
        val kde = estimator(samples, 100.0)
        val queries = doubleArrayOf(-1.0, 0.0, 1.0, 3.0, 4.0)
        assertArrayEquals(fixedReference(samples, 100.0, queries, 10.0), kde.estimateDoseRate(queries, 10.0), 1e-10)
        assertCurvesEqual(reference(samples, 100.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
        val endQueries = doubleArrayOf(999.0, 1000.0, 1001.0, 1008.0)
        assertArrayEquals(fixedReference(samples, 100.0, endQueries, 10.0, 1010.0),
            kde.estimateDoseRate(endQueries, 10.0, 1010.0), 1e-10)
        assertCurvesEqual(reference(samples, 100.0, endQueries, 10.0, 1010.0),
            kde.getAdaptiveConfidenceIntervals(endQueries, 10.0, 1010.0))
    }

    @Test
    fun rangeWithNoCandidateSourcesStillHasZeroCountConfidenceLimit() {
        val samples = listOf(Sample(-1000.0, 0.0, 1), Sample(1000.0, 0.0, 1))
        val queries = doubleArrayOf(-1.0, 0.0, 1.0)
        val kde = estimator(samples, 1.0)
        assertArrayEquals(DoubleArray(3), kde.estimateDoseRate(queries, 10.0), 0.0)
        val actual = kde.getAdaptiveConfidenceIntervals(queries, 10.0)
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), actual)
        assertTrue(actual.third.all { it > 0.0 })
    }

    @Test
    fun rangeIndexesRefreshAfterInsertionCopyClearAndPruning() {
        val samples = mutableListOf(Sample(0.0, 3000.0, 3000), Sample(900.0, 30.0, 30), Sample(950.0, 0.0, 3))
        val kde = estimator(samples, 5.0)
        val queries = DoubleArray(61) { 935.0 + it * 0.5 }
        // Warm both paths before mutating the step-prefix and interval indexes.
        kde.getConfidenceIntervals(queries, 10.0)
        kde.getAdaptiveConfidenceIntervals(queries, 10.0)
        val inserted = listOf(Sample(500.0, 2000.0, 1000), Sample(940.0, 20.0, 20))
        for (sample in inserted) kde.addSampleInterval(sample.start, sample.duration, sample.counts)
        samples.addAll(inserted)
        assertArrayEquals(fixedReference(samples, 5.0, queries, 10.0), kde.estimateDoseRate(queries, 10.0), 1e-8)
        assertCurvesEqual(reference(samples, 5.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-7)

        val copy = kde.copy()
        kde.clear()
        val replacement = listOf(Sample(900.0, 100.0, 5))
        kde.addSampleInterval(900.0, 100.0, 5)
        assertArrayEquals(fixedReference(replacement, 5.0, queries, 10.0), kde.estimateDoseRate(queries, 10.0), 1e-8)
        assertCurvesEqual(reference(replacement, 5.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-7)
        assertCurvesEqual(reference(samples, 5.0, queries, 10.0), copy.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-7)
        assertArrayEquals(fixedReference(samples, 5.0, queries, 10.0), copy.estimateDoseRate(queries, 10.0), 1e-8)

        copy.timeout = 30.0
        copy.addSampleInterval(4000.0, 20.0, 4)
        val prunedSamples = listOf(Sample(3990.0, 10.0, 0), Sample(4000.0, 20.0, 4))
        val prunedQueries = DoubleArray(41) { 4000.0 + it * 0.5 }
        assertEquals(3990.0 to 4020.0, copy.timestampBounds())
        assertArrayEquals(fixedReference(prunedSamples, 5.0, prunedQueries, 10.0),
            copy.estimateDoseRate(prunedQueries, 10.0), 1e-8)
        assertCurvesEqual(reference(prunedSamples, 5.0, prunedQueries, 10.0),
            copy.getAdaptiveConfidenceIntervals(prunedQueries, 10.0), 1e-7)
    }

    @Test
    fun cachedStepPrefixRefreshesWhenTouchingIntervalsMerge() {
        val samples = mutableListOf(Sample(0.0, 10.0, 10), Sample(20.0, 10.0, 10))
        val kde = estimator(samples, 10.0)
        val queries = doubleArrayOf(5.0, 15.0, 25.0)
        kde.estimateDoseRate(queries, 2.0)
        kde.estimateAdaptiveDoseRate(queries, 2.0)
        // Filling the gap cancels two previously cached step entries.
        samples.add(Sample(10.0, 10.0, 10))
        kde.addSampleInterval(10.0, 10.0, 10)
        assertArrayEquals(fixedReference(samples, 10.0, queries, 2.0),
            kde.estimateDoseRate(queries, 2.0), 1e-10)
        assertCurvesEqual(reference(samples, 10.0, queries, 2.0),
            kde.getAdaptiveConfidenceIntervals(queries, 2.0))
        // An out-of-order interval also moves the earliest retained step.
        samples.add(Sample(-10.0, 10.0, 10))
        kde.addSampleInterval(-10.0, 10.0, 10)
        assertArrayEquals(fixedReference(samples, 10.0, queries, 2.0),
            kde.estimateDoseRate(queries, 2.0), 1e-10)
        assertCurvesEqual(reference(samples, 10.0, queries, 2.0),
            kde.getAdaptiveConfidenceIntervals(queries, 2.0))
    }

    @Test
    fun longDenseQuerySweepRemainsAccurateAcrossMomentRebases() {
        val samples = (0 until 500).flatMap { i ->
            listOf(Sample(i.toDouble(), 0.8, 1 + i % 3), Sample(i + 0.4, 0.0, 1))
        }
        val queries = DoubleArray(2001) { it * 0.25 }
        val kde = estimator(samples, 10.0)
        assertArrayEquals(fixedReference(samples, 10.0, queries, 3.0),
            kde.estimateDoseRate(queries, 3.0), 1e-7)
        assertCurvesEqual(reference(samples, 10.0, queries, 3.0),
            kde.getAdaptiveConfidenceIntervals(queries, 3.0), 1e-7)
    }

    @Test
    fun randomizedMixedAdaptiveCurvesMatchDirectConvolutionAtEveryBreakpoint() {
        for (seed in 0 until 4) {
            val random = Random(seed)
            val samples = mutableListOf(Sample(-50.0, 0.0, 1), Sample(650.0, 0.0, 1))
            repeat(64) { index ->
                val start = random.nextDouble(0.0, 500.0)
                val duration = if (index % 3 == 0) 0.0 else random.nextDouble(0.02, 100.0)
                samples += Sample(start, duration, random.nextInt(1, 40))
            }
            for (scale in doubleArrayOf(5.0, 20.0, 80.0)) {
                val widths = scales(samples, 7.0, scale)
                val queryList = MutableList(201) { -60.0 + it * 720.0 / 200.0 }
                for (i in samples.indices) {
                    val sample = samples[i]
                    val h = widths[i]
                    for (breakpoint in doubleArrayOf(sample.start - h, sample.start + h, sample.end - h, sample.end + h)) {
                        queryList.add(breakpoint - 1e-7)
                        queryList.add(breakpoint)
                        queryList.add(breakpoint + 1e-7)
                    }
                }
                val queries = queryList.sorted().toDoubleArray()
                val kde = estimator(samples.shuffled(random), 7.0)
                assertCurvesEqual(reference(samples, 7.0, queries, scale),
                    kde.getAdaptiveConfidenceIntervals(queries, scale), 2e-7)
            }
        }
    }

    @Test
    fun adaptiveReflectionUsesEachSourceScaleOnBothEdges() {
        val samples = listOf(
            Sample(0.0, 0.0, 1), Sample(0.25, 0.0, 2), Sample(1.0, 4.0, 3),
            Sample(8.0, 0.0, 100), Sample(25.0, 20.0, 8), Sample(48.0, 1.0, 20), Sample(50.0, 0.0, 1)
        )
        val widths = scales(samples, 4.0, 20.0)
        assertTrue("fixture needs variable scales", widths.maxOrNull()!! > widths.minOrNull()!! * 1.5)
        val queries = DoubleArray(321) { -15.0 + it * 0.25 }
        assertCurvesEqual(reference(samples, 4.0, queries, 20.0),
            estimator(samples, 4.0).getAdaptiveConfidenceIntervals(queries, 20.0), 1e-7)
    }

    @Test
    fun adaptiveShortObservationExtentRepeatsReflectionsAndHonorsLiveOverride() {
        val samples = listOf(
            Sample(100.0, 0.1, 1), Sample(100.05, 0.1, 4),
            Sample(100.15, 0.0, 3), Sample(100.3, 0.0, 1)
        )
        val queries = DoubleArray(101) { 99.0 + it * 0.025 }
        val kde = estimator(samples, 200.0)
        for (endOverride in listOf(null, 100.6, 110.0)) {
            assertCurvesEqual(reference(samples, 200.0, queries, 10.0, endOverride),
                kde.getAdaptiveConfidenceIntervals(queries, 10.0, endOverride), 1e-7)
        }
    }

    @Test
    fun adaptiveBatchAndSingleQueriesAgreeAtRepeatedSupportTransitions() {
        val baseSamples = listOf(
            Sample(0.0, 100.0, 80), Sample(0.0, 0.0, 1), Sample(100.0, 0.0, 1),
            Sample(17.5, 12.0, 30), Sample(20.0, 0.0, 12),
            Sample(72.0, 18.0, 20), Sample(80.0, 0.0, 8)
        )
        for (offset in doubleArrayOf(0.0, 1_800_000_000.0)) {
            val samples = baseSamples.map { it.copy(start = it.start + offset) }
            val kde = estimator(samples, 5.0)
            for (scale in doubleArrayOf(20.0, 60.0)) {
                for (endOverride in listOf(null, offset + 115.0)) {
                    val end = endOverride ?: offset + 100.0
                    val queryList = mutableListOf(offset - scale, offset, offset + 50.0, end, end + scale)
                    for (seam in doubleArrayOf(offset + scale, end - scale)) {
                        queryList.addAll(listOf(Math.nextDown(seam), seam, seam, Math.nextUp(seam)))
                    }
                    val queries = queryList.sorted().toDoubleArray()
                    val expectedRaw = rawReference(samples, 5.0, queries, scale, endOverride)
                    val actualRaw = adaptiveRaw(kde, queries, scale, endOverride)
                    assertArrayEquals("raw mean across evaluation ranges", expectedRaw.first, actualRaw.first, 1e-8)
                    assertArrayEquals("raw variance across evaluation ranges", expectedRaw.second, actualRaw.second, 1e-8)
                    assertCurvesEqual(reference(samples, 5.0, queries, scale, endOverride),
                        kde.getAdaptiveConfidenceIntervals(queries, scale, endOverride), 1e-7)
                    val meanOnly = kde.estimateAdaptiveDoseRate(queries, scale, endOverride)
                    for (i in queries.indices) {
                        assertEquals(expectedRaw.first[i] / 5.0, meanOnly[i], 1e-8)
                        // The sweep must agree with an isolated request at the same timestamp.
                        assertEquals(meanOnly[i], kde.estimateAdaptiveDoseRate(
                            doubleArrayOf(queries[i]), scale, endOverride).single(), 1e-8)
                    }
                }
            }
        }
    }

    @Test
    fun adaptiveLongIntervalPlateausAndSparseQueriesDoNotRetainTransitionPolynomials() {
        val samples = listOf(
            Sample(-1000.0, 0.0, 1), Sample(0.0, 10000.0, 2000),
            Sample(100.0, 0.0, 30), Sample(5000.0, 0.0, 100),
            Sample(9990.0, 20.0, 40), Sample(15000.0, 0.0, 1)
        )
        val queries = doubleArrayOf(-30.0, -1.0, 0.0, 1.0, 50.0, 100.0, 500.0, 5000.0, 9900.0, 9999.0, 10000.0, 10010.0, 11000.0)
        val kde = estimator(samples, 4.0)
        val expected = reference(samples, 4.0, queries, 20.0)
        assertCurvesEqual(expected, kde.getAdaptiveConfidenceIntervals(queries, 20.0), 1e-7)
        for (i in queries.indices) {
            assertEquals(expected.first[i], kde.estimateAdaptiveDoseRate(doubleArrayOf(queries[i]), 20.0).single(), 1e-8)
        }
    }

    @Test
    fun rawAdaptiveVarianceMatchesDirectSumsAcrossIntervalPhasesAndEpochs() {
        val original = listOf(
            Sample(-1000.0, 0.0, 1), Sample(0.0, 100.0, 80),
            Sample(120.0, 0.75, 15), Sample(125.0, 0.0, 6),
            Sample(130.0, 45.0, 18), Sample(1000.0, 0.0, 1)
        )
        val baseQueries = DoubleArray(961) { -30.0 + it * 0.25 }
        for (offset in doubleArrayOf(0.0, 1_800_000_000.0)) {
            val samples = original.map { it.copy(start = it.start + offset) }
            val queries = baseQueries.map { it + offset }.toDoubleArray()
            val expected = rawReference(samples, 5.0, queries, 20.0)
            val actual = adaptiveRaw(estimator(samples, 5.0), queries, 20.0)
            assertArrayEquals("raw rate at epoch $offset", expected.first, actual.first, 1e-8)
            assertArrayEquals("raw variance at epoch $offset", expected.second, actual.second, 1e-8)
            assertTrue(actual.second.all { it >= 0.0 && it.isFinite() })
        }
    }

    @Test
    fun rawAdaptiveVarianceResolvesTinyRisingAndFallingIntervalTails() {
        val samples = listOf(Sample(-100.0, 0.0, 1), Sample(0.0, 100.0, 10000), Sample(200.0, 0.0, 1))
        val queries = doubleArrayOf(-0.999999, 100.999999)
        val expected = rawReference(samples, 1.0, queries, 10.0)
        val actual = adaptiveRaw(estimator(samples, 1.0), queries, 10.0)
        for (i in queries.indices) {
            assertTrue(expected.second[i] > 0.0)
            assertEquals(expected.first[i], actual.first[i], expected.first[i] * 1e-6)
            assertEquals(expected.second[i], actual.second[i], expected.second[i] * 1e-6)
        }
    }

    @Test
    fun roundedEpochIntervalBreakpointsPreserveRawVarianceAndMeanOnlyPath() {
        val epoch = 1_800_000_000.0
        val ulp = Math.ulp(epoch)
        val duration = 8.0 * ulp
        val samples = listOf(
            Sample(epoch - 1000.0, 0.0, 1), Sample(epoch, duration, 10000),
            Sample(epoch + 1000.0, 0.0, 1)
        )
        for (scale in doubleArrayOf(6.0, 7.0)) {
            val h = scale / 10.0
            val queries = listOf(epoch - h, epoch + h, epoch + duration - h, epoch + duration + h)
                .flatMap { edge -> (-9..9).map { edge + it * ulp } }.sorted().toDoubleArray()
            val expected = rawReference(samples, 1.0, queries, scale)
            val kde = estimator(samples, 1.0)
            val actual = adaptiveRaw(kde, queries, scale)
            val meanOnly = kde.estimateAdaptiveDoseRate(queries, scale)
            for (i in queries.indices) {
                val meanTolerance = 1e-7 * max(expected.first[i], 1e-8)
                val varianceTolerance = 1e-7 * max(expected.second[i], 1e-8)
                assertEquals("raw rate at ${queries[i] - epoch}", expected.first[i], actual.first[i], meanTolerance)
                assertEquals("raw variance at ${queries[i] - epoch}", expected.second[i], actual.second[i], varianceTolerance)
                assertEquals("mean-only rate at ${queries[i] - epoch}", expected.first[i], meanOnly[i], meanTolerance)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidScaleIsRejected() {
        KernelDensityEstimator(1.0).estimateAdaptiveDoseRate(doubleArrayOf(0.0), 0.0)
    }

    @Test(timeout = 2000)
    fun internalTransitionAtZeroUsesBoundedQuerySearch() {
        val samples = listOf(
            Sample(-100.0, 0.0, 1), Sample(-0.1, 0.2, 100), Sample(100.0, 0.0, 1)
        )
        val queries = doubleArrayOf(-0.2, -0.1, -Double.MIN_VALUE, 0.0, Double.MIN_VALUE, 0.1, 0.2)
        val kde = estimator(samples, 1.0)
        val expected = rawReference(samples, 1.0, queries, 1.0)
        val actual = adaptiveRaw(kde, queries, 1.0)
        assertArrayEquals(expected.first, actual.first, 1e-9)
        assertArrayEquals(expected.second, actual.second, 1e-8)
        assertArrayEquals(expected.first, kde.estimateAdaptiveDoseRate(queries, 1.0), 1e-9)
    }

    @Test
    fun roundedEpochPointSupportsNeverSubtractFromOtherSources() {
        val epoch = 1_800_000_000.0
        val samples = listOf(Sample(epoch - 1000.0, 2000.0, 2000), Sample(epoch, 0.0, 10000))
        val h = 0.7
        val ulp = Math.ulp(epoch)
        val queries = listOf(epoch - h, epoch + h)
            .flatMap { edge -> (-2..2).map { edge + it * ulp } }.sorted().toDoubleArray()
        val expected = rawReference(samples, 1.0, queries, 7.0)
        val kde = estimator(samples, 1.0)
        val actual = adaptiveRaw(kde, queries, 7.0)
        assertArrayEquals(expected.first, actual.first, 1e-8)
        assertArrayEquals(expected.second, actual.second, 1e-8)
        assertArrayEquals(expected.first, kde.estimateAdaptiveDoseRate(queries, 7.0), 1e-8)
    }

    @Test
    fun rawVarianceKeepsRelativeAccuracyWhenATailIsAdmittedFarFromTheOrigin() {
        val samples = listOf(
            Sample(0.0, 0.0, 1), Sample(0.019931267045644185, 0.0, 2),
            Sample(0.017623398201851992, 0.0714206739085058, 48), Sample(2.5, 0.0, 1)
        )
        val queries = doubleArrayOf(-8.7197, -4.980067732954356)
        val anchored = samples + listOf(Sample(-1000.0, 0.0, 1), Sample(1000.0, 0.0, 1))
        for (fixture in listOf(anchored)) {
            val expected = rawReference(fixture, 0.1, queries, 50.0)
            val actual = adaptiveRaw(estimator(fixture, 0.1), queries, 50.0)
            assertTrue(expected.second[1] > 0.0)
            assertEquals(expected.second[1], actual.second[1], 2e-8 * expected.second[1])
        }
    }

    @Test
    fun reflectedCopiesUseTheirOriginalCountsForVariance() {
        val samples = listOf(Sample(0.0, 0.0, 4), Sample(100.0, 0.0, 1))
        val kde = estimator(samples, 1000.0)
        val actual = adaptiveRaw(kde, doubleArrayOf(0.0), 10.0)
        val q = 2.0 * kernel(0.0, 10.0)
        assertEquals(4.0 * q, actual.first.single(), 1e-12)
        assertEquals(4.0 * q * q, actual.second.single(), 1e-12)
        // Counting the reflection as independent would incorrectly halve this variance.
        assertTrue(actual.second.single() > 4.0 * 2.0 * kernel(0.0, 10.0) * kernel(0.0, 10.0) * 1.99)
    }

    @Test
    fun pilotUsesReflectionBeforeSelectingBandwidths() {
        val samples = listOf(Sample(0.0, 0.0, 1), Sample(2.0, 0.0, 20),
            Sample(5.0, 0.0, 1), Sample(100.0, 0.0, 1))
        val widths = scales(samples, 10.0, 10.0)
        val oldPilot = fixedReference(samples, 10.0, doubleArrayOf(2.0), 10.0).single()
        val oldWidth = (1.0 / oldPilot).coerceIn(1.0, 10.0)
        assertTrue("fixture distinguishes reflected and mass-normalized pilots", abs(widths[1] - oldWidth) > 0.5)
        val queries = DoubleArray(81) { it * 0.125 }
        assertCurvesEqual(reference(samples, 10.0, queries, 10.0),
            estimator(samples, 10.0).getAdaptiveConfidenceIntervals(queries, 10.0), 1e-8)
    }

    @Test
    fun manyReflectionsPreserveUniformRateOnShortRecordings() {
        val samples = listOf(Sample(0.0, 0.2, 2))
        val queries = DoubleArray(21) { it * 0.01 }
        val kde = estimator(samples, 1000.0)
        for (scale in doubleArrayOf(1.0, 10.0, 100.0)) {
            assertArrayEquals(DoubleArray(queries.size) { 0.01 },
                kde.estimateAdaptiveDoseRate(queries, scale), 1e-10)
            assertCurvesEqual(reference(samples, 1000.0, queries, scale),
                kde.getAdaptiveConfidenceIntervals(queries, scale), 1e-8)
        }
    }

    @Test(timeout = 3000)
    fun enormousBandwidthDoesNotMaterializeTrillionsOfMirrors() {
        val kde = estimator(listOf(Sample(0.0, 0.25, 5)), 1000.0)
        val queries = DoubleArray(101) { it * 0.0025 }
        val actual = adaptiveRaw(kde, queries, 1e12)
        assertArrayEquals(DoubleArray(queries.size) { 20.0 }, actual.first, 1e-10)
        // The reflected kernel tends to the uniform weight 1 / duration.
        assertArrayEquals(DoubleArray(queries.size) { 80.0 }, actual.second, 1e-9)
    }

    @Test
    fun reflectedAdaptiveCurvePreservesTotalCounts() {
        val samples = listOf(Sample(0.0, 0.0, 2), Sample(1.0, 3.0, 40),
            Sample(6.0, 0.0, 7), Sample(10.0, 12.0, 9), Sample(40.0, 0.0, 1))
        val sensitivity = 10.0
        val scale = 10.0
        val widths = scales(samples, sensitivity, scale)
        val length = 40.0
        val cuts = mutableListOf(0.0, length)
        for ((i, sample) in samples.withIndex()) {
            val h = widths[i]
            for (direction in doubleArrayOf(1.0, -1.0)) {
                val lo = min(direction * sample.start, direction * sample.end)
                val hi = max(direction * sample.start, direction * sample.end)
                val first = ceil((-h - hi) / (2.0 * length)).toInt()
                val last = floor((length + h - lo) / (2.0 * length)).toInt()
                for (k in first..last) {
                    for (edge in doubleArrayOf(lo - h, lo + h, hi - h, hi + h)) {
                        val t = edge + k * 2.0 * length
                        if (t > 0.0 && t < length) cuts.add(t)
                    }
                }
            }
        }
        cuts.sort()
        val queries = ArrayList<Double>()
        val weights = ArrayList<Double>()
        for (i in 1 until cuts.size) {
            val half = (cuts[i] - cuts[i - 1]) / 2.0
            if (half == 0.0) continue
            val midpoint = cuts[i - 1] + half
            queries.add(midpoint - half / sqrt(3.0)); weights.add(half)
            queries.add(midpoint + half / sqrt(3.0)); weights.add(half)
        }
        val rates = estimator(samples, sensitivity).estimateAdaptiveDoseRate(queries.toDoubleArray(), scale)
        val counts = rates.indices.sumOf { rates[it] * sensitivity * weights[it] }
        assertEquals(samples.sumOf { it.counts }.toDouble(), counts, 1e-7)
    }

    @Test
    fun adaptiveEstimatesAreRestrictedToObservedTime() {
        val samples = listOf(Sample(0.0, 10.0, 20))
        val kde = estimator(samples, 1.0)
        val queries = doubleArrayOf(-1.0, Math.nextDown(0.0), 0.0, 10.0, Math.nextUp(10.0), 11.0)
        val actual = kde.getAdaptiveConfidenceIntervals(queries, 20.0)
        for (i in listOf(0, 1, 4, 5)) {
            assertEquals(0.0, actual.first[i], 0.0)
            assertEquals(0.0, actual.second[i], 0.0)
            assertEquals(0.0, actual.third[i], 0.0)
        }
        assertEquals(2.0, actual.first[2], 1e-10)
        assertEquals(2.0, actual.first[3], 1e-10)
    }

    @Test
    fun zeroCountIntervalsRetainTheCompleteObservationRange() {
        val samples = listOf(Sample(0.0, 100.0, 0), Sample(100.0, 10.0, 10), Sample(110.0, 100.0, 0))
        val queries = doubleArrayOf(100.0, 105.0, 110.0)
        val kde = estimator(samples, 1.0)
        assertEquals(0.0 to 210.0, kde.timestampBounds())
        assertArrayEquals(doubleArrayOf(0.5, 0.6875, 0.5), kde.estimateDoseRate(queries, 10.0), 1e-12)
        assertCurvesEqual(reference(samples, 1.0, queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun zeroCountExposureSurvivesCopyAndTimeoutButNotClear() {
        val kde = KernelDensityEstimator(1.0)
        kde.addSampleInterval(0.0, 100.0, 0)
        val queries = doubleArrayOf(0.0, 50.0, 100.0)
        val fixed = kde.getConfidenceIntervals(queries, 10.0)
        val adaptive = kde.getAdaptiveConfidenceIntervals(queries, 10.0)
        for (curve in listOf(fixed, adaptive)) {
            assertArrayEquals(DoubleArray(3), curve.first, 0.0)
            assertArrayEquals(DoubleArray(3), curve.second, 0.0)
            assertTrue(curve.third.all { it > 0.0 && it.isFinite() })
        }
        val copy = kde.copy()
        kde.clear()
        assertEquals(null, kde.timestampBounds())
        assertCurvesEqual(adaptive, copy.getAdaptiveConfidenceIntervals(queries, 10.0))
        copy.timeout = 20.0
        copy.addSampleInterval(100.0, 10.0, 0)
        assertEquals(90.0 to 110.0, copy.timestampBounds())
        val retained = listOf(Sample(90.0, 20.0, 0))
        assertCurvesEqual(reference(retained, 1.0, doubleArrayOf(90.0, 100.0, 110.0), 10.0),
            copy.getAdaptiveConfidenceIntervals(doubleArrayOf(90.0, 100.0, 110.0), 10.0))
    }

    @Test
    fun weakSourcesRemainAccurateAfterLargeContributionsLeaveTheSweep() {
        val queries = doubleArrayOf(7.9, 8.5, 9.0, 9.5, 10.0, 10.5, 10.999, 11.0, 11.0000001, 11.5, 13.0, 15.0)
        for (large in listOf(Sample(10.0, 0.0, 2_000_000_000), Sample(0.0, 10.0, 2_000_000_000))) {
            val samples = listOf(Sample(0.0, 0.0, 1), large, Sample(10.75, 0.0, 1),
                Sample(9.9, 3.0, 1), Sample(0.0, 30.0, 1), Sample(30.0, 0.0, 1))
            val expected = rawReference(samples, 1000.0, queries, 10.0)
            val kde = estimator(samples, 1000.0)
            val actual = adaptiveRaw(kde, queries, 10.0)
            val meanOnly = kde.estimateAdaptiveDoseRate(queries, 10.0)
            for (i in queries.indices) {
                if (queries[i] < 11.0) continue
                assertEquals("mean after large source at ${queries[i]}", expected.first[i], actual.first[i], 1e-10)
                assertEquals("variance after large source at ${queries[i]}", expected.second[i], actual.second[i], 1e-10)
                assertEquals("mean-only after large source at ${queries[i]}", expected.first[i] / 1000.0, meanOnly[i], 1e-12)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsortedQueriesAreRejected() {
        estimator(listOf(Sample(0.0, 10.0, 3)), 1.0).estimateAdaptiveDoseRate(doubleArrayOf(2.0, 1.0), 10.0)
    }
}
