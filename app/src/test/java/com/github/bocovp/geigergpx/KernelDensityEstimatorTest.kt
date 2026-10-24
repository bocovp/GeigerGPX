package com.github.bocovp.geigergpx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
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

    private fun scales(
        samples: List<Sample>, sensitivity: Double, scale: Double, endOverride: Double? = null
    ): List<Double> {
        val sortedCenters = samples.map { it.center }.sorted().toDoubleArray()
        val pilot = fixedReference(samples, sensitivity, sortedCenters, scale, endOverride)
        val byCenter = sortedCenters.indices.associate { sortedCenters[it] to pilot[it] }
        return samples.map { (scale * 0.1 / byCenter.getValue(it.center)).coerceIn(scale / 10.0, scale) }
    }

    /** Direct O(NQ) mean/variance sums, independent of polynomial accumulation. */
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
            val boundaryT = t.coerceIn(first, last)
            for ((sampleIndex, sample) in samples.withIndex()) {
                val h = widths[sampleIndex]
                // Preserve admission/eviction at represented support endpoints,
                // including epoch rounding of the outer source boundaries.
                if (t < sample.start - h || t >= sample.end + h) continue
                val boundaryMass = mass((boundaryT - last) / h, (boundaryT - first) / h)
                if (boundaryMass <= 1e-9) continue
                if (sample.duration == 0.0) {
                    val k = kernel(t - sample.start, h) / boundaryMass
                    rates[index] += sample.counts * k
                    variances[index] += sample.counts * k * k
                } else {
                    val lo = (t - sample.end) / h
                    val hi = (t - sample.start) / h
                    val density = sample.counts / sample.duration
                    rates[index] += density * mass(lo, hi) / boundaryMass
                    variances[index] += density * squaredMass(lo, hi) / h / (boundaryMass * boundaryMass)
                }
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
            val boundaryT = t.coerceIn(first, last)
            val rate = rates[index]
            val variance = variances[index]
            val backgroundLo = (boundaryT - last) / scale
            val backgroundHi = (boundaryT - first) / scale
            val backgroundMass = mass(backgroundLo, backgroundHi)
            val backgroundSquaredMass = squaredMass(backgroundLo, backgroundHi)
            val backgroundExposure = if (backgroundSquaredMass > 1e-12) {
                scale * backgroundMass * backgroundMass / backgroundSquaredMass
            } else 0.0
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
    fun backgroundDoseKeepsMaximumScaleAndFixedIntervalResults() {
        val samples = listOf(Sample(0.0, 100.0, 10))
        val kde = estimator(samples, 2.0)
        val queries = DoubleArray(101) { it.toDouble() }
        assertEquals(listOf(10.0), scales(samples, 2.0, 10.0))
        assertCurvesEqual(kde.getConfidenceIntervals(queries, 10.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0))
    }

    @Test
    fun highDoseClampsAtOneTenthOfSliderScale() {
        val samples = listOf(Sample(0.0, 100.0, 10000))
        val kde = estimator(samples, 1.0)
        val queries = DoubleArray(201) { it * 0.5 }
        assertEquals(listOf(1.0), scales(samples, 1.0, 10.0))
        assertCurvesEqual(kde.getConfidenceIntervals(queries, 1.0), kde.getAdaptiveConfidenceIntervals(queries, 10.0), 1e-6)
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
    fun tinyIntervalTailKeepsSquaredKernelVarianceAccurate() {
        val kde = estimator(listOf(Sample(0.0, 100.0, 10000)), 1.0)
        val t = 100.999999
        val delta = 1.0 - (t - 100.0)
        // h = 1, boundary mass = 1/2. Integrate from u = 1 - delta to 1
        // in powers of delta to avoid subtracting nearly equal primitives.
        val mass = 0.75 * delta * delta * (1.0 - delta / 3.0)
        val squareMass = 0.5625 * delta * delta * delta *
            (4.0 / 3.0 - delta + delta * delta / 5.0)
        val rate = 100.0 * mass / 0.5
        val variance = 100.0 * squareMass / 0.25
        val exposure = min(rate / variance, 5.0 * 10.0 / 6.0)
        val effectiveCount = rate * exposure
        val ci = ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)
        val expectedHigh = (ci.chi2R(1) + effectiveCount * (ci.chi2R(2) - ci.chi2R(1))) * 0.5 / exposure
        val actual = kde.getAdaptiveConfidenceIntervals(doubleArrayOf(t), 10.0)
        assertEquals(rate, actual.first.single(), rate * 1e-6)
        assertEquals(expectedHigh, actual.third.single(), expectedHigh * 1e-6)
    }

    @Test
    fun lastSupportEdgeHasContinuousPositiveUpperBound() {
        val kde = estimator(listOf(Sample(0.0, 100.0, 10000)), 1.0)
        val queries = doubleArrayOf(100.999999, 101.0, 101.000001)
        val actual = kde.getAdaptiveConfidenceIntervals(queries, 10.0)
        assertTrue(actual.first[0] > 0.0)
        assertEquals(0.0, actual.first[1], 0.0)
        assertEquals(0.0, actual.first[2], 0.0)
        assertTrue(actual.third[1] > 0.0)
        assertEquals(actual.third[1], actual.third[0], 1e-8)
        assertEquals(actual.third[1], actual.third[2], 1e-12)
    }

    @Test
    fun squaredKernelIntegralRemainsAccurateNearSupportEdge() {
        // The confidence exposure cap can hide cancellation in a vanishing tail;
        // check this numerical primitive directly against its endpoint expansion.
        val kde = KernelDensityEstimator(1.0)
        val integral = KernelDensityEstimator::class.java.getDeclaredMethod(
            "adaptiveSquaredKernelIntegral", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType
        ).apply { isAccessible = true }
        for (width in doubleArrayOf(1e-3, 1e-6, 1e-9)) {
            val lo = 1.0 - width
            val delta = 1.0 - lo
            val expected = 0.5625 * delta * delta * delta *
                (4.0 / 3.0 - delta + delta * delta / 5.0)
            val actual = integral.invoke(kde, lo, 1.0) as Double
            assertEquals(expected, actual, expected * 1e-6)
        }
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
        val remaining = listOf(Sample(30.0, 5.0, 8), Sample(40.0, 0.0, 2))
        val queries = DoubleArray(81) { it * 0.5 + 20.0 }
        assertEquals(30.0 to 40.0, kde.timestampBounds())
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
        val prunedSamples = listOf(Sample(4000.0, 20.0, 4))
        val prunedQueries = DoubleArray(41) { 4000.0 + it * 0.5 }
        assertEquals(4000.0 to 4020.0, copy.timestampBounds())
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
    fun adaptiveBoundaryNormalizationUsesEachSourceScaleOnBothEdges() {
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
    fun adaptiveShortObservationExtentAndLiveOverrideKeepBothBoundaryMasses() {
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
    fun adaptiveDirectAndPolynomialRangesAgreeAtRepeatedTransitionQueries() {
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
                        // Batch partitioning must agree with a boundary-only or
                        // interior-only request at the same repeated timestamp.
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
        val samples = listOf(Sample(0.0, 100.0, 10000))
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
        // Distant anchors put the same tail in the polynomial interior; the
        // unanchored data exercises the direct path for a short measurement.
        val anchored = samples + listOf(Sample(-1000.0, 0.0, 1), Sample(1000.0, 0.0, 1))
        for (fixture in listOf(samples, anchored)) {
            val expected = rawReference(fixture, 0.1, queries, 50.0)
            val actual = adaptiveRaw(estimator(fixture, 0.1), queries, 50.0)
            assertTrue(expected.second[1] > 0.0)
            assertEquals(expected.second[1], actual.second[1], 2e-8 * expected.second[1])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsortedQueriesAreRejected() {
        estimator(listOf(Sample(0.0, 10.0, 3)), 1.0).estimateAdaptiveDoseRate(doubleArrayOf(2.0, 1.0), 10.0)
    }
}
